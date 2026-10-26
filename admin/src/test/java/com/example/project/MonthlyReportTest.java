package com.example.project;

import com.example.project.report.MonthlyReportService;
import com.example.project.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 使用合成账号直接调用后台入口，验证无会话读取、历史范围和唯一统计口径。 */
class MonthlyReportTest extends SecurityFixture {
    @Autowired MonthlyReportService reports;
    private static final LocalDate FRIDAY=LocalDate.of(2026,10,23);

    Map<String,Object> self() { return reports.build(4,"2026-10","SELF",null,false); }
    @SuppressWarnings("unchecked") Map<String,Object> day(Map<String,Object> report,LocalDate date) {
        return ((List<Map<String,Object>>)report.get("days")).stream().filter(row -> date.equals(row.get("businessDate"))).findFirst().orElseThrow(AssertionError::new);
    }
    void event(String action, LocalTime time, int department) {
        db.update("INSERT INTO records(user_id,business_date,action,occurred_at,source,dept_id,address,state_radio,create_time) VALUES(4,?,?,?,?,?,'私密地点','正常',?)",
                FRIDAY,action,FRIDAY.atTime(time),"FACE",department,NOW);
    }
    void snapshot(int department) {
        db.update("INSERT INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES(4,?,?,true,1,'08:00','18:00',0,?)",FRIDAY,department,NOW);
    }
    void denied(String code, Runnable call) { assertEquals(code,assertThrows(ServiceException.class,call::run).getCode()); }

    @Test void backgroundEntryNeedsNoSessionAndDoesNotFreezeDailyRules() {
        Map<String,Object> report=self();
        assertEquals(1,((List<?>)report.get("people")).size());assertEquals(31,((List<?>)report.get("days")).size());
        assertTrue(report.get("asOf").toString().endsWith("+08:00"));assertEquals(true,report.get("unsettled"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM attendance_day",Integer.class));
        assertEquals(reports.scopeSignature(4,"2026-10","SELF",null,false),report.get("scopeSignature"));
    }
    @Test void originalWorkbenchAndReportShareAllStatisticsAndHalfDayLeave() throws Exception {
        event("IN",LocalTime.of(8,0,1),1);event("OUT",LocalTime.of(18,0),1);
        db.update("INSERT INTO attendance_request(user_id,dept_id,request_key,request_hash,kind,start_date,start_half,end_date,end_half,leave_type,reason,state,version,created_at) VALUES(4,1,'leave','hash','LEAVE','2026-10-22','AM','2026-10-22','AM','事假','合成原因','APPROVED',1,?)",NOW);
        Map<String,Object> report=self();
        JsonNode original=body(mvc.perform(get("/attendance/month?month=2026-10").header("Authorization",employee)).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data").path("statistics");
        JsonNode reportStats=json.readTree(json.writeValueAsString(report.get("statistics")));
        original.fields().forEachRemaining(entry -> assertEquals(entry.getValue(),reportStats.path(entry.getKey()),entry.getKey()));
        assertEquals(0.5,reportStats.path("leaveDays").asDouble());assertEquals(1,reportStats.path("lateMinutes").asLong());
    }
    @Test void validatesScopeAndRechecksDatabasePermissionsForEveryExecution() {
        denied("422",() -> reports.build(4,"2026-1","SELF",null,false));
        denied("422",() -> reports.build(4,"2026-10","ALL",null,false));
        denied("422",() -> reports.build(4,"2026-10","SELF",1,false));
        denied("422",() -> reports.build(1,"2026-10","DEPARTMENT",null,false));
        denied("403",() -> reports.build(4,"2026-10","DEPARTMENT",1,false));
        denied("403",() -> reports.build(2,"2026-10","DEPARTMENT",2,false));
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id=(SELECT id FROM sys_permission WHERE auth='records.export')");
        self();denied("403",() -> reports.build(4,"2026-10","SELF",null,true));
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id=(SELECT id FROM sys_permission WHERE auth='records.list')");
        denied("403",this::self);
    }
    @Test void disabledAccountOrRoleCannotUsePreviouslyAuthorizedTask() {
        reports.scopeSignature(4,"2026-10","SELF",null,true);
        db.update("UPDATE sys_user SET deleted=id WHERE id=4");denied("401",this::self);
        db.update("UPDATE sys_user SET deleted=0 WHERE id=4");
        db.update("UPDATE sys_role SET deleted=id WHERE flag='employee'");denied("403",this::self);
    }
    @Test void departmentUsesOnlyCurrentActiveEmployeeProfiles() {
        Map<String,Object> report=reports.build(2,"2026-10","DEPARTMENT",1,false);
        assertEquals("部门A",report.get("departmentName"));
        JsonNode people=json.valueToTree(report.get("people"));assertEquals(1,people.size());assertEquals(4,people.get(0).path("userId").asInt());
        db.update("UPDATE sys_user SET deleted=id WHERE id=4");
        report=reports.build(1,"2026-10","DEPARTMENT",1,true);
        assertEquals(0,((List<?>)report.get("people")).size());assertEquals(0,((Number)((Map<?,?>)report.get("statistics")).get("peopleCount")).intValue());
    }
    @Test void administratorAlsoCannotMixOtherOrUnknownDepartmentHistoricalDays() {
        snapshot(1);event("IN",LocalTime.of(8,0),1);
        Map<String,Object> report=reports.build(1,"2026-10","DEPARTMENT",1,false);
        assertEquals("MISSING",day(report,FRIDAY).get("status"));assertEquals("RESTRICTED",day(report,FRIDAY.minusDays(1)).get("status"));
        event("OUT",LocalTime.of(18,0),2);
        Map<String,Object> restricted=day(reports.build(1,"2026-10","DEPARTMENT",1,false),FRIDAY);
        assertEquals("RESTRICTED",restricted.get("status"));assertEquals(new HashSet<>(Arrays.asList("userId","name","businessDate","status")),restricted.keySet());
        db.update("UPDATE records SET dept_id=0 WHERE action='OUT'");
        assertEquals("RESTRICTED",day(reports.build(1,"2026-10","DEPARTMENT",1,false),FRIDAY).get("status"));
    }
    @Test void managerOfTwoDepartmentsStillCannotIncludeMixedDayInEitherSelectedDepartment() {
        db.update("UPDATE dept SET user_id=2 WHERE id=2");snapshot(1);event("IN",LocalTime.of(8,0),2);
        assertEquals("RESTRICTED",day(reports.build(2,"2026-10","DEPARTMENT",1,false),FRIDAY).get("status"));
    }
    @Test void scopeSignatureChangesOnTransferAndRevokedDepartmentOwnershipDenies() {
        String before=reports.scopeSignature(2,"2026-10","DEPARTMENT",1,true);
        db.update("UPDATE employee SET dept_id=2 WHERE user_id=4");
        assertNotEquals(before,reports.scopeSignature(2,"2026-10","DEPARTMENT",1,true));
        db.update("UPDATE dept SET user_id=3 WHERE id=1");
        denied("403",() -> reports.scopeSignature(2,"2026-10","DEPARTMENT",1,true));
    }
    @Test void accessSignatureTracksVisibleDepartmentChangesButNotLaterFactValues() {
        snapshot(1);event("IN",LocalTime.of(8,0),1);
        Object before=reports.build(1,"2026-10","DEPARTMENT",1,false).get("accessSignature");
        db.update("UPDATE records SET occurred_at='2026-10-23 08:10:00' WHERE action='IN'");
        assertEquals(before,reports.build(1,"2026-10","DEPARTMENT",1,false).get("accessSignature"));
        db.update("UPDATE records SET dept_id=2 WHERE action='IN'");
        assertNotEquals(before,reports.build(1,"2026-10","DEPARTMENT",1,false).get("accessSignature"));
    }
    @Test void reportContainsOnlyExplicitlyWhitelistedPersonAndEventFields() {
        event("IN",LocalTime.of(8,0),1);Map<String,Object> report=self();
        JsonNode person=json.valueToTree(((List<?>)report.get("people")).get(0));
        assertEquals(new HashSet<>(Arrays.asList("userId","username","name","statistics")),fieldNames(person));
        JsonNode event=json.valueToTree(day(report,FRIDAY).get("checkIn"));
        assertEquals(new HashSet<>(Arrays.asList("action","occurredAt","createTime","source")),fieldNames(event));
        String serialized=json.valueToTree(report).toString();
        for(String forbidden:Arrays.asList("password","email","phone","address","avatar","face","_accessEvidence","私密地点","@test.invalid")) assertFalse(serialized.contains(forbidden),forbidden);
    }
    @Test void rejectsOversizedDepartmentsAndDuplicateEmployeeProfiles() {
        for(int id=6;id<=55;id++) {
            db.update("INSERT INTO sys_user(id,username,password,name,email,uid,role,deleted) VALUES(?,?,?,?,?,?,'employee',0)",id,"test"+id,HASH,"合成员工"+id,"report"+id+"@test.invalid","report-"+id);
            db.update("INSERT INTO employee(username,name,user_id,dept_id) VALUES(?,?,?,1)","test"+id,"合成员工"+id,id);
        }
        denied("422",() -> reports.scopeSignature(2,"2026-10","DEPARTMENT",1,false));
        db.update("DELETE FROM employee WHERE user_id>=6");
        db.update("INSERT INTO employee(username,name,user_id,dept_id) VALUES('u4','重复档案',4,2)");
        denied("422",() -> reports.scopeSignature(2,"2026-10","DEPARTMENT",1,false));
    }
    @Test void closedMonthIsSettledAndLegacyDaysRemainExplicit() {
        Map<String,Object> legacy=reports.build(4,"2026-08","SELF",null,false);
        assertEquals(false,legacy.get("unsettled"));assertEquals("LEGACY",day(legacy,LocalDate.of(2026,8,1)).get("status"));
        assertEquals(0,((Number)((Map<?,?>)legacy.get("statistics")).get("workingDays")).intValue());
    }
    Set<String> fieldNames(JsonNode node) { Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);return fields; }
}
