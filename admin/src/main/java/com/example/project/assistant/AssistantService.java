package com.example.project.assistant;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.project.attendance.*;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.report.*;
import com.example.project.security.AccessPolicy;
import com.example.project.service.IDeptService;
import com.example.project.utils.SessionUtils;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 身份、幂等、时间预算与副作用归Java；模型只能选择固定工具，不能声明业务事实。 */
@Service
public class AssistantService {
    @Resource private AssistantConversationMapper conversations;
    @Resource private AssistantRunMapper runs;
    @Resource private AssistantToolCallMapper calls;
    @Resource private UserMapper users;
    @Resource private AccessPolicy access;
    @Resource private IDeptService departments;
    @Resource private MonthlyReportService reports;
    @Resource private AttendanceService attendance;
    @Resource private ReportExportService exports;
    @Resource private AssistantPlannerClient planner;
    @Resource private AttendanceClock clock;
    @Resource private TransactionTemplate tx;
    @Resource private ObjectMapper json;
    private static final List<String> TOOLS=Arrays.asList("query_attendance_summary","explain_attendance_day","create_attendance_export","get_export_status");

    public Map<String,Object> status(){actor();return planner.status();}
    public Map<String,Object> createConversation(JsonNode input) {
        fields(input,"title");User actor=actor();String title=input.path("title").isMissingNode()?"新的考勤对话":text(input,"title",1,100);
        AssistantConversation row=new AssistantConversation();row.setOwnerId(actor.getId());row.setTitle(title);row.setCreatedAt(clock.now());row.setUpdatedAt(clock.now());
        if(conversations.insert(row)!=1) throw new ServiceException("会话保存失败");return conversationView(row);
    }
    public Map<String,Object> conversations(int pageNum,int pageSize) {
        User actor=actor();if(pageNum<1 || pageSize<1 || pageSize>100) invalid("分页参数无效");recoverExpired();
        Page<AssistantConversation> page=conversations.selectPage(new Page<>(pageNum,pageSize),new QueryWrapper<AssistantConversation>().eq("owner_id",actor.getId()).orderByDesc("updated_at").orderByDesc("id"));
        List<Map<String,Object>> rows=new ArrayList<>();for(AssistantConversation row:page.getRecords())rows.add(conversationView(row));
        return map("records",rows,"total",page.getTotal());
    }
    public Map<String,Object> conversation(Long id) {
        User actor=actor();AssistantConversation row=ownedConversation(id,actor.getId());recoverExpired();
        List<AssistantRun> rows=runs.selectList(new QueryWrapper<AssistantRun>().eq("conversation_id",id).eq("owner_id",actor.getId()).orderByDesc("turn_index").last("LIMIT 20"));
        Collections.reverse(rows);List<Map<String,Object>> result=new ArrayList<>();Projection projection=new Projection(actor.getId());for(AssistantRun run:rows)result.add(runView(run,projection));
        return map("conversation",conversationView(row),"runs",result);
    }
    public Map<String,Object> detail(String id){User actor=actor();recoverExpired();return runView(ownedRun(id,actor.getId()));}
    public Map<String,Object> byRequest(String key) {
        User actor=actor();validateKey(key);recoverExpired();AssistantRun row=runs.selectOne(new QueryWrapper<AssistantRun>().eq("owner_id",actor.getId()).eq("request_key",key));
        if(row==null) throw new ServiceException("404","未找到此请求");return runView(row);
    }

    /** 模型调用在事务之外；重复或并发重试只读取原运行，不重新规划或执行。 */
    public Map<String,Object> run(JsonNode input) {
        fields(input,"conversationId","requestKey","message");long conversationId=positiveLong(input,"conversationId");String key=text(input,"requestKey",8,64);validateKey(key);
        String message=text(input,"message",1,2000);User actor=actor();String requestHash=digest(conversationId+"\n"+message);boolean[] created={false};
        AssistantRun row=tx.execute(status -> {
            if(users.lockActiveId(actor.getId())==null)AccessPolicy.deny();actor();recoverExpired();
            AssistantRun previous=runs.selectOne(new QueryWrapper<AssistantRun>().eq("owner_id",actor.getId()).eq("request_key",key));
            if(previous!=null){if(!requestHash.equals(previous.getRequestHash()))conflict("同一请求编号不能用于不同问题或会话");return previous;}
            ownedConversation(conversationId,actor.getId());
            if(runs.selectCount(new QueryWrapper<AssistantRun>().eq("owner_id",actor.getId()).eq("state","RUNNING"))>0)conflict("已有对话正在处理，请先查询原请求");
            AssistantRun fresh=new AssistantRun();fresh.setId(UUID.randomUUID().toString());fresh.setConversationId(conversationId);fresh.setOwnerId(actor.getId());
            // 当前用户行锁覆盖编号分配和插入；同一会话只有该owner能够写入。
            fresh.setTurnIndex(runs.nextTurnIndex(conversationId));
            fresh.setRequestKey(key);fresh.setRequestHash(requestHash);fresh.setMessage(message);fresh.setAnswer("");fresh.setState("RUNNING");fresh.setMode(planner.mode());
            fresh.setCreatedAt(clock.now());fresh.setDeadlineAt(clock.now().plusSeconds(60));if(runs.insert(fresh)!=1)throw new ServiceException("运行保存失败");created[0]=true;return fresh;
        });
        if(!created[0])return runView(row);
        try {
            for(int round=0;round<=5;round++) {
                checkBudget(row);actor();
                List<AssistantToolCall> receipts=receipts(row.getId());
                if(receipts.size()>=5){finish(row,"FAILED","TOOL_LIMIT_REACHED",null);break;}
                JsonNode plan=planner.plan(planInput(row,actor(),receipts),Duration.between(clock.now(),row.getDeadlineAt()).toMillis());
                checkBudget(row);actor();
                JsonNode suggestions=plan.path("toolCalls");
                if(suggestions.isEmpty()){finish(row,receipts.isEmpty()?"NEEDS_INPUT":"COMPLETED",null,plan.path("message").asText());break;}
                JsonNode suggested=suggestions.get(0);fields(suggested,"name","arguments");String tool=text(suggested,"name",1,80);JsonNode arguments=suggested.path("arguments");
                if(!TOOLS.contains(tool))throw new AssistantPlannerClient.PlannerFailure("TOOL_NOT_ALLOWED");
                validateArguments(tool,arguments);
                AssistantToolCall receipt=execute(row,tool,arguments,receipts.size()+1);
                if(!"SUCCEEDED".equals(receipt.getState())){finish(row,"FAILED",receipt.getErrorCode(),null);break;}
            }
        }catch(BudgetExceeded ignored){finish(row,"TIMED_OUT","RUN_TIMEOUT",null);}
        catch(AssistantPlannerClient.PlannerFailure failure){finish(row,"MODEL_TIMEOUT".equals(failure.code)?"TIMED_OUT":"FAILED",failure.code,null);}
        catch(ServiceException failure){finish(row,"FAILED",safeCode(failure),null);}
        catch(Exception ignored){finish(row,"FAILED","ASSISTANT_FAILED",null);}
        return runView(ownedRun(row.getId(),actor.getId()));
    }

    private AssistantToolCall execute(AssistantRun original,String tool,JsonNode arguments,int sequence) {
        try {
            return tx.execute(status -> {
                AssistantRun row=lockRunning(original);checkBudget(row);User actor=actor();
                if(!row.getOwnerId().equals(actor.getId()))AccessPolicy.deny();
                Map<String,Object> result=invoke(actor,row,tool,arguments);checkBudget(row);actor();
                AssistantToolCall receipt=newReceipt(row,sequence,tool,arguments,"SUCCEEDED",result,null);
                if(calls.insert(receipt)!=1)throw new IllegalStateException("receipt write failed");return receipt;
            });
        }catch(BudgetExceeded failure){throw failure;}
        catch(Exception failure) {
            String code=failure instanceof ServiceException?safeCode((ServiceException)failure):failure instanceof AssistantPlannerClient.PlannerFailure?((AssistantPlannerClient.PlannerFailure)failure).code:"TOOL_FAILED";
            return tx.execute(status -> {
                AssistantRun row=lockRunning(original);checkBudget(row);
                AssistantToolCall receipt=newReceipt(row,sequence,tool,arguments,"FAILED",map("message",toolError(code),"source","Java考勤工具校验"),code);
                if(calls.insert(receipt)!=1)throw new IllegalStateException("receipt write failed");return receipt;
            });
        }
    }
    private Map<String,Object> invoke(User actor,AssistantRun run,String tool,JsonNode arguments) {
        if("query_attendance_summary".equals(tool)) {
            Map<String,Object> report=reports.build(actor.getId(),arguments.path("month").asText(),arguments.path("scope").asText(),nullableInt(arguments,"deptId"),false);
            Map<String,Object> result=pick(report,"month","scope","deptId","departmentName","asOf","calculationVersion","statistics","unsettled");
            result.put("_scopeSignature",report.get("scopeSignature"));result.put("_accessSignature",report.get("accessSignature"));
            result.put("source","考勤报表中心 · "+MonthlyReportService.CALCULATION_VERSION);return result;
        }
        if("explain_attendance_day".equals(tool)) {
            LocalDate date;try{date=LocalDate.parse(arguments.path("date").asText());}catch(Exception ignored){throw new ServiceException("422","日期格式应为YYYY-MM-DD");}
            Map<String,Object> day=attendance.day(actor.getId(),date);Map<String,Object> result=pick(day,"businessDate","status","workingDay","policyId","startTime","endTime","graceMinutes","lateMinutes","earlyMinutes","missingIn","missingOut","leaveDays","pendingCount");
            result.put("checkIn",event((Records)day.get("checkIn")));result.put("checkOut",event((Records)day.get("checkOut")));result.put("source","考勤工作台 · 本人每日结果");return result;
        }
        if("create_attendance_export".equals(tool)) {
            access.requireActiveActor(actor.getId(),"records.list","records.export");ReportExportService.CreateInput input=new ReportExportService.CreateInput();
            input.setRequestKey("ai_"+run.getId());input.setMonth(arguments.path("month").asText());input.setScope(arguments.path("scope").asText());input.setDeptId(nullableInt(arguments,"deptId"));
            Map<String,Object> result=exports.create(input);result.put("source","考勤报表中心 · 本人导出任务");return result;
        }
        Map<String,Object> result=exports.detail(positiveLong(arguments,"jobId"));result.put("source","考勤报表中心 · 本人导出任务");return result;
    }
    private Map<String,Object> event(Records record){return record==null?null:map("action",record.getAction(),"occurredAt",record.getOccurredAt(),"createTime",record.getCreateTime(),"source",record.getSource());}
    private AssistantToolCall newReceipt(AssistantRun run,int sequence,String tool,JsonNode arguments,String state,Map<String,Object> data,String code) {
        AssistantToolCall receipt=new AssistantToolCall();receipt.setRunId(run.getId());receipt.setCallOrder(sequence);receipt.setToolName(tool);receipt.setArgumentsJson(arguments.toString());
        receipt.setState(state);receipt.setResultJson(serialize(data));receipt.setErrorCode(code);receipt.setCreatedAt(clock.now());return receipt;
    }
    private Map<String,Object> planInput(AssistantRun row,User actor,List<AssistantToolCall> receipts) {
        List<Map<String,Object>> history=new ArrayList<>();
        List<AssistantRun> previous=runs.selectList(new QueryWrapper<AssistantRun>().eq("owner_id",actor.getId()).eq("conversation_id",row.getConversationId()).ne("id",row.getId()).ne("state","RUNNING").orderByDesc("turn_index").last("LIMIT 6"));
        Projection projection=new Projection(actor.getId());
        Collections.reverse(previous);for(AssistantRun item:previous)history.add(map("message",item.getMessage(),"answer",clip(String.valueOf(runView(item,projection).get("answer")),2000)));
        List<Map<String,Object>> steps=new ArrayList<>();for(AssistantToolCall item:receipts)steps.add(map("toolName",item.getToolName(),"arguments",parse(item.getArgumentsJson()),"success","SUCCEEDED".equals(item.getState()),"data",projection.data(item)));
        List<Map<String,Object>> options=new ArrayList<>();
        if(access.isAdmin(actor) || "manager".equals(actor.getRole())) {
            QueryWrapper<Dept> query=new QueryWrapper<>();if(!access.isAdmin(actor)){List<Integer> ids=access.departmentIds(actor);if(ids.isEmpty())query.apply("1=0");else query.in("id",ids);}
            for(Dept dept:departments.list(query.orderByAsc("id").last("LIMIT 50")))options.add(map("id",dept.getId(),"name",clip(dept.getName(),100)));
        }
        boolean allowExport;try{access.requireActiveActor(actor.getId(),"records.list","records.export");allowExport=true;}catch(ServiceException ignored){allowExport=false;}
        return map("contractVersion",AssistantPlannerClient.CONTRACT,"message",row.getMessage(),"today",clock.today().toString(),"departments",options,"history",history,"steps",steps,"allowExport",allowExport);
    }

    private void finish(AssistantRun original,String state,String code,String clarification) {
        tx.executeWithoutResult(status -> {
            AssistantRun row=runs.lock(original.getId());if(row==null || !"RUNNING".equals(row.getState()))return;
            String finalState=state,finalCode=code;
            if(!clock.now().isBefore(row.getDeadlineAt()) && !"TIMED_OUT".equals(state)){finalState="TIMED_OUT";finalCode="RUN_TIMEOUT";}
            String answer=factAnswer(receipts(row.getId()),finalState,clarification);
            if("TOOL_LIMIT_REACHED".equals(finalCode))answer+="\n已达到每轮5个工具的上限，尚不能确认问题已全部完成；请核对已完成回执后继续提问。";
            runs.update(null,new UpdateWrapper<AssistantRun>().eq("id",row.getId()).eq("state","RUNNING").set("state",finalState).set("error_code",finalCode).set("answer",answer).set("finished_at",clock.now()));
            conversations.update(null,new UpdateWrapper<AssistantConversation>().eq("id",row.getConversationId()).eq("owner_id",row.getOwnerId()).set("updated_at",clock.now()));
        });
    }
    /** 有业务回执时只使用确定模板，忽略模型对数字、成功或失败的自由描述。 */
    private String factAnswer(List<AssistantToolCall> receipts,String state,String clarification) {
        List<String> lines=new ArrayList<>();
        for(AssistantToolCall receipt:receipts) {
            JsonNode data=parse(receipt.getResultJson());
            if(!"SUCCEEDED".equals(receipt.getState())){lines.add("工具未执行成功："+toolError(receipt.getErrorCode()));continue;}
            switch(receipt.getToolName()) {
                case "query_attendance_summary":JsonNode stats=data.path("statistics");lines.add(data.path("month").asText()+"月报已查询："+("SELF".equals(data.path("scope").asText())?"本人":"所选部门")+"，迟到"+stats.path("lateDays").asText("0")+"人次日、早退"+stats.path("earlyDays").asText("0")+"人次日、缺卡"+stats.path("missingDays").asText("0")+"人次日、请假"+stats.path("leaveDays").asText("0")+"人天。详见来源数据。");break;
                case "explain_attendance_day":
                    String dayLabel=dayStatusLabel(data);
                    if("RESTRICTED".equals(data.path("status").textValue()))lines.add(data.path("businessDate").asText()+"本人日结果："+dayLabel+"。");
                    else lines.add(data.path("businessDate").asText()+"本人日结果："+dayLabel+"，迟到"+data.path("lateMinutes").asText("0")+"分钟，早退"+data.path("earlyMinutes").asText("0")+"分钟，请假"+data.path("leaveDays").asText("0")+"天。");
                    break;
                case "create_attendance_export":lines.add("已创建导出任务 #"+data.path("id").asText()+"，回执状态："+data.path("state").asText()+"。文件需生成后到报表中心下载。");break;
                case "get_export_status":lines.add("导出任务 #"+data.path("id").asText()+"，查询时状态："+data.path("state").asText()+"。");break;
                default:break;
            }
        }
        if(!Arrays.asList("COMPLETED","NEEDS_INPUT").contains(state))lines.add("本轮"+("TIMED_OUT".equals(state)?"已超时":"INTERRUPTED".equals(state)?"已中断":"未完成")+"，已提交的工具回执仍可查询；请核对已有任务后再发起新问题。");
        if(lines.isEmpty())return "本轮未执行业务工具。"+(clarification==null || clarification.trim().isEmpty()?"请提供明确月份、本人或部门范围，或需要查询的日期、任务编号。":"助手提示："+clip(clarification,1900));
        return String.join("\n",lines);
    }
    /** 与考勤工作台 dayLabel 的已知状态和缺卡优先级保持一致。 */
    private static String dayStatusLabel(JsonNode day) {
        JsonNode statusNode=day.path("status");String status=statusNode.isTextual()?statusNode.textValue():"";
        if("RESTRICTED".equals(status))return "无权查看历史";
        if((day.path("missingIn").isBoolean() && day.path("missingIn").booleanValue()) ||
                (day.path("missingOut").isBoolean() && day.path("missingOut").booleanValue()))return "待核对 / 缺卡";
        switch(status) {
            case "NORMAL":case "PRESENT":return "正常";
            case "LATE":return "迟到";
            case "EARLY":return "早退";
            case "LATE_AND_EARLY":return "迟到且早退";
            case "MISSING":case "MISSING_CARD":return "待核对 / 缺卡";
            case "LEAVE":case "ON_LEAVE":return "已请假";
            case "IN_PROGRESS":return "进行中";
            case "REST":case "REST_DAY":return "休息日";
            case "UPCOMING":case "FUTURE":return "尚未到来";
            case "LEGACY":return "旧数据不推算";
            case "NOT_APPLICABLE":return "未启用新口径";
            default:return "待核对";
        }
    }
    public void recoverExpired() {
        List<AssistantRun> expired=runs.selectList(new QueryWrapper<AssistantRun>().eq("state","RUNNING").le("deadline_at",clock.now()));
        for(AssistantRun item:expired)tx.executeWithoutResult(status -> {
            AssistantRun row=runs.lock(item.getId());if(row!=null && "RUNNING".equals(row.getState()) && !clock.now().isBefore(row.getDeadlineAt()))
                runs.update(null,new UpdateWrapper<AssistantRun>().eq("id",row.getId()).eq("state","RUNNING").le("deadline_at",clock.now()).set("state","INTERRUPTED").set("error_code","RUN_INTERRUPTED").set("finished_at",clock.now()).set("answer",factAnswer(receipts(row.getId()),"INTERRUPTED",null)));
        });
    }
    private AssistantRun lockRunning(AssistantRun original) {
        AssistantRun row=runs.lock(original.getId());if(row==null || !"RUNNING".equals(row.getState()))throw new AssistantPlannerClient.PlannerFailure("RUN_NOT_ACTIVE");return row;
    }
    private void checkBudget(AssistantRun row){if(!clock.now().isBefore(row.getDeadlineAt()))throw new BudgetExceeded();}
    private User actor(){User actor=SessionUtils.getUser();return access.requireActiveActor(actor.getId(),"records.list");}
    private AssistantConversation ownedConversation(Long id,Integer owner) {
        AssistantConversation row=id==null?null:conversations.selectById(id);if(row==null)throw new ServiceException("404","会话不存在");if(!owner.equals(row.getOwnerId()))AccessPolicy.deny();return row;
    }
    private AssistantRun ownedRun(String id,Integer owner) {
        AssistantRun row=id==null?null:runs.selectById(id);if(row==null)throw new ServiceException("404","运行不存在");if(!owner.equals(row.getOwnerId()))AccessPolicy.deny();return row;
    }
    private List<AssistantToolCall> receipts(String id){return calls.selectList(new QueryWrapper<AssistantToolCall>().eq("run_id",id).orderByAsc("call_order"));}
    private Map<String,Object> conversationView(AssistantConversation row){return map("id",row.getId(),"title",row.getTitle(),"createdAt",row.getCreatedAt(),"updatedAt",row.getUpdatedAt());}
    private Map<String,Object> runView(AssistantRun row){return runView(row,new Projection(row.getOwnerId()));}
    private Map<String,Object> runView(AssistantRun row,Projection projection) {
        List<Map<String,Object>> tools=new ArrayList<>();boolean restricted=false;
        for(AssistantToolCall call:receipts(row.getId())) {
            JsonNode data=projection.data(call);restricted|=data.path("visibilityChanged").asBoolean(false);
            tools.add(map("sequence",call.getCallOrder(),"toolName",call.getToolName(),"arguments",parse(call.getArgumentsJson()),"state",call.getState(),"data",data,"errorCode",call.getErrorCode(),"createdAt",call.getCreatedAt()));
        }
        return map("id",row.getId(),"conversationId",row.getConversationId(),"turnIndex",row.getTurnIndex(),"message",row.getMessage(),"answer",restricted?VISIBILITY_CHANGED:row.getAnswer(),"state",row.getState(),"mode",row.getMode(),"errorCode",row.getErrorCode(),"createdAt",row.getCreatedAt(),"finishedAt",row.getFinishedAt(),"tools",tools);
    }
    private static final String VISIBILITY_CHANGED="历史可见范围已变化，请重新查询";
    /** 缓存仅存于本次投影，不跨模型等待复用；旧回执没有证据时不猜测历史权限。 */
    private class Projection {
        private final Integer owner;
        private final boolean active;
        private final Map<String,Map<String,Object>> proofs=new HashMap<>();
        Projection(Integer owner){this.owner=owner;boolean allowed;try{access.requireActiveActor(owner,"records.list");allowed=true;}catch(Exception denied){allowed=false;}active=allowed;}
        JsonNode data(AssistantToolCall call) {
            JsonNode original=parse(call.getResultJson());JsonNode args=parse(call.getArgumentsJson());
            if(!active && "SUCCEEDED".equals(call.getState()))return json.valueToTree(map("visibilityChanged",true,"message",VISIBILITY_CHANGED,"source","Java当前权限校验"));
            if("SUCCEEDED".equals(call.getState()) && "query_attendance_summary".equals(call.getToolName()) && "DEPARTMENT".equals(args.path("scope").asText())) {
                String key=args.path("month").asText()+"|"+args.path("scope").asText()+"|"+args.path("deptId").asText();
                if(!proofs.containsKey(key)) {
                    try {
                        Map<String,Object> report=reports.build(owner,args.path("month").asText(),"DEPARTMENT",nullableInt(args,"deptId"),false);
                        proofs.put(key,pick(report,"scopeSignature","accessSignature"));
                    }catch(Exception denied){proofs.put(key,null);}
                }
                Map<String,Object> current=proofs.get(key);
                if(current==null || !original.path("_scopeSignature").isTextual() || !original.path("_accessSignature").isTextual() ||
                        !Objects.equals(original.path("_scopeSignature").asText(),current.get("scopeSignature")) || !Objects.equals(original.path("_accessSignature").asText(),current.get("accessSignature")))
                    return json.valueToTree(map("visibilityChanged",true,"message",VISIBILITY_CHANGED,"source","考勤报表中心 · 当前权限校验"));
            }
            JsonNode clean=original.deepCopy();stripPrivate(clean);return clean;
        }
    }
    private void stripPrivate(JsonNode node) {
        if(node.isObject()) {
            List<String> remove=new ArrayList<>();node.fieldNames().forEachRemaining(key -> {if(key.startsWith("_"))remove.add(key);});((ObjectNode)node).remove(remove);
            node.elements().forEachRemaining(this::stripPrivate);
        }else if(node.isArray())node.elements().forEachRemaining(this::stripPrivate);
    }
    private void validateArguments(String tool,JsonNode args) {
        if(Arrays.asList("query_attendance_summary","create_attendance_export").contains(tool)) {
            fields(args,"month","scope","deptId");String month=text(args,"month",7,7);AttendancePolicyService.parseMonth(month);String scope=text(args,"scope",4,10);
            if(!Arrays.asList("SELF","DEPARTMENT").contains(scope))invalid("范围须为SELF或DEPARTMENT");Integer dept=nullableInt(args,"deptId");
            if(("SELF".equals(scope) && dept!=null) || ("DEPARTMENT".equals(scope) && dept==null))invalid("请明确本人或部门范围");
        }else if("explain_attendance_day".equals(tool)){fields(args,"date");String date=text(args,"date",10,10);try{LocalDate.parse(date);}catch(Exception ignored){invalid("日期格式应为YYYY-MM-DD");}}
        else {fields(args,"jobId");positiveLong(args,"jobId");}
    }
    private static void fields(JsonNode node,String...allowed) {
        if(node==null || !node.isObject())invalid("请求须为JSON对象");Set<String> accepted=new HashSet<>(Arrays.asList(allowed));Iterator<String> names=node.fieldNames();while(names.hasNext())if(!accepted.contains(names.next()))invalid("请求包含不支持的字段");
    }
    private static String text(JsonNode node,String name,int min,int max){JsonNode value=node.path(name);if(!value.isTextual())invalid("缺少或无效字段："+name);String result=value.asText().trim();if(result.length()<min || result.length()>max)invalid("字段长度无效："+name);return result;}
    private static long positiveLong(JsonNode node,String name){JsonNode value=node.path(name);if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<1)invalid("编号无效："+name);return value.asLong();}
    private static Integer nullableInt(JsonNode node,String name){JsonNode value=node.path(name);if(value.isMissingNode() || value.isNull())return null;if(!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<1)invalid("部门编号无效");return value.asInt();}
    private static void validateKey(String key){if(key==null || !key.matches("[A-Za-z0-9_-]{8,64}"))invalid("请求编号应为8至64位字母、数字、横线或下划线");}
    private static String safeCode(ServiceException error){return "403".equals(error.getCode())?"ACCESS_DENIED":"401".equals(error.getCode())?"SESSION_EXPIRED":"422".equals(error.getCode())?"INVALID_ARGUMENTS":"409".equals(error.getCode())?"BUSINESS_CONFLICT":"TOOL_FAILED";}
    private static String toolError(String code){return "ACCESS_DENIED".equals(code)?"当前账号无权执行此工具":"INVALID_ARGUMENTS".equals(code)?"工具条件无效，请补充明确条件":"BUSINESS_CONFLICT".equals(code)?"业务状态冲突，请到报表中心核对":"EXPORT_LIMIT_REACHED".equals(code)?"同一轮最多创建一个导出任务":"工具暂时失败，请核对已有结果";}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException(failure);}}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception ignored){return json.createObjectNode();}}
    private static Map<String,Object> pick(Map<String,Object> input,String...fields){Map<String,Object> result=new LinkedHashMap<>();for(String field:fields)if(input.containsKey(field))result.put(field,input.get(field));return result;}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static String clip(String text,int length){return text==null?"":text.substring(0,Math.min(length,text.length()));}
    private static String digest(String value){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void invalid(String message){throw new ServiceException("422",message);}
    private static void conflict(String message){throw new ServiceException("409",message);}
    private static class BudgetExceeded extends RuntimeException {}
}
