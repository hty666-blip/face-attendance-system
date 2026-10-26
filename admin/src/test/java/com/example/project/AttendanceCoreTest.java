package com.example.project;

import com.example.project.attendance.*;
import com.example.project.entity.Records;
import com.example.project.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证真实事件、日规则快照、半日豁免、历史范围与两种写入入口共用的业务约束。 */
class AttendanceCoreTest extends SecurityFixture {
    @Autowired AttendanceService attendance;
    @Autowired AttendancePolicyService policies;
    private static final LocalDate FRIDAY=LocalDate.of(2026,10,23);

    void at(LocalDateTime time) {
        doReturn(time).when(attendanceClock).now();doReturn(time.toLocalDate()).when(attendanceClock).today();
    }
    JsonNode today(String token) throws Exception {
        return body(mvc.perform(get("/attendance/today").header("Authorization",token)).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data");
    }
    JsonNode day(LocalDate date,String token,int user) throws Exception {
        JsonNode response=body(mvc.perform(get("/attendance/month?month="+date.toString().substring(0,7)+"&userId="+user).header("Authorization",token))
                .andExpect(jsonPath("$.code").value("200")).andReturn()).path("data").path("days");
        for(JsonNode item:response) if(date.toString().equals(item.path("businessDate").asText())) return item;
        fail("missing day: "+date);return null;
    }
    void event(int user,LocalDate date,String action,LocalTime time,int department) {
        db.update("INSERT INTO records(user_id,business_date,action,occurred_at,source,dept_id,address,state_radio,create_time) VALUES(?,?,?,?,?,?,?,'正常',?)",
                user,date,action,date.atTime(time),"FACE",department,"测试地点",NOW);
    }
    long correction(LocalDate date,String action,LocalTime time,int department) {
        db.update("INSERT INTO attendance_request(user_id,dept_id,request_key,request_hash,kind,business_date,action,correction_time,reason,state,version,created_at) VALUES(4,?,?,?,'CORRECTION',?,?,?,'测试原因','PENDING',1,?)",
                department,UUID.randomUUID().toString(),"hash",date,action,date.atTime(time),NOW);
        return db.queryForObject("SELECT MAX(id) FROM attendance_request",Long.class);
    }
    void leave(LocalDate date,String startHalf,String endHalf,String state) {
        db.update("INSERT INTO attendance_request(user_id,dept_id,request_key,request_hash,kind,start_date,start_half,end_date,end_half,leave_type,reason,state,version,created_at) VALUES(4,1,?,?,'LEAVE',?,?,?,?,'事假','测试原因',?,1,?)",
                UUID.randomUUID().toString(),"hash",date,startHalf,date,endHalf,state,NOW);
    }
    void enroll() throws Exception {
        mvc.perform(post("/face/enroll").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":4,\"images\":[\"a\",\"b\",\"c\"]}"))
                .andExpect(jsonPath("$.code").value("200"));
    }
    String challenge(String action) throws Exception {
        return body(mvc.perform(post("/face/challenge").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\""+action+"\"}")).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data").path("challengeId").asText();
    }
    JsonNode verifyFace(String id) throws Exception {
        return body(mvc.perform(post("/faceVerify?address=office").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\""+id+"\",\"imageBase\":\"test-private-image\"}")).andReturn());
    }
    @Test void todayNeverMarksAnUnfinishedDayAsMissing() throws Exception {
        at(NOW.withHour(21));JsonNode result=today(employee).path("day");
        assertEquals("IN_PROGRESS",result.path("status").asText());assertFalse(result.path("missingIn").asBoolean());assertFalse(result.path("missingOut").asBoolean());
        assertEquals("MISSING",day(FRIDAY,employee,4).path("status").asText());
    }
    @Test void fractionalClockDoesNotDisableFaceActions() throws Exception {
        at(NOW.withNano(123000000));JsonNode result=today(employee);
        assertTrue(result.path("actions").get(0).path("allowed").asBoolean());
        enroll();assertFalse(challenge("IN").isEmpty());
    }
    @Test void dateAndTimeContractsAreJsonStringsAcrossAttendanceEndpoints() throws Exception {
        JsonNode current=today(employee);
        assertTrue(current.path("businessDate").isTextual());assertTrue(current.path("serverTime").isTextual());
        assertTrue(current.path("day").path("startTime").isTextual());
        assertTrue(day(FRIDAY,employee,4).path("businessDate").isTextual());
        JsonNode versions=body(mvc.perform(get("/attendance/policies").header("Authorization",admin)).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data");
        assertTrue(versions.get(0).path("effectiveFrom").isTextual());assertTrue(versions.get(0).path("startTime").isTextual());
        JsonNode calendar=body(mvc.perform(get("/attendance/calendar?month=2026-10").header("Authorization",employee)).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data");
        assertTrue(calendar.path("days").get(0).path("date").isTextual());
    }
    @Test void lateAndEarlyUseGraceAndCeilingOfExcessMinutes() throws Exception {
        db.update("UPDATE attendance_policy SET grace_minutes=5 WHERE id=1");
        event(4,FRIDAY,"IN",LocalTime.of(8,5,1),1);event(4,FRIDAY,"OUT",LocalTime.of(17,54,59),1);
        JsonNode result=day(FRIDAY,employee,4);
        assertEquals(1,result.path("lateMinutes").asInt());assertEquals(1,result.path("earlyMinutes").asInt());
        assertEquals("LATE_AND_EARLY",result.path("status").asText());assertEquals("FACE",result.path("checkIn").path("source").asText());
    }
    @Test void afternoonLeaveAndMorningLeaveExemptTheirOwnActions() throws Exception {
        leave(FRIDAY,"AM","AM","APPROVED");
        JsonNode result=day(FRIDAY,employee,4);assertFalse(result.path("missingIn").asBoolean());assertTrue(result.path("missingOut").asBoolean());
        assertEquals(0.5,result.path("leaveDays").asDouble());
        assertEquals("422",assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.atTime(8,0))).getCode());
        attendance.validateCorrection(4,FRIDAY,"OUT",FRIDAY.atTime(18,0));
        leave(NOW.toLocalDate(),"PM","PM","APPROVED");JsonNode current=today(employee);
        assertTrue(current.path("actions").get(0).path("allowed").asBoolean());assertFalse(current.path("actions").get(1).path("allowed").asBoolean());
    }
    @Test void fullLeaveAndPendingLeaveHaveDifferentDailyEffects() throws Exception {
        leave(FRIDAY,"AM","PM","APPROVED");JsonNode past=day(FRIDAY,employee,4);
        assertEquals("LEAVE",past.path("status").asText());assertEquals(1,past.path("leaveDays").asDouble());
        assertFalse(past.path("missingIn").asBoolean());assertFalse(past.path("missingOut").asBoolean());
        leave(NOW.toLocalDate(),"AM","PM","PENDING");JsonNode current=today(employee);
        assertEquals(0,current.path("day").path("leaveDays").asDouble());assertEquals(1,current.path("day").path("pendingCount").asInt());
        assertTrue(current.path("actions").get(0).path("allowed").asBoolean());
    }
    @Test void weekendAndFutureCalendarPreviewDoNotFreezeFutureRules() throws Exception {
        assertEquals("REST",day(LocalDate.of(2026,10,24),employee,4).path("status").asText());
        String payload="{\"date\":\"2026-10-31\",\"workingDay\":true,\"note\":\"调休工作日\"}";
        mvc.perform(post("/attendance/calendar").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(jsonPath("$.code").value("200"));
        assertEquals("UPCOMING",day(LocalDate.of(2026,10,31),employee,4).path("status").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM attendance_day WHERE business_date>'2026-10-26'",Integer.class));
        mvc.perform(post("/attendance/calendar").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(payload.replace("true","false"))).andExpect(jsonPath("$.code").value("200"));
        assertEquals("REST",day(LocalDate.of(2026,10,31),employee,4).path("status").asText());
    }
    @Test void futurePolicyCannotChangeFrozenDayAndPastConfigurationIsRejected() throws Exception {
        today(employee);
        mvc.perform(post("/attendance/policies").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"effectiveFrom\":\"2026-10-27\",\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"graceMinutes\":5}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(1,today(employee).path("day").path("policyId").asInt());
        assertNotEquals(1,day(LocalDate.of(2026,10,27),employee,4).path("policyId").asInt());
        for(String path:Arrays.asList("policies","calendar")) {
            String payload=path.equals("policies")?"{\"effectiveFrom\":\"2026-10-26\",\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"graceMinutes\":0}":"{\"date\":\"2026-10-26\",\"workingDay\":false,\"note\":\"修改今天\"}";
            mvc.perform(post("/attendance/"+path).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(jsonPath("$.code").value("422"));
            mvc.perform(post("/attendance/"+path).header("Authorization",employee).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(jsonPath("$.code").value("403"));
        }
    }
    @Test void historicalDataBeforeActivationOrAccountCreationIsNotGuessed() throws Exception {
        assertEquals("LEGACY",day(LocalDate.of(2026,8,31),employee,4).path("status").asText());
        db.update("UPDATE sys_user SET create_time='2026-10-25 10:00:00' WHERE id=4");
        JsonNode beforeJoin=day(FRIDAY,employee,4);assertEquals("LEGACY",beforeJoin.path("status").asText());assertFalse(beforeJoin.path("missingIn").asBoolean());
        assertEquals("422",assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.atTime(8,0))).getCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM attendance_day WHERE user_id=4 AND business_date<'2026-10-25'",Integer.class));
    }
    @Test void correctionWindowIsPastThirtyDaysWorkingDayAndSameDayTime() {
        attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.atTime(6,0));
        attendance.validateCorrection(4,FRIDAY,"OUT",FRIDAY.atTime(22,0));
        for(LocalDate date:Arrays.asList(NOW.toLocalDate(),NOW.toLocalDate().plusDays(1),NOW.toLocalDate().minusDays(31),LocalDate.of(2026,10,24)))
            assertEquals("422",assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,date,"IN",date.atTime(8,0))).getCode());
        assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.atTime(5,59,59)));
        assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"OUT",FRIDAY.atTime(22,0,1)));
        assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.minusDays(1).atTime(8,0)));
    }
    @Test void correctionHasSeparateOccurrenceAndWriteTimeAndNeverOverwritesEvents() {
        policies.dayPolicy(4,FRIDAY,1);long request=correction(FRIDAY,"IN",LocalTime.of(8,0),1);
        Records row=attendance.record(4,"IN",FRIDAY,FRIDAY.atTime(8,0),"CORRECTION","补卡审批",request);
        assertEquals(FRIDAY.atTime(8,0),row.getOccurredAt());assertEquals(NOW,row.getCreateTime());assertEquals("CORRECTION",row.getSource());assertEquals(1,row.getDeptId());
        assertEquals("409",assertThrows(ServiceException.class,() -> attendance.validateCorrection(4,FRIDAY,"IN",FRIDAY.atTime(8,0))).getCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM records WHERE business_date=? AND user_id=4",Integer.class,FRIDAY));
    }
    @Test void unknownDepartmentSnapshotIsNotRewrittenByCorrection() {
        policies.dayPolicy(4,FRIDAY,0);long request=correction(FRIDAY,"IN",LocalTime.of(8,0),0);
        Records row=attendance.record(4,"IN",FRIDAY,FRIDAY.atTime(8,0),"CORRECTION","补卡审批",request);
        assertEquals(0,row.getDeptId());assertEquals(0,db.queryForObject("SELECT dept_id FROM attendance_day WHERE user_id=4 AND business_date=?",Integer.class,FRIDAY));
    }
    @Test void conflictingCorrectionDepartmentIsRejected() {
        policies.dayPolicy(4,FRIDAY,1);long request=correction(FRIDAY,"IN",LocalTime.of(8,0),2);
        assertEquals("409",assertThrows(ServiceException.class,() -> attendance.record(4,"IN",FRIDAY,FRIDAY.atTime(8,0),"CORRECTION","补卡审批",request)).getCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM records WHERE request_id=?",Integer.class,request));
    }
    @Test void transferredStaffDoNotExposePreviousDepartmentDailyFacts() throws Exception {
        event(4,FRIDAY,"IN",LocalTime.of(8,0),1);day(FRIDAY,employee,4);
        db.update("UPDATE employee SET dept_id=2 WHERE user_id=4");String newManager=login("u3");
        JsonNode hidden=day(FRIDAY,newManager,4);assertEquals("RESTRICTED",hidden.path("status").asText());assertFalse(hidden.has("checkIn"));
        mvc.perform(get("/attendance/month?month=2026-10&userId=4").header("Authorization",manager)).andExpect(jsonPath("$.code").value("403"));
        int eventId=db.queryForObject("SELECT id FROM records WHERE user_id=4 AND business_date=?",Integer.class,FRIDAY);
        mvc.perform(get("/records/"+eventId).header("Authorization",newManager)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/statistics/recordsStatics").header("Authorization",newManager))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].name").value("迟到"))
                .andExpect(jsonPath("$.data[0].value").value(1));
        assertEquals("MISSING",day(FRIDAY,employee,4).path("status").asText());
    }
    @Test void pastMissingDayWithoutDepartmentEvidenceRemainsRestrictedForManagers() throws Exception {
        assertEquals("MISSING",day(FRIDAY,employee,4).path("status").asText());
        assertEquals(0,db.queryForObject("SELECT dept_id FROM attendance_day WHERE user_id=4 AND business_date=?",Integer.class,FRIDAY));
        assertEquals("RESTRICTED",day(FRIDAY,manager,4).path("status").asText());
    }
    @Test void rawEventsCannotBeMutatedEvenByAdministrator() throws Exception {
        mvc.perform(post("/records").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}")).andExpect(jsonPath("$.code").value("409"));
        mvc.perform(put("/records").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"id\":1,\"stateRadio\":\"正常\"}")).andExpect(jsonPath("$.code").value("409"));
        mvc.perform(delete("/records/1").header("Authorization",admin)).andExpect(jsonPath("$.code").value("409"));
        mvc.perform(post("/records/del/batch").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("[1,2]")).andExpect(jsonPath("$.code").value("409"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
    }
    @Test void explicitOutCanBeRecordedWithoutAnInAndAutomaticClientChoosesRemainingAction() throws Exception {
        enroll();assertEquals("200",verifyFace(challenge("OUT")).path("code").asText());
        assertEquals("OUT",db.queryForObject("SELECT action FROM records WHERE business_date='2026-10-26'",String.class));
        assertEquals("IN",body(mvc.perform(post("/face/challenge").header("Authorization",employee)).andReturn()).path("data").path("action").asText());
        mvc.perform(post("/face/challenge").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"OUT\"}"))
                .andExpect(jsonPath("$.code").value("409"));
    }
    @Test void differentFaceSessionsCannotCreateDuplicateAction() throws Exception {
        enroll();String a=challenge("IN"),b=challenge("IN");
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return 0.8d;});
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<String> first=workers.submit(() -> verifyFace(a).path("code").asText()),second=workers.submit(() -> verifyFace(b).path("code").asText());
            assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown();
            List<String> codes=Arrays.asList(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(codes,"200"));assertEquals(1,Collections.frequency(codes,"409"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM records WHERE user_id=4 AND business_date='2026-10-26' AND action='IN'",Integer.class));
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void crossDaySessionAndWindowClosingDuringInferenceDoNotCreateAnEvent() throws Exception {
        enroll();String cross=challenge("IN");
        at(NOW.plusDays(1));db.update("UPDATE face_checkin_session SET expires_at=? WHERE id=?",NOW.plusDays(1).plusHours(1),cross);
        assertEquals("409",verifyFace(cross).path("code").asText());
        at(NOW.withHour(17).withMinute(59).withSecond(59));String late=challenge("IN");
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {at(NOW.withHour(18).withSecond(1));return 0.8d;});
        assertEquals("422",verifyFace(late).path("code").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM records WHERE business_date IS NOT NULL",Integer.class));
    }
}
