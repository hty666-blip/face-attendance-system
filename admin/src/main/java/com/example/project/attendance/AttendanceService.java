package com.example.project.attendance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.security.AccessPolicy;
import com.example.project.service.*;
import com.example.project.utils.SessionUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.*;
import java.util.*;

/** 日结果从不可变日规则、事件、已批假即时计算，不持久化易过期的汇总。 */
@Service
public class AttendanceService {
    @Resource private AttendanceClock clock;
    @Resource private AttendancePolicyService policies;
    @Resource private AttendanceDayMapper days;
    @Resource private AttendanceRequestMapper requests;
    @Resource private RecordsMapper events;
    @Resource private UserMapper users;
    @Resource private IEmployeeService employees;
    @Resource private IManagerService managers;
    @Resource private IDeptService departments;
    @Resource private IRecordsService recordService;
    @Resource private AccessPolicy access;

    public Integer departmentOf(Integer userId) {
        if(userId==null) return 0;
        List<Employee> employee=employees.list(new QueryWrapper<Employee>().eq("user_id",userId).last("LIMIT 2"));
        if(employee.size()>1) throw invalid("人员存在重复部门档案，请联系管理员处理");
        if(!employee.isEmpty()) return employee.get(0).getDeptId()==null?0:employee.get(0).getDeptId();
        List<Manager> manager=managers.list(new QueryWrapper<Manager>().eq("user_id",userId).last("LIMIT 2"));
        if(manager.size()>1) throw invalid("主管存在重复部门档案，请联系管理员处理");
        if(!manager.isEmpty()) return manager.get(0).getDeptId()==null?0:manager.get(0).getDeptId();
        List<Dept> owned=departments.list(new QueryWrapper<Dept>().eq("user_id",userId).last("LIMIT 2"));
        return owned.size()==1?owned.get(0).getId():0;
    }
    private void requireAction(String action) {
        if(!"IN".equals(action) && !"OUT".equals(action)) throw invalid("打卡动作须为IN或OUT");
    }
    public LocalDate eligibleFrom(Integer userId) {
        User user=users.selectById(userId);if(user==null) throw invalid("用户不存在或已停用");
        LocalDate activation=policies.activationDate();
        return user.getCreateTime()!=null && user.getCreateTime().toLocalDate().isAfter(activation)?user.getCreateTime().toLocalDate():activation;
    }
    private List<Records> events(Integer userId,LocalDate date) {
        return events.selectList(new QueryWrapper<Records>().eq("user_id",userId).eq("business_date",date));
    }
    private List<AttendanceRequest> facts(Integer userId,LocalDate date) {
        return requests.selectList(new QueryWrapper<AttendanceRequest>().eq("user_id",userId)
                .in("state",Arrays.asList("PENDING","APPROVED"))
                .and(q -> q.eq("kind","CORRECTION").eq("business_date",date)
                        .or(l -> l.eq("kind","LEAVE").le("start_date",date).ge("end_date",date))));
    }
    private AttendanceDay snapshot(Integer userId,LocalDate date,List<Records> records,List<AttendanceRequest> requests) {
        int historicalDepartment=0;
        if(!date.isBefore(clock.today())) historicalDepartment=departmentOf(userId);
        else if(!records.isEmpty()) historicalDepartment=records.get(0).getDeptId()==null?0:records.get(0).getDeptId();
        else if(!requests.isEmpty()) historicalDepartment=requests.get(0).getDeptId()==null?0:requests.get(0).getDeptId();
        // 无历史事实时保留0未知，不能用调岗后的部门猜测旧日归属。
        return policies.dayPolicy(userId,date,historicalDepartment);
    }
    private boolean leaveHalf(List<AttendanceRequest> facts,LocalDate date,String half) {
        long target=halfIndex(date,half);
        for(AttendanceRequest fact:facts) {
            if("LEAVE".equals(fact.getKind()) && "APPROVED".equals(fact.getState()) &&
                    target>=halfIndex(fact.getStartDate(),fact.getStartHalf()) && target<=halfIndex(fact.getEndDate(),fact.getEndHalf())) return true;
        }
        return false;
    }
    private long halfIndex(LocalDate date,String half) {
        if(date==null || (!"AM".equals(half) && !"PM".equals(half))) throw invalid("请假日期或半日数据无效，请联系管理员");
        return date.toEpochDay()*2+("PM".equals(half)?1:0);
    }
    private boolean actionExists(List<Records> records,String action) {
        for(Records row:records) if(action.equals(row.getAction())) return true;return false;
    }
    private boolean exempt(List<AttendanceRequest> facts,LocalDate date,String action) {
        return leaveHalf(facts,date,"IN".equals(action)?"AM":"PM");
    }
    private void validateWindow(AttendanceDay day,String action,LocalDateTime time) {
        if(time==null || time.getNano()!=0 || !day.getBusinessDate().equals(time.toLocalDate())) throw invalid("发生时间必须属于所选业务日并精确到秒");
        LocalDateTime from="IN".equals(action)?day.getBusinessDate().atTime(day.getStartTime()).minusHours(2):day.getBusinessDate().atTime(day.getStartTime());
        LocalDateTime to="IN".equals(action)?day.getBusinessDate().atTime(day.getEndTime()):day.getBusinessDate().atTime(day.getEndTime()).plusHours(4);
        if(time.isBefore(from) || time.isAfter(to)) throw invalid("发生时间不在该动作允许窗口内");
    }
    private AttendanceDay validate(Integer userId,LocalDate date,String action,LocalDateTime occurredAt,boolean correction) {
        requireAction(action);
        if(userId==null || date==null) throw invalid("人员或业务日期无效");
        if(date.isBefore(eligibleFrom(userId))) throw invalid("该日期尚未纳入该账号的新考勤口径，请查阅旧记录");
        if(correction && (!date.isBefore(clock.today()) || date.isBefore(clock.today().minusDays(30))))
            throw invalid("补卡仅支持过去30天内的业务日，不含今天");
        if(!correction && !date.equals(clock.today())) throw new ServiceException("409","打卡会话已跨业务日，请重新打开窗口");
        List<Records> records=events(userId,date);List<AttendanceRequest> leave=facts(userId,date);
        AttendanceDay day=snapshot(userId,date,records,leave);
        if(day==null || !Boolean.TRUE.equals(day.getWorkingDay())) throw invalid("该日为休息日，无需打卡或补卡");
        if(actionExists(records,action)) throw new ServiceException("409","该日该动作已有有效记录，不能重复打卡或补卡");
        if(exempt(leave,date,action)) throw invalid("该动作已由批准的请假豁免，无需打卡或补卡");
        validateWindow(day,action,occurredAt);return day;
    }
    public void validateCorrection(Integer userId,LocalDate date,String action,LocalDateTime occurredAt) {
        validate(userId,date,action,occurredAt,true);
    }
    /** 只由人脸验证/补卡审批调用；与审批的用户行锁及事务共用唯一性边界。 */
    @Transactional
    public Records record(Integer userId,String action,LocalDate date,LocalDateTime occurredAt,String source,String address,Long requestId) {
        if(userId==null || users.lockActiveId(userId)==null) throw new ServiceException("401","账号已停用，不能保存考勤");
        boolean correction="CORRECTION".equals(source);
        if(!correction && !"FACE".equals(source)) throw invalid("考勤来源无效");
        if(correction && (requestId==null || requestId<=0)) throw invalid("补卡必须关联已审核的申请");
        if(!correction && requestId!=null) throw invalid("人脸事件不能关联补卡申请");
        if(address==null || address.trim().isEmpty() || address.length()>200) throw invalid("打卡地点须为1至200字");
        if(!correction) occurredAt=clock.now().withNano(0); // 人脸时间由服务端决定，复核推理完成时的窗口。
        AttendanceDay policy=validate(userId,date,action,occurredAt,correction);
        Integer eventDepartment=departmentOf(userId);
        if(correction) {
            AttendanceRequest request=requests.selectById(requestId);
            if(request==null || !"CORRECTION".equals(request.getKind()) || !userId.equals(request.getUserId()) ||
                    !date.equals(request.getBusinessDate()) || !action.equals(request.getAction()) ||
                    !occurredAt.equals(request.getCorrectionTime()) || !Arrays.asList("PENDING","APPROVED").contains(request.getState()))
                throw new ServiceException("409","补卡申请事实已变化，请刷新后重试");
            if(policy.getDeptId()!=null && policy.getDeptId()>0 && !policy.getDeptId().equals(request.getDeptId()))
                throw new ServiceException("409","补卡申请与原日快照部门冲突，请联系管理员复核");
            eventDepartment=request.getDeptId()==null?0:request.getDeptId();
        }
        Records row=new Records();row.setUserId(userId);row.setBusinessDate(date);row.setAction(action);row.setOccurredAt(occurredAt);
        row.setSource(source);row.setDeptId(eventDepartment);row.setRequestId(requestId);row.setAddress(address.trim());row.setCreateTime(clock.now());
        long abnormal="IN".equals(action)?lateMinutes(policy,occurredAt):earlyMinutes(policy,occurredAt);
        row.setStateRadio(abnormal==0?"正常":"IN".equals(action)?"迟到":"早退");
        try {if(!recordService.save(row) || row.getId()==null) throw new ServiceException("考勤保存失败，请稍后重试");}
        catch(DuplicateKeyException error){throw new ServiceException("409","该日该动作已有记录，不能重复保存");}
        return row;
    }
    public String chooseAction(Integer userId,String requested) {
        LocalDateTime time=clock.now().withNano(0);
        if(requested!=null) {
            validate(userId,time.toLocalDate(),requested,time,false);return requested;
        }
        ServiceException first=null;
        for(String action:Arrays.asList("IN","OUT")) {
            try {validate(userId,time.toLocalDate(),action,time,false);return action;}
            catch(ServiceException error){if(first==null) first=error;}
        }
        throw first==null?invalid("当前没有可用打卡动作"):first;
    }
    public Map<String,Object> today() {
        Integer userId=SessionUtils.getUser().getId();LocalDate date=clock.today();Map<String,Object> result=new LinkedHashMap<>();
        result.put("businessDate",date);result.put("serverTime",clock.now());result.put("day",day(userId,date));
        List<Map<String,Object>> actions=new ArrayList<>();
        for(String action:Arrays.asList("IN","OUT")) {
            Map<String,Object> item=new LinkedHashMap<>();item.put("action",action);item.put("label","IN".equals(action)?"签到":"签退");
            try {chooseAction(userId,action);item.put("allowed",true);item.put("reason","");}
            catch(ServiceException error){item.put("allowed",false);item.put("reason",error.getMessage());}
            actions.add(item);
        }
        result.put("actions",actions);return result;
    }
    public Map<String,Object> day(Integer userId,LocalDate date) {
        access.requireUser(userId);
        List<Records> records=events(userId,date);List<AttendanceRequest> facts=facts(userId,date);
        AttendanceDay policy=date.isBefore(eligibleFrom(userId))?null:snapshot(userId,date,records,facts);
        Map<String,Object> result=new LinkedHashMap<>();result.put("businessDate",date);
        if(!visibleDay(userId,date,policy,records,facts)) {result.put("status","RESTRICTED");return result;}
        Records in=null,out=null;for(Records row:records){if("IN".equals(row.getAction())) in=row;if("OUT".equals(row.getAction())) out=row;}
        result.put("workingDay",policy!=null && Boolean.TRUE.equals(policy.getWorkingDay()));result.put("policyId",policy==null?null:policy.getPolicyId());
        result.put("startTime",policy==null?null:policy.getStartTime());result.put("endTime",policy==null?null:policy.getEndTime());
        result.put("graceMinutes",policy==null?null:policy.getGraceMinutes());result.put("checkIn",policy==null?null:in);result.put("checkOut",policy==null?null:out);
        boolean working=policy!=null && Boolean.TRUE.equals(policy.getWorkingDay());
        boolean am=working && leaveHalf(facts,date,"AM"),pm=working && leaveHalf(facts,date,"PM");
        long late=working && !am && in!=null?lateMinutes(policy,in.getOccurredAt()):0,early=working && !pm && out!=null?earlyMinutes(policy,out.getOccurredAt()):0;
        boolean missingIn=working && date.isBefore(clock.today()) && !am && in==null,missingOut=working && date.isBefore(clock.today()) && !pm && out==null;
        result.put("lateMinutes",late);result.put("earlyMinutes",early);result.put("missingIn",missingIn);result.put("missingOut",missingOut);
        result.put("leaveDays",(am?0.5:0)+(pm?0.5:0));result.put("pendingCount",facts.stream().filter(row -> "PENDING".equals(row.getState())).count());
        String status;
        if(policy==null) status="LEGACY";
        else if(!working) status="REST";
        else if(date.isAfter(clock.today())) status="UPCOMING";
        else if(am && pm) status="LEAVE";
        else if(missingIn || missingOut) status="MISSING";
        else if(date.equals(clock.today()) && ((!am && in==null) || (!pm && out==null))) status="IN_PROGRESS";
        else if(late>0 && early>0) status="LATE_AND_EARLY";
        else if(late>0) status="LATE";
        else if(early>0) status="EARLY";
        else status="NORMAL";
        result.put("status",status);return result;
    }
    private boolean visibleDay(Integer userId,LocalDate date,AttendanceDay day,List<Records> records,List<AttendanceRequest> facts) {
        User actor=SessionUtils.getUser();if("ADMIN".equals(actor.getRole()) || userId.equals(actor.getId())) return true;
        if(!"manager".equals(actor.getRole()) || day==null) return false;
        Set<Integer> known=new HashSet<>();if(day.getDeptId()!=null && day.getDeptId()>0) known.add(day.getDeptId());
        for(Records event:records) {if(event.getDeptId()==null || event.getDeptId()==0) return false;known.add(event.getDeptId());}
        for(AttendanceRequest request:facts) {if(request.getDeptId()==null || request.getDeptId()==0) return false;known.add(request.getDeptId());}
        if(known.isEmpty()) return false;return access.departmentIds().containsAll(known);
    }
    public Map<String,Object> month(String month,Integer requestedUser) {
        YearMonth selected=AttendancePolicyService.parseMonth(month);Integer target=requestedUser==null?SessionUtils.getUser().getId():requestedUser;
        access.requireUser(target);
        if(users.selectById(target)==null) throw invalid("用户不存在或已停用");
        List<Map<String,Object>> result=new ArrayList<>();Map<String,Object> stats=new LinkedHashMap<>();
        int working=0,elapsed=0,normal=0,lateDays=0,earlyDays=0,missing=0,restricted=0;double leave=0;long late=0,early=0;
        for(int number=1;number<=selected.lengthOfMonth();number++) {
            Map<String,Object> item=day(target,selected.atDay(number));result.add(item);
            if("RESTRICTED".equals(item.get("status"))) {restricted++;continue;}
            if(Boolean.TRUE.equals(item.get("workingDay"))) {working++;if(!selected.atDay(number).isAfter(clock.today())) elapsed++;}
            if("NORMAL".equals(item.get("status"))) normal++;
            long dayLate=((Number)item.get("lateMinutes")).longValue(),dayEarly=((Number)item.get("earlyMinutes")).longValue();
            if(dayLate>0) lateDays++;if(dayEarly>0) earlyDays++;if("MISSING".equals(item.get("status"))) missing++;
            late+=dayLate;early+=dayEarly;leave+=((Number)item.get("leaveDays")).doubleValue();
        }
        stats.put("workingDays",working);stats.put("elapsedWorkingDays",elapsed);stats.put("normalDays",normal);stats.put("lateDays",lateDays);stats.put("earlyDays",earlyDays);
        stats.put("missingDays",missing);stats.put("leaveDays",leave);stats.put("lateMinutes",late);stats.put("earlyMinutes",early);stats.put("restrictedDays",restricted);
        Map<String,Object> response=new LinkedHashMap<>();response.put("month",selected.toString());response.put("userId",target);response.put("days",result);response.put("statistics",stats);return response;
    }
    private long lateMinutes(AttendanceDay day,LocalDateTime occurrence) {
        if(occurrence==null) return 0;return excessMinutes(day.getBusinessDate().atTime(day.getStartTime()).plusMinutes(day.getGraceMinutes()),occurrence);
    }
    private long earlyMinutes(AttendanceDay day,LocalDateTime occurrence) {
        if(occurrence==null) return 0;return excessMinutes(occurrence,day.getBusinessDate().atTime(day.getEndTime()).minusMinutes(day.getGraceMinutes()));
    }
    private long excessMinutes(LocalDateTime from,LocalDateTime to) {
        if(!to.isAfter(from)) return 0;Duration difference=Duration.between(from,to);
        return (difference.getSeconds()+59+(difference.getNano()>0?1:0))/60;
    }
    private ServiceException invalid(String message){return new ServiceException("422",message);}
}
