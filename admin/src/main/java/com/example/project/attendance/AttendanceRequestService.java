package com.example.project.attendance;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.security.AccessPolicy;
import com.example.project.service.IUserService;
import com.example.project.utils.SessionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 用户行锁统一本人的补卡、请假创建与状态迁移，审批结果与事件/历史同事务提交。 */
@Service
public class AttendanceRequestService {
    @Resource private AttendanceRequestMapper requests;
    @Resource private AttendanceApprovalMapper history;
    @Resource private AttendanceDayMapper days;
    @Resource private UserMapper userMapper;
    @Resource private IUserService users;
    @Resource private AttendanceService attendance;
    @Resource private AttendancePolicyService policies;
    @Resource private AttendanceClock clock;
    @Resource private AccessPolicy access;
    @Resource private TransactionTemplate tx;
    @Resource private ObjectMapper json;
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> LEAVE_TYPES=new HashSet<>(Arrays.asList("事假","病假","婚假","丧假"));

    /** 不接受申请人、部门、状态或审批人字段，未知客户端字段不会参与业务赋值。 */
    @Data public static class CreateInput {
        private String requestKey,kind,reason,businessDate,action,correctionTime;
        private String startDate,startHalf,endDate,endHalf,leaveType;
    }
    @Data public static class DecisionInput {private Integer version;private String decision,comment;}
    @Data public static class CancelInput {private Integer version;}

    public Map<String,Object> list(String scope,int pageNum,int pageSize) {
        User actor=SessionUtils.getUser();
        if(pageNum<1 || pageSize<1 || pageSize>100) invalid("分页范围应为页码至少1、每页1至100条");
        if(!Arrays.asList("mine","pending","all").contains(scope)) invalid("申请范围无效");
        List<String> kinds=readableKinds();
        QueryWrapper<AttendanceRequest> query=new QueryWrapper<AttendanceRequest>().in("kind",kinds);
        if("mine".equals(scope)) query.eq("user_id",actor.getId());
        else {
            requireReviewerRole(actor);
            if(!"ADMIN".equals(actor.getRole())) {
                List<Integer> departments=access.departmentIds(),visible=access.visibleUserIds();
                if(departments.isEmpty()) query.eq("user_id",actor.getId());
                else query.and(part -> part.eq("user_id",actor.getId()).or(other -> other.in("user_id",visible).in("dept_id",departments)));
            }
            if("pending".equals(scope)) query.eq("state","PENDING").ne("user_id",actor.getId());
        }
        query.orderByDesc("created_at","id");
        Page<AttendanceRequest> page=requests.selectPage(new Page<>(pageNum,pageSize),query);
        fillNames(page.getRecords());
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",page.getRecords());result.put("total",page.getTotal());return result;
    }

    public Map<String,Object> detail(Long id) {
        AttendanceRequest row=require(id);requireRead(row);fillNames(Collections.singletonList(row));
        List<AttendanceApproval> entries=history.selectList(new QueryWrapper<AttendanceApproval>().eq("request_id",id).orderByAsc("id"));
        for(AttendanceApproval entry:entries) {User actor=users.getById(entry.getActorId());entry.setActorName(actor==null?"人员"+entry.getActorId():actor.getName());}
        Map<String,Object> result=new LinkedHashMap<>();result.put("request",row);result.put("history",entries);return result;
    }

    public AttendanceRequest create(CreateInput input) {
        User actor=SessionUtils.getUser();AttendanceRequest normalized=normalize(input);
        StpUtil.checkPermission(permission(normalized.getKind(),"add"));
        String hash=fingerprint(normalized);
        return tx.execute(status -> {
            lockUser(actor.getId());StpUtil.checkPermission(permission(normalized.getKind(),"add"));
            AttendanceRequest old=requests.selectOne(new QueryWrapper<AttendanceRequest>().eq("user_id",actor.getId()).eq("request_key",normalized.getRequestKey()));
            if(old!=null) {
                if(!hash.equals(old.getRequestHash())) conflict("同一请求编号不能用于不同的申请内容");
                fillNames(Collections.singletonList(old));return old;
            }
            if("CORRECTION".equals(normalized.getKind())) attendance.validateCorrection(actor.getId(),normalized.getBusinessDate(),normalized.getAction(),normalized.getCorrectionTime());
            else {validateLeaveDates(normalized,actor.getId());requireNoOverlap(normalized,actor.getId(),null);}
            normalized.setUserId(actor.getId());normalized.setDeptId(requestDepartment(normalized,actor.getId()));
            normalized.setRequestHash(hash);normalized.setState("PENDING");normalized.setVersion(1);normalized.setCreatedAt(clock.now());
            if(requests.insert(normalized)!=1 || normalized.getId()==null) throw new ServiceException("申请保存失败，请稍后重试");
            appendHistory(normalized.getId(),actor.getId(),null,"PENDING","提交申请");
            fillNames(Collections.singletonList(normalized));return normalized;
        });
    }

    public AttendanceRequest decide(Long id,DecisionInput input) {
        User actor=SessionUtils.getUser();
        if(input==null || input.getVersion()==null || input.getVersion()<1) invalid("请提供当前申请版本");
        String decision=enumValue(input.getDecision());
        if(!Arrays.asList("APPROVED","REJECTED").contains(decision)) invalid("审批结果无效");
        String comment=text(input.getComment(),500,false,"审批意见");
        AttendanceRequest found=require(id);requireReview(found);
        return tx.execute(status -> {
            lockUser(found.getUserId());AttendanceRequest row=requests.lock(id);
            if(row==null) conflict("申请已不存在");
            requireReview(row);
            if(!"PENDING".equals(row.getState())) {
                if(decision.equals(row.getState()) && actor.getId().equals(row.getDecisionBy())
                        && Objects.equals(comment,row.getDecisionComment()) && input.getVersion()==row.getVersion()-1) {
                    fillNames(Collections.singletonList(row));return row;
                }
                conflict("申请已处理或版本已变化，请刷新后查看");
            }
            if(!input.getVersion().equals(row.getVersion())) conflict("申请版本已变化，请刷新后重试");
            if("APPROVED".equals(decision)) {
                if("CORRECTION".equals(row.getKind())) {
                    attendance.validateCorrection(row.getUserId(),row.getBusinessDate(),row.getAction(),row.getCorrectionTime());
                    Records event=attendance.record(row.getUserId(),row.getAction(),row.getBusinessDate(),row.getCorrectionTime(),"CORRECTION","补卡审批",row.getId());
                    if(event==null || event.getId()==null) throw new ServiceException("补卡事件保存失败，请稍后重试");
                } else {
                    if(row.getStartDate().isBefore(attendance.eligibleFrom(row.getUserId()))) invalid("请假起日早于该账号纳入新考勤的日期，不能批准");
                    requireWorkingHalfDay(row);requireNoOverlap(row,row.getUserId(),row.getId());
                }
            }
            transition(row,decision,comment,actor.getId());
            fillNames(Collections.singletonList(row));return row;
        });
    }

    public AttendanceRequest cancel(Long id,CancelInput input) {
        User actor=SessionUtils.getUser();
        if(input==null || input.getVersion()==null || input.getVersion()<1) invalid("请提供当前申请版本");
        AttendanceRequest found=require(id);access.requireSelf(found.getUserId());StpUtil.checkPermission(permission(found.getKind(),"add"));
        return tx.execute(status -> {
            lockUser(actor.getId());AttendanceRequest row=requests.lock(id);
            if(row==null) conflict("申请已不存在");
            access.requireSelf(row.getUserId());StpUtil.checkPermission(permission(row.getKind(),"add"));
            if("CANCELLED".equals(row.getState()) && input.getVersion()==row.getVersion()-1) {
                fillNames(Collections.singletonList(row));return row;
            }
            if(!"PENDING".equals(row.getState()) || !input.getVersion().equals(row.getVersion())) conflict("仅可撤回当前版本的待审批申请");
            transition(row,"CANCELLED","申请人撤回",actor.getId());
            fillNames(Collections.singletonList(row));return row;
        });
    }

    private void transition(AttendanceRequest row,String state,String comment,Integer actorId) {
        int version=row.getVersion();LocalDateTime at=clock.now();
        int changed=requests.update(null,new UpdateWrapper<AttendanceRequest>().eq("id",row.getId()).eq("state","PENDING").eq("version",version)
                .set("state",state).set("version",version+1).set("decision_by",actorId).set("decision_at",at).set("decision_comment",comment));
        if(changed!=1) conflict("申请状态已变化，请刷新后查看");
        appendHistory(row.getId(),actorId,"PENDING",state,comment);
        row.setState(state);row.setVersion(version+1);row.setDecisionBy(actorId);row.setDecisionAt(at);row.setDecisionComment(comment);
    }

    private void appendHistory(Long requestId,Integer actorId,String before,String after,String comment) {
        AttendanceApproval entry=new AttendanceApproval();entry.setRequestId(requestId);entry.setActorId(actorId);
        entry.setFromState(before);entry.setToState(after);entry.setComment(comment);entry.setCreatedAt(clock.now());
        if(history.insert(entry)!=1) throw new ServiceException("状态历史保存失败，请稍后重试");
    }

    private void requireNoOverlap(AttendanceRequest row,Integer userId,Long excluded) {
        QueryWrapper<AttendanceRequest> query=new QueryWrapper<AttendanceRequest>().eq("user_id",userId).eq("kind","LEAVE")
                .in("state",Arrays.asList("PENDING","APPROVED")).le("start_date",row.getEndDate()).ge("end_date",row.getStartDate());
        if(excluded!=null) query.ne("id",excluded);
        long start=slot(row.getStartDate(),row.getStartHalf()),end=slot(row.getEndDate(),row.getEndHalf());
        for(AttendanceRequest old:requests.selectList(query)) {
            if(slot(old.getStartDate(),old.getStartHalf())<=end && slot(old.getEndDate(),old.getEndHalf())>=start)
                conflict("该半日范围已有待审批或已批准的请假申请");
        }
    }

    private void validateLeaveDates(AttendanceRequest row,Integer userId) {
        LocalDate first=attendance.eligibleFrom(userId),minimum=clock.today().minusDays(30);
        if(first.isAfter(minimum)) minimum=first;
        if(row.getStartDate().isBefore(minimum) || row.getEndDate().isAfter(clock.today().plusDays(366))) invalid("请假日期须在新考勤生效后、过去30天至未来366天内");
        if(ChronoUnit.DAYS.between(row.getStartDate(),row.getEndDate())>=31) invalid("单次请假最多跨31个自然日");
        requireWorkingHalfDay(row);
    }

    private double leaveDays(AttendanceRequest row) {
        long first=slot(row.getStartDate(),row.getStartHalf()),last=slot(row.getEndDate(),row.getEndHalf());int halves=0;
        for(LocalDate day=row.getStartDate();!day.isAfter(row.getEndDate());day=day.plusDays(1)) {
            if(!policies.workingDay(day)) continue;
            long morning=slot(day,"AM"),afternoon=slot(day,"PM");
            if(morning>=first && morning<=last) halves++;
            if(afternoon>=first && afternoon<=last) halves++;
        }
        return halves/2.0d;
    }
    private void requireWorkingHalfDay(AttendanceRequest row) {
        if(leaveDays(row)<=0) invalid("请假范围没有工作半日，请检查工作日安排");
    }

    private Integer requestDepartment(AttendanceRequest row,Integer userId) {
        if("CORRECTION".equals(row.getKind())) return knownDepartment(userId,row.getBusinessDate());
        if(!row.getStartDate().isBefore(clock.today())) return attendance.departmentOf(userId);
        // 过去日期不能从当前部门猜测调岗历史；整段旧半日事实必须有一致的已存归属。
        Integer department=null;
        LocalDate last=row.getEndDate().isBefore(clock.today())?row.getEndDate():clock.today().minusDays(1);
        for(LocalDate day=row.getStartDate();!day.isAfter(last);day=day.plusDays(1)) {
            Integer known=knownDepartment(userId,day);
            if(known==0 || (department!=null && !department.equals(known))) return 0;
            department=known;
        }
        return department==null?0:department;
    }
    private Integer knownDepartment(Integer userId,LocalDate date) {
        AttendanceDay day=days.selectOne(new QueryWrapper<AttendanceDay>().eq("user_id",userId).eq("business_date",date));
        return day==null || day.getDeptId()==null?0:day.getDeptId();
    }

    private AttendanceRequest normalize(CreateInput input) {
        if(input==null) {invalid("申请内容不能为空");return null;}
        AttendanceRequest row=new AttendanceRequest();String key=input.getRequestKey()==null?"":input.getRequestKey().trim();
        if(!key.matches("[A-Za-z0-9-]{32,64}")) invalid("请求编号应为32至64位字母、数字或横线");
        row.setRequestKey(key);row.setKind(enumValue(input.getKind()));row.setReason(text(input.getReason(),1000,true,"申请原因"));
        if("CORRECTION".equals(row.getKind())) {
            row.setBusinessDate(date(input.getBusinessDate()));row.setAction(enumValue(input.getAction()));
            if(!Arrays.asList("IN","OUT").contains(row.getAction())) invalid("补卡动作只能为签到或签退");
            try {row.setCorrectionTime(LocalDateTime.parse(input.getCorrectionTime()==null?"":input.getCorrectionTime().trim(),DATE_TIME));}
            catch(Exception error) {invalid("补卡时间格式应为yyyy-MM-dd HH:mm:ss");}
        } else if("LEAVE".equals(row.getKind())) {
            row.setStartDate(date(input.getStartDate()));row.setEndDate(date(input.getEndDate()));
            row.setStartHalf(enumValue(input.getStartHalf()));row.setEndHalf(enumValue(input.getEndHalf()));
            if(!Arrays.asList("AM","PM").contains(row.getStartHalf()) || !Arrays.asList("AM","PM").contains(row.getEndHalf())) invalid("请假半日只能为上午或下午");
            if(slot(row.getStartDate(),row.getStartHalf())>slot(row.getEndDate(),row.getEndHalf())) invalid("请假结束半日不能早于开始半日");
            row.setLeaveType(input.getLeaveType()==null?"":input.getLeaveType().trim());
            if(!LEAVE_TYPES.contains(row.getLeaveType())) invalid("请假类型无效");
        } else invalid("申请类型无效");
        return row;
    }

    private String fingerprint(AttendanceRequest row) {
        // 固定字段顺序及规范化值，服务端身份/部门/状态由业务补充，客户端不能影响它们。
        List<Object> payload=Arrays.asList(row.getKind(),row.getReason(),row.getBusinessDate()==null?null:row.getBusinessDate().toString(),row.getAction(),
                row.getCorrectionTime()==null?null:row.getCorrectionTime().format(DATE_TIME),row.getStartDate()==null?null:row.getStartDate().toString(),row.getStartHalf(),
                row.getEndDate()==null?null:row.getEndDate().toString(),row.getEndHalf(),row.getLeaveType());
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte item:digest) hex.append(String.format("%02x",item&255));return hex.toString();
        } catch(Exception error) {throw new IllegalStateException("无法计算申请请求指纹",error);}
    }

    private void requireRead(AttendanceRequest row) {
        StpUtil.checkPermission(permission(row.getKind(),"list"));
        User actor=SessionUtils.getUser();if(actor.getId().equals(row.getUserId())) return;
        requireReviewerRole(actor);requireScope(row,actor);
    }
    private void requireReview(AttendanceRequest row) {
        User actor=SessionUtils.getUser();requireReviewerRole(actor);
        if(actor.getId().equals(row.getUserId())) AccessPolicy.deny();
        StpUtil.checkPermission(permission(row.getKind(),"edit"));requireScope(row,actor);
    }
    private void requireScope(AttendanceRequest row,User actor) {
        if("ADMIN".equals(actor.getRole())) return;
        if(row.getDeptId()==null || !access.departmentIds().contains(row.getDeptId()) || !access.visibleUserIds().contains(row.getUserId())) AccessPolicy.deny();
    }
    private void requireReviewerRole(User actor) {
        if(!Arrays.asList("ADMIN","manager").contains(actor.getRole())) AccessPolicy.deny();
    }
    private List<String> readableKinds() {
        List<String> kinds=new ArrayList<>();
        if(StpUtil.hasPermission("records.list")) kinds.add("CORRECTION");
        if(StpUtil.hasPermission("leaveapply.list")) kinds.add("LEAVE");
        if(kinds.isEmpty()) AccessPolicy.deny();return kinds;
    }
    private String permission(String kind,String operation) {return ("CORRECTION".equals(kind)?"records":"leaveapply")+"."+operation;}
    private AttendanceRequest require(Long id) {
        if(id==null || id<1) invalid("申请编号无效");
        AttendanceRequest row=requests.selectById(id);if(row==null) throw new ServiceException("404","申请不存在");return row;
    }
    private void lockUser(Integer userId) {
        if(userMapper.lockActiveId(userId)==null) conflict("申请人员已停用，无法继续处理");
        SessionUtils.getUser(); // 等待锁期间账号及角色可能变化，每个写事务重新确认当前身份。
    }
    private void fillNames(List<AttendanceRequest> rows) {
        if(rows.isEmpty()) return;
        Set<Integer> ids=new HashSet<>();for(AttendanceRequest row:rows) ids.add(row.getUserId());
        Map<Integer,String> names=new HashMap<>();for(User user:users.listByIds(ids)) names.put(user.getId(),user.getName());
        for(AttendanceRequest row:rows) {
            row.setApplicantName(names.containsKey(row.getUserId())?names.get(row.getUserId()):"人员"+row.getUserId());
            if("LEAVE".equals(row.getKind())) row.setLeaveDays(leaveDays(row));
        }
    }
    private static LocalDate date(String value) {
        try {if(value==null || !value.trim().matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();return LocalDate.parse(value.trim());}
        catch(Exception error) {invalid("日期格式无效，应为yyyy-MM-dd");return null;}
    }
    private static long slot(LocalDate date,String half) {return date.toEpochDay()*2+("PM".equals(half)?1:0);}
    private static String enumValue(String value) {return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
    private static String text(String value,int limit,boolean required,String label) {
        String result=value==null?"":value.replace("\r\n","\n").replace('\r','\n').trim();
        if((required && result.isEmpty()) || result.length()>limit) invalid(label+"不能为空或超过"+limit+"字");return result;
    }
    private static void invalid(String message) {throw new ServiceException("422",message);}
    private static void conflict(String message) {throw new ServiceException("409",message);}
}
