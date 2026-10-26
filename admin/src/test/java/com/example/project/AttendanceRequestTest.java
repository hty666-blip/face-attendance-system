package com.example.project;

import com.example.project.entity.AttendanceApproval;
import com.example.project.entity.Records;
import com.example.project.mapper.AttendanceApprovalMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AttendanceRequestTest extends SecurityFixture {
    @SpyBean AttendanceApprovalMapper approvalMapper;
    @BeforeEach void resetWorkflowSpy() {reset(approvalMapper);}

    @Test void applicantAndStateAreAuthoritativeAndCreationRetriesBindNormalizedPayload() throws Exception {
        Map<String,Object> input=correction("IN");
        input.put("userId",5);input.put("deptId",2);input.put("state","APPROVED");input.put("version",99);
        input.put("reason","  外出\r\n说明  ");
        JsonNode created=create(employee,input);ok(created);
        JsonNode row=created.path("data");long id=row.path("id").asLong();
        assertEquals(4,row.path("userId").asInt());assertEquals("PENDING",row.path("state").asText());assertEquals(1,row.path("version").asInt());
        assertEquals("外出\n说明",row.path("reason").asText());assertFalse(row.has("requestHash"));
        input.put("reason","外出\n说明");input.put("kind"," correction ");
        JsonNode replay=create(employee,input);ok(replay);assertEquals(id,replay.path("data").path("id").asLong());
        assertEquals(1,count("attendance_request","1=1"));assertEquals(1,count("attendance_approval","request_id="+id));
        input.put("reason","不同原因");assertEquals("409",create(employee,input).path("code").asText());
    }

    @Test void halfDayLeaveValidatesRangesAndPendingOverlap() throws Exception {
        ok(create(employee,leave("2026-10-27","AM","2026-10-27","AM")));
        assertEquals("409",create(employee,leave("2026-10-27","AM","2026-10-27","PM")).path("code").asText());
        ok(create(employee,leave("2026-10-27","PM","2026-10-27","PM")));
        assertEquals("422",create(employee,leave("2026-10-28","PM","2026-10-28","AM")).path("code").asText());
        assertEquals("422",create(employee,leave("2026-09-25","AM","2026-09-25","PM")).path("code").asText());
        assertEquals("422",create(employee,leave("2027-10-28","AM","2027-10-28","PM")).path("code").asText());
        assertEquals("422",create(employee,leave("2026-11-01","AM","2026-12-02","PM")).path("code").asText());
        Map<String,Object> bad=leave("2026-02-30","AM","2026-10-28","PM");
        assertEquals("422",create(employee,bad).path("code").asText());
        bad=leave("2026-10-28","AM","2026-10-28","PM");bad.put("leaveType","任意假期");
        assertEquals("422",create(employee,bad).path("code").asText());
    }
    @Test void leaveBeforeKnownAccountCreationCannotBeCreatedOrApproved() throws Exception {
        long pending=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        db.update("UPDATE sys_user SET create_time='2026-10-28 00:00:00' WHERE id=4");
        assertEquals("422",create(employee,leave("2026-10-27","AM","2026-10-27","AM")).path("code").asText());
        assertEquals("422",decide(admin,pending,1,"APPROVED","").path("code").asText());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,pending));
    }
    @Test void queuedLeaveMayBeApprovedAfterThirtyDaysWithoutChangingEligibility() throws Exception {
        long pending=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        doReturn(NOW.plusDays(40)).when(attendanceClock).now();doReturn(NOW.toLocalDate().plusDays(40)).when(attendanceClock).today();
        ok(decide(admin,pending,1,"APPROVED","排队处理"));
        assertEquals("APPROVED",db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,pending));
    }

    @Test void approvalRecordsCorrectionAndRetriesWithoutDuplicateHistory() throws Exception {
        knownDay("2026-10-23",1);
        long id=id(create(employee,correction("IN")));
        JsonNode approved=decide(manager,id,1,"APPROVED","  已核实  ");ok(approved);
        assertEquals("APPROVED",approved.path("data").path("state").asText());assertEquals(2,approved.path("data").path("version").asInt());
        assertEquals(1,count("records","request_id="+id+" AND action='IN' AND source='CORRECTION'"));
        assertEquals(2,count("attendance_approval","request_id="+id));
        ok(decide(manager,id,1,"APPROVED","已核实"));
        assertEquals(1,count("records","request_id="+id));assertEquals(2,count("attendance_approval","request_id="+id));
        assertEquals("409",decide(manager,id,1,"APPROVED","另一意见").path("code").asText());
        assertEquals("409",decide(manager,id,1,"REJECTED","已核实").path("code").asText());
        JsonNode detail=detail(employee,id);ok(detail);
        assertEquals(2,detail.path("data").path("history").size());
        assertEquals("PENDING",detail.path("data").path("history").get(0).path("toState").asText());
        assertEquals("APPROVED",detail.path("data").path("history").get(1).path("toState").asText());
    }

    @Test void leaveDurationComesFromServerWorkingHalfDaysAndApprovalRechecksCalendar() throws Exception {
        Map<String,Object> half=leave("2026-10-27","AM","2026-10-27","AM");half.put("leaveDays",99);
        JsonNode created=create(employee,half);ok(created);long id=created.path("data").path("id").asLong();
        assertEquals(0.5d,created.path("data").path("leaveDays").asDouble());
        JsonNode acrossWeekend=create(employee,leave("2026-10-30","PM","2026-11-02","AM"));ok(acrossWeekend);
        assertEquals(1.0d,acrossWeekend.path("data").path("leaveDays").asDouble());
        assertEquals("422",create(employee,leave("2026-10-31","AM","2026-11-01","PM")).path("code").asText());
        db.update("INSERT INTO attendance_calendar(date,working_day,note,updated_by,updated_at) VALUES('2026-10-27',false,'调整休息',1,'2026-10-26 09:00:00')");
        assertEquals(0.0d,detail(employee,id).path("data").path("request").path("leaveDays").asDouble());
        assertEquals("422",decide(manager,id,1,"APPROVED","").path("code").asText());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,id));
        assertEquals(1,count("attendance_approval","request_id="+id));
    }

    @Test void unknownPastDepartmentRequiresAdministratorAndIsNotGuessedFromCurrentAssignment() throws Exception {
        long correction=id(create(employee,correction("IN")));
        assertEquals(0,db.queryForObject("SELECT dept_id FROM attendance_request WHERE id=?",Integer.class,correction));
        assertEquals("403",decide(manager,correction,1,"APPROVED","").path("code").asText());
        ok(decide(admin,correction,1,"APPROVED","管理员核实"));
        long leave=id(create(employee,leave("2026-10-22","AM","2026-10-22","PM")));
        assertEquals(0,db.queryForObject("SELECT dept_id FROM attendance_request WHERE id=?",Integer.class,leave));
        assertEquals("403",decide(manager,leave,1,"APPROVED","").path("code").asText());
        ok(decide(admin,leave,1,"REJECTED","缺少归属证据"));
    }

    @Test void employeeOtherDepartmentAndSelfReviewAreDenied() throws Exception {
        long id=id(create(employee,leave("2026-10-27","AM","2026-10-27","AM")));
        assertEquals("403",decide(employee,id,1,"APPROVED","").path("code").asText());
        assertEquals("403",decide(login("u3"),id,1,"APPROVED","").path("code").asText());
        assertEquals("403",detail(login("u5"),id).path("code").asText());
        long own=id(create(manager,leave("2026-10-28","AM","2026-10-28","PM")));
        assertEquals("403",decide(manager,own,1,"APPROVED","").path("code").asText());
        long adminOwn=id(create(admin,leave("2026-10-29","AM","2026-10-29","AM")));
        assertEquals("403",decide(admin,adminOwn,1,"APPROVED","").path("code").asText());
        db.update("DELETE FROM sys_role_permission WHERE role_id=2 AND permission_id IN (SELECT id FROM sys_permission WHERE auth='leaveapply.edit')");
        assertEquals("403",decide(manager,id,1,"APPROVED","").path("code").asText());
    }

    @Test void transferDoesNotGrantHistoricalScopeToEitherDepartmentManager() throws Exception {
        long id=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        String managerB=login("u3");db.update("UPDATE employee SET dept_id=2 WHERE user_id=4");
        assertEquals("403",detail(manager,id).path("code").asText());assertEquals("403",detail(managerB,id).path("code").asText());
        assertEquals("403",decide(manager,id,1,"APPROVED","").path("code").asText());assertEquals("403",decide(managerB,id,1,"APPROVED","").path("code").asText());
        assertEquals(0,list(manager,"pending").path("data").path("total").asInt());
        assertEquals(0,list(managerB,"pending").path("data").path("total").asInt());
        assertEquals(1,list(employee,"mine").path("data").path("total").asInt());ok(detail(admin,id));
        ok(decide(admin,id,1,"APPROVED","跨部门核实"));
    }

    @Test void ownerCancellationIsIdempotentAndFreesPendingLeaveRange() throws Exception {
        long id=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        assertEquals("403",cancel(login("u5"),id,1).path("code").asText());
        ok(cancel(employee,id,1));ok(cancel(employee,id,1));
        assertEquals(2,count("attendance_approval","request_id="+id));
        assertEquals("CANCELLED",detail(employee,id).path("data").path("request").path("state").asText());
        long next=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        ok(decide(manager,next,1,"APPROVED","") );
        assertEquals("409",cancel(employee,next,1).path("code").asText());
        assertEquals("409",create(employee,leave("2026-10-27","AM","2026-10-27","AM")).path("code").asText());
    }

    @Test void kindsKeepIndependentOperationPermissionsAndPaginationIsBounded() throws Exception {
        long leave=id(create(employee,leave("2026-10-27","AM","2026-10-27","PM")));
        id(create(employee,correction("IN")));
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN (SELECT id FROM sys_permission WHERE auth IN ('leaveapply.add','leaveapply.list'))");
        assertEquals("403",create(employee,leave("2026-10-28","AM","2026-10-28","PM")).path("code").asText());
        JsonNode mine=list(employee,"mine");ok(mine);assertEquals(1,mine.path("data").path("total").asInt());
        assertEquals("CORRECTION",mine.path("data").path("records").get(0).path("kind").asText());
        assertEquals("403",detail(employee,leave).path("code").asText());
        assertEquals("403",list(employee,"all").path("code").asText());
        JsonNode invalid=body(mvc.perform(get("/attendance/requests").header("Authorization",employee).param("pageSize","101")).andReturn());
        assertEquals("422",invalid.path("code").asText());
    }

    @Test void parallelCreationWithSameRequestKeyCreatesOneRequestAndInitialHistory() throws Exception {
        Map<String,Object> input=leave("2026-10-27","AM","2026-10-27","PM");
        List<JsonNode> results=parallel(() -> create(employee,input),() -> create(employee,input));
        for(JsonNode result:results) ok(result);
        assertEquals(results.get(0).path("data").path("id").asLong(),results.get(1).path("data").path("id").asLong());
        assertEquals(1,count("attendance_request","1=1"));assertEquals(1,count("attendance_approval","1=1"));
    }

    @Test void parallelOverlappingLeaveApplicationsCannotBothBeCreated() throws Exception {
        Map<String,Object> first=leave("2026-10-27","AM","2026-10-27","PM"),second=leave("2026-10-27","PM","2026-10-28","AM");
        List<JsonNode> results=parallel(() -> create(employee,first),() -> create(employee,second));
        List<String> codes=new ArrayList<>();for(JsonNode result:results) codes.add(result.path("code").asText());Collections.sort(codes);
        assertEquals(Arrays.asList("200","409"),codes);assertEquals(1,count("attendance_request","1=1"));
    }

    @Test void parallelContradictoryDecisionsProduceOneTerminalHistory() throws Exception {
        knownDay("2026-10-23",1);long id=id(create(employee,correction("IN")));
        List<JsonNode> results=parallel(() -> decide(manager,id,1,"APPROVED","同意"),() -> decide(manager,id,1,"REJECTED","驳回"));
        List<String> codes=new ArrayList<>();for(JsonNode result:results) codes.add(result.path("code").asText());Collections.sort(codes);
        assertEquals(Arrays.asList("200","409"),codes);assertEquals(2,count("attendance_approval","request_id="+id));
        String state=db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,id);
        assertEquals("APPROVED".equals(state)?1:0,count("records","request_id="+id));
    }

    @Test void failedAuditAppendRollsBackRequestStateAndCorrectionEvent() throws Exception {
        knownDay("2026-10-23",1);long id=id(create(employee,correction("IN")));
        doReturn(0).when(approvalMapper).insert(any(AttendanceApproval.class));
        assertEquals("500",decide(manager,id,1,"APPROVED","").path("code").asText());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT version FROM attendance_request WHERE id=?",Integer.class,id));
        assertEquals(0,count("records","request_id="+id));assertEquals(1,count("attendance_approval","request_id="+id));
    }

    @Test void failedInitialHistoryAndFailedRecordWriteDoNotPartiallyCommit() throws Exception {
        doReturn(0).when(approvalMapper).insert(any(AttendanceApproval.class));
        assertEquals("500",create(employee,leave("2026-10-27","AM","2026-10-27","PM")).path("code").asText());
        assertEquals(0,count("attendance_request","1=1"));reset(approvalMapper);
        knownDay("2026-10-23",1);long id=id(create(employee,correction("IN")));
        doReturn(false).when(recordService).save(any(Records.class));
        assertEquals("500",decide(manager,id,1,"APPROVED","").path("code").asText());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM attendance_request WHERE id=?",String.class,id));
        assertEquals(1,count("attendance_approval","request_id="+id));assertEquals(0,count("records","request_id="+id));
    }

    private Map<String,Object> correction(String action) {
        Map<String,Object> input=new LinkedHashMap<>();input.put("requestKey",UUID.randomUUID().toString());input.put("kind","CORRECTION");input.put("reason","忘记打卡，申请核实");
        input.put("businessDate","2026-10-23");input.put("action",action);input.put("correctionTime","2026-10-23 "+("IN".equals(action)?"08:00:00":"18:00:00"));return input;
    }
    private Map<String,Object> leave(String start,String startHalf,String end,String endHalf) {
        Map<String,Object> input=new LinkedHashMap<>();input.put("requestKey",UUID.randomUUID().toString());input.put("kind","LEAVE");input.put("reason","个人事务，请审批");
        input.put("startDate",start);input.put("startHalf",startHalf);input.put("endDate",end);input.put("endHalf",endHalf);input.put("leaveType","事假");return input;
    }
    private void knownDay(String date,int department) {
        db.update("INSERT INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES(4,?,?,true,1,'08:00:00','18:00:00',0,'2026-10-23 08:00:00')",date,department);
    }
    private JsonNode create(String actor,Map<String,Object> payload) throws Exception {
        return body(mvc.perform(post("/attendance/requests").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andReturn());
    }
    private JsonNode decide(String actor,long id,int version,String decision,String comment) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("version",version);payload.put("decision",decision);payload.put("comment",comment);
        return body(mvc.perform(post("/attendance/requests/"+id+"/decision").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andReturn());
    }
    private JsonNode cancel(String actor,long id,int version) throws Exception {
        return body(mvc.perform(post("/attendance/requests/"+id+"/cancel").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}")).andReturn());
    }
    private JsonNode detail(String actor,long id) throws Exception {return body(mvc.perform(get("/attendance/requests/"+id).header("Authorization",actor)).andReturn());}
    private JsonNode list(String actor,String scope) throws Exception {return body(mvc.perform(get("/attendance/requests").header("Authorization",actor).param("scope",scope)).andReturn());}
    private long id(JsonNode response) {ok(response);return response.path("data").path("id").asLong();}
    private void ok(JsonNode response) {assertEquals("200",response.path("code").asText(),response.toString());}
    private int count(String table,String condition) {return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+condition,Integer.class);}
    private List<JsonNode> parallel(Callable<JsonNode> first,Callable<JsonNode> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<JsonNode> a=pool.submit(() -> {start.await();return first.call();});Future<JsonNode> b=pool.submit(() -> {start.await();return second.call();});start.countDown();
            return Arrays.asList(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
}
