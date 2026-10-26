package com.example.project.report;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.attendance.AttendanceClock;
import com.example.project.attendance.AttendancePolicyService;
import com.example.project.attendance.AttendanceService;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.security.AccessPolicy;
import com.example.project.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.*;

/** 月报只负责范围和白名单组织；日结果与统计始终交给考勤业务计算。 */
@Service
public class MonthlyReportService {
    public static final String CALCULATION_VERSION = "fixed-day-v1-report-v1";
    private static final List<String> DAY_FIELDS = Arrays.asList("businessDate", "status", "workingDay", "policyId",
            "startTime", "endTime", "graceMinutes", "lateMinutes", "earlyMinutes", "missingIn", "missingOut", "leaveDays", "pendingCount");
    @Resource private AccessPolicy access;
    @Resource private AttendanceService attendance;
    @Resource private AttendanceClock clock;
    @Resource private IUserService users;
    @Resource private IEmployeeService employees;
    @Resource private IDeptService departments;

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public String scopeSignature(Integer actorId, String month, String scope, Integer deptId, boolean requireExport) {
        return resolve(actorId, month, scope, deptId, requireExport).signature;
    }

    /** 后台执行与 HTTP 预览使用同一入口，从数据库重载身份，整份报告使用同一个读取快照。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public Map<String, Object> build(Integer actorId, String month, String scope, Integer deptId, boolean requireExport) {
        Selection selected = resolve(actorId, month, scope, deptId, requireExport);
        LocalDateTime generatedAt=clock.now();
        String asOf = generatedAt.atOffset(ZoneOffset.ofHours(8)).toString();
        List<Map<String, Object>> people = new ArrayList<>(), daily = new ArrayList<>(), rawDays = new ArrayList<>();
        List<String> accessEvidence = new ArrayList<>();
        for (User user : selected.people) {
            Map<String, Object> personMonth = attendance.reportMonth(selected.actor, selected.month.toString(), user.getId(), selected.departmentId,generatedAt.toLocalDate());
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("userId", user.getId()); person.put("username", user.getUsername()); person.put("name", user.getName());
            person.put("statistics", personMonth.get("statistics")); people.add(person);
            for (Map<String, Object> day : (List<Map<String, Object>>) personMonth.get("days")) {
                rawDays.add(day);
                accessEvidence.add(user.getId() + "|" + day.get("businessDate") + "|" + day.get("_accessEvidence"));
                Map<String, Object> row = new LinkedHashMap<>(); row.put("userId", user.getId()); row.put("name", user.getName());
                for (String field : DAY_FIELDS) if (day.containsKey(field)) row.put(field, day.get(field));
                if (day.containsKey("checkIn")) row.put("checkIn", event((Records) day.get("checkIn")));
                if (day.containsKey("checkOut")) row.put("checkOut", event((Records) day.get("checkOut")));
                daily.add(row);
            }
        }
        Map<String, Object> stats = attendance.statistics(rawDays,generatedAt.toLocalDate()); stats.put("peopleCount", people.size());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("month", selected.month.toString()); report.put("scope", selected.scope); report.put("deptId", selected.departmentId);
        report.put("departmentName", selected.departmentName); report.put("asOf", asOf); report.put("calculationVersion", CALCULATION_VERSION);
        report.put("scopeSignature", selected.signature); report.put("accessSignature", digest(selected.signature + "\n" + String.join("\n", accessEvidence)));
        report.put("statistics", stats); report.put("people", people); report.put("days", daily);
        report.put("unsettled", !selected.month.atEndOfMonth().isBefore(generatedAt.toLocalDate()));
        return report;
    }

    private Map<String, Object> event(Records record) {
        if (record == null) return null;
        Map<String, Object> event = new LinkedHashMap<>(); event.put("action", record.getAction());
        event.put("occurredAt", record.getOccurredAt()); event.put("createTime", record.getCreateTime()); event.put("source", record.getSource());
        return event;
    }

    private Selection resolve(Integer actorId, String month, String scope, Integer deptId, boolean requireExport) {
        YearMonth selectedMonth = AttendancePolicyService.parseMonth(month);
        if (!Arrays.asList("SELF", "DEPARTMENT").contains(scope)) throw invalid("报表范围须为SELF或DEPARTMENT");
        User actor = requireExport ? access.requireActiveActor(actorId, "records.list", "records.export")
                : access.requireActiveActor(actorId, "records.list");
        Selection result = new Selection(); result.actor = actor; result.month = selectedMonth; result.scope = scope;
        if ("SELF".equals(scope)) {
            if (deptId != null) throw invalid("本人月报不接受部门参数");
            result.people.add(actor);
        } else {
            if (deptId == null || deptId <= 0) throw invalid("请明确选择一个部门");
            if (!access.isAdmin(actor) && (!"manager".equals(actor.getRole()) || !access.departmentIds(actor).contains(deptId))) AccessPolicy.deny();
            Dept department = departments.getById(deptId);
            if (department == null) throw invalid("所选部门不存在");
            result.departmentId = deptId; result.departmentName = department.getName();
            Set<Integer> added = new HashSet<>();
            for (Employee employee : employees.list(new QueryWrapper<Employee>().eq("dept_id", deptId).orderByAsc("user_id"))) {
                User user = employee.getUserId() == null ? null : users.getById(employee.getUserId());
                if (user == null || !"employee".equals(user.getRole())) continue;
                if (!added.add(user.getId()) || employees.count(new QueryWrapper<Employee>().eq("user_id", user.getId())) != 1)
                    throw invalid("人员存在重复部门档案，请联系管理员处理");
                result.people.add(user);
                if (result.people.size() > 50) throw invalid("部门当前在职员工超过50人，请缩小部门范围后重试");
            }
        }
        result.people.sort(Comparator.comparing(User::getId));
        StringBuilder signature = new StringBuilder(selectedMonth.toString()).append('|').append(scope).append('|')
                .append(deptId).append('|').append(actor.getId()).append('|').append(actor.getRole());
        for (User user : result.people) signature.append('|').append(user.getId()).append(':').append(attendance.departmentOf(user.getId()));
        result.signature = digest(signature.toString()); return result;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ServiceException invalid(String message) { return new ServiceException("422", message); }
    private static class Selection {
        User actor; YearMonth month; String scope; Integer departmentId; String departmentName = ""; String signature;
        List<User> people = new ArrayList<>();
    }
}
