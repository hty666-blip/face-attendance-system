package com.example.project.attendance;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.security.AccessPolicy;
import com.example.project.utils.SessionUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class AttendancePolicyService {
    @Resource private AttendancePolicyMapper policies;
    @Resource private AttendanceCalendarMapper calendars;
    @Resource private AttendanceDayMapper days;
    @Resource private AttendanceClock clock;
    @Resource private AccessPolicy access;

    public LocalDate activationDate() {
        AttendancePolicy first=policies.selectOne(new QueryWrapper<AttendancePolicy>().orderByAsc("effective_from").last("LIMIT 1"));
        if(first==null) throw new ServiceException("503","考勤新规则尚未初始化，请联系管理员执行数据迁移");
        return first.getEffectiveFrom();
    }
    public List<AttendancePolicy> list(){return policies.selectList(new QueryWrapper<AttendancePolicy>().orderByDesc("effective_from"));}
    public AttendancePolicy effective(LocalDate date) {
        if(date==null) throw invalid("业务日期无效");
        return policies.selectOne(new QueryWrapper<AttendancePolicy>().le("effective_from",date).orderByDesc("effective_from").last("LIMIT 1"));
    }
    public boolean workingDay(LocalDate date) {
        AttendanceCalendar override=calendars.selectById(date);
        return override==null?date.getDayOfWeek().getValue()<=5:Boolean.TRUE.equals(override.getWorkingDay());
    }
    /** 过去及当天保存不可改写快照，未来只返回预期规则。调用方显式提供可证实的部门。 */
    @Transactional
    public AttendanceDay dayPolicy(Integer userId,LocalDate date,Integer deptId) {
        AttendanceDay old=days.selectOne(new QueryWrapper<AttendanceDay>().eq("user_id",userId).eq("business_date",date));
        if(old!=null) return old;
        AttendancePolicy policy=effective(date);
        if(policy==null) return null;
        validateTimes(policy.getStartTime(),policy.getEndTime(),policy.getGraceMinutes());
        AttendanceDay row=new AttendanceDay();row.setUserId(userId);row.setBusinessDate(date);row.setDeptId(deptId==null?0:deptId);
        row.setPolicyId(policy.getId());row.setStartTime(policy.getStartTime());row.setEndTime(policy.getEndTime());
        row.setGraceMinutes(policy.getGraceMinutes());row.setWorkingDay(workingDay(date));row.setCreatedAt(clock.now());
        if(date.isAfter(clock.today())) return row;
        days.insertIfAbsent(row);
        // MySQL REPEATABLE READ 下使用当前读，才能看见并发 INSERT IGNORE 等待后已提交的行。
        AttendanceDay saved=days.lock(userId,date);
        if(saved==null) throw new ServiceException("409","日规则保存失败，请稍后重试");
        return saved;
    }
    @Transactional
    public AttendancePolicy append(LocalDate effectiveFrom,LocalTime start,LocalTime end,Integer grace) {
        authorizeConfiguration();
        if(effectiveFrom==null || !effectiveFrom.isAfter(clock.today())) throw invalid("新规则只能从明日或未来日期生效");
        validateTimes(start,end,grace);
        AttendancePolicy row=new AttendancePolicy();row.setEffectiveFrom(effectiveFrom);row.setStartTime(start);row.setEndTime(end);
        row.setGraceMinutes(grace);row.setCreatedBy(SessionUtils.getUser().getId());row.setCreatedAt(clock.now());
        try {if(policies.insert(row)!=1) throw new ServiceException("409","规则保存失败，请稍后重试");}
        catch(DuplicateKeyException error){throw new ServiceException("409","该生效日已有规则，请选择其他未来日期");}
        return row;
    }
    public Map<String,Object> calendar(String month) {
        YearMonth selected=parseMonth(month);List<Map<String,Object>> result=new ArrayList<>();
        Map<LocalDate,AttendanceCalendar> overrides=new HashMap<>();
        for(AttendanceCalendar row:calendars.selectList(new QueryWrapper<AttendanceCalendar>().ge("date",selected.atDay(1)).le("date",selected.atEndOfMonth())))
            overrides.put(row.getDate(),row);
        for(int number=1;number<=selected.lengthOfMonth();number++) {
            LocalDate date=selected.atDay(number);AttendanceCalendar override=overrides.get(date);
            Map<String,Object> item=new LinkedHashMap<>();item.put("date",date);item.put("workingDay",override==null?date.getDayOfWeek().getValue()<=5:override.getWorkingDay());
            item.put("overridden",override!=null);item.put("note",override==null?"":override.getNote());result.add(item);
        }
        Map<String,Object> response=new LinkedHashMap<>();response.put("month",selected.toString());response.put("days",result);return response;
    }
    @Transactional
    public AttendanceCalendar configureDay(LocalDate date,Boolean workingDay,String note) {
        authorizeConfiguration();
        if(date==null || !date.isAfter(clock.today())) throw invalid("只能调整明日或未来日期的工作日安排");
        if(workingDay==null) throw invalid("请选择工作日或休息日");
        if(note==null || note.trim().isEmpty() || note.length()>200) throw invalid("调整说明须为1至200字");
        AttendanceCalendar row=new AttendanceCalendar();row.setDate(date);row.setWorkingDay(workingDay);row.setNote(note.trim());
        row.setUpdatedBy(SessionUtils.getUser().getId());row.setUpdatedAt(clock.now());
        try {
            int changed=calendars.selectById(date)==null?calendars.insert(row):calendars.updateById(row);
            if(changed!=1) throw new ServiceException("409","日历保存失败，请稍后重试");
        } catch(DuplicateKeyException error){throw new ServiceException("409","日历已被其他操作更新，请刷新后重试");}
        return row;
    }
    private void authorizeConfiguration(){access.adminOnly();StpUtil.checkPermission("rules.edit");}
    public static void validateTimes(LocalTime start,LocalTime end,Integer grace) {
        if(start==null || end==null || start.getNano()!=0 || end.getNano()!=0 || !start.isBefore(LocalTime.NOON) || !end.isAfter(LocalTime.NOON))
            throw invalid("固定日班须在12:00前上班、12:00后下班");
        if(grace==null || grace<0 || grace>60) throw invalid("宽限须为0至60分钟");
    }
    public static YearMonth parseMonth(String value) {
        if(value==null || !value.matches("\\d{4}-\\d{2}")) throw invalid("月份格式须为YYYY-MM");
        try {YearMonth month=YearMonth.parse(value);if(month.getYear()<2000 || month.getYear()>2100) throw invalid("月份超出支持范围");return month;}
        catch(DateTimeParseException error){throw invalid("月份格式须为YYYY-MM");}
    }
    static ServiceException invalid(String message){return new ServiceException("422",message);}
}
