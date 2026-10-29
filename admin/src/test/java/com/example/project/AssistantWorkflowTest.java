package com.example.project;

import com.example.project.assistant.*;
import com.example.project.mapper.AssistantToolCallMapper;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AssistantWorkflowTest extends SecurityFixture {
    @MockBean AssistantPlannerClient planner;
    @SpyBean AssistantToolCallMapper toolMapper;
    @SpyBean com.example.project.report.ReportExportService exportService;
    @Autowired AssistantService assistant;
    @BeforeEach void plannerDefaults(){reset(toolMapper,planner,exportService);when(planner.mode()).thenReturn("MOCK");when(planner.status()).thenReturn(map("enabled",true,"ready",true,"mode","MOCK","reason",null));}

    @Test void sessionsRequireLoginRecordsPermissionAndOwner() throws Exception {
        assertEquals("401",body(mvc.perform(get("/assistant/status")).andReturn()).path("code").asText());
        long id=conversation(employee);assertCode("403",getJson(manager,"/assistant/conversations/"+id));assertCode("403",getJson(admin,"/assistant/conversations/"+id));
        assertEquals(0,getJson(manager,"/assistant/conversations").path("data").path("total").asInt());
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.list')");
        assertCode("403",getJson(employee,"/assistant/status"));assertCode("403",getJson(employee,"/assistant/conversations/"+id));
    }
    @Test void summaryUsesRealServiceWhitelistAndIgnoresInventedModelAnswer() throws Exception {
        long conversation=conversation(employee);planTool("query_attendance_summary",map("month","2026-10","scope","SELF","deptId",null));
        JsonNode result=run(employee,input(conversation));ok(result);JsonNode data=result.path("data");assertEquals("COMPLETED",data.path("state").asText());assertEquals("MOCK",data.path("mode").asText());
        assertFalse(data.path("answer").asText().contains("已导出100000人"));JsonNode tool=data.path("tools").get(0);assertEquals("SUCCEEDED",tool.path("state").asText());assertTrue(tool.path("data").has("statistics"));
        assertFalse(tool.path("data").has("people"));assertFalse(tool.path("data").has("days"));assertFalse(result.toString().contains("地点A"));assertFalse(result.toString().contains("test.invalid"));
        verify(planner,atLeastOnce()).plan(argThat(payload -> !payload.toString().contains("地点A") && !payload.toString().contains("人员4")),anyLong());
    }
    @Test void originalRequestRetriesDoNotReplayToolsAndPayloadConflictIsRejected() throws Exception {
        planTool("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));Map<String,Object> input=input(conversation(employee));
        JsonNode first=run(employee,input);JsonNode again=run(employee,input);ok(first);ok(again);assertEquals(first.path("data").path("id"),again.path("data").path("id"));assertEquals(1,count("attendance_export_job"));
        assertFalse(first.path("data").has("ownerId"));assertFalse(first.path("data").has("requestHash"));assertFalse(first.path("data").has("requestKey"));
        assertEquals(first.path("data").path("id"),getJson(employee,"/assistant/runs/by-request?requestKey="+input.get("requestKey")).path("data").path("id"));
        input.put("message","不同问题");assertCode("409",run(employee,input));assertCode("403",getJson(admin,"/assistant/runs/"+first.path("data").path("id").asText()));
        assertCode("404",getJson(manager,"/assistant/runs/by-request?requestKey="+input.get("requestKey")));
    }
    @Test void sameUserConcurrentRetryAndDifferentRequestHaveOneRunningSlot() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger invocations=new AtomicInteger();
        when(planner.plan(anyMap(),anyLong())).thenAnswer(call -> {invocations.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return plan(null,null);});
        Map<String,Object> input=input(conversation(employee));ExecutorService pool=Executors.newSingleThreadExecutor();
        try {Future<JsonNode> first=pool.submit(()->run(employee,input));assertTrue(entered.await(10,TimeUnit.SECONDS));
            JsonNode retry=run(employee,input);ok(retry);assertEquals("RUNNING",retry.path("data").path("state").asText());
            assertCode("409",run(employee,input(((Number)input.get("conversationId")).longValue())));release.countDown();ok(first.get(15,TimeUnit.SECONDS));assertEquals(1,invocations.get());assertEquals(1,count("assistant_run"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void unknownToolAndForgedIdentityCannotReachBusinessCode() throws Exception {
        long conversation=conversation(employee);
        when(planner.plan(anyMap(),anyLong())).thenReturn(plan("execute_sql",map("sql","DROP TABLE records")));JsonNode unknown=run(employee,input(conversation));ok(unknown);assertEquals("TOOL_NOT_ALLOWED",unknown.path("data").path("errorCode").asText());assertEquals(0,count("attendance_export_job"));
        when(planner.plan(anyMap(),anyLong())).thenReturn(plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null,"ownerId",1)));
        JsonNode forged=run(employee,input(conversation));assertEquals("INVALID_ARGUMENTS",forged.path("data").path("errorCode").asText());assertEquals(0,count("attendance_export_job"));
        Map<String,Object> client=input(conversation);client.put("ownerId",1);assertCode("422",run(employee,client));
    }
    @Test void departmentScopeIsRecheckedAndOnlyCurrentDepartmentIsPlanned() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {
            Map<String,Object> payload=invocation.getArgument(0);assertFalse(payload.get("departments").toString().contains("部门B"));return plan("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",2));
        });
        JsonNode result=run(manager,input(conversation(manager)));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals("ACCESS_DENIED",result.path("data").path("tools").get(0).path("errorCode").asText());
    }
    @Test void exportPermissionRevocationAfterPlanningPreventsSideEffects() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {
            assertEquals(true,((Map<?,?>)invocation.getArgument(0)).get("allowExport"));
            db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.export')");
            return plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));
        });
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("ACCESS_DENIED",result.path("data").path("errorCode").asText());assertEquals(0,count("attendance_export_job"));
    }
    @Test void permissionRevocationOfBaseAccessStopsToolAfterModelResponse() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {
            db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.list')");
            return plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));
        });
        JsonNode result=run(employee,input(conversation(employee)));ok(result);assertEquals("FAILED",result.path("data").path("state").asText());assertEquals(0,count("attendance_export_job"));
    }
    @Test void exportTaskAndReceiptCommitTogetherWhenReceiptWriteFails() throws Exception {
        planTool("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));doThrow(new IllegalStateException("private-db-detail")).when(toolMapper).insert(any());
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals(0,count("attendance_export_job"));assertEquals(0,count("assistant_tool_call"));assertFalse(result.toString().contains("private-db-detail"));
    }
    @Test void latePlannerResponseCannotCreateExport() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {doReturn(NOW.plusSeconds(61)).when(attendanceClock).now();return plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));});
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("TIMED_OUT",result.path("data").path("state").asText());assertEquals(0,count("attendance_export_job"));
    }
    @Test void deadlineCrossedDuringExportRollsBackJobAndReceipt() throws Exception {
        planTool("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));
        doAnswer(invocation -> {Object result=invocation.callRealMethod();doReturn(NOW.plusSeconds(61)).when(attendanceClock).now();return result;}).when(exportService).create(any());
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("TIMED_OUT",result.path("data").path("state").asText());assertEquals(0,count("attendance_export_job"));assertEquals(0,count("assistant_tool_call"));
    }
    @Test void crashRecoveryPreservesCommittedExportAndNeverReplaysOriginalRequest() throws Exception {
        planTool("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));Map<String,Object> input=input(conversation(employee));JsonNode first=run(employee,input);String id=first.path("data").path("id").asText();
        db.update("UPDATE assistant_run SET state='RUNNING',deadline_at='2026-10-26 08:59:00',finished_at=NULL WHERE id=?",id);reset(planner);
        JsonNode recovered=getJson(employee,"/assistant/runs/"+id);assertEquals("INTERRUPTED",recovered.path("data").path("state").asText());assertEquals(1,recovered.path("data").path("tools").size());
        ok(run(employee,input));assertEquals(1,count("attendance_export_job"));verifyNoInteractions(planner);
    }
    @Test void repeatedSameExportReturnsSameTaskAndToolLimitIsExplicit() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenReturn(plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null)));
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals("TOOL_LIMIT_REACHED",result.path("data").path("errorCode").asText());assertEquals(1,count("attendance_export_job"));assertEquals(5,result.path("data").path("tools").size());assertTrue(result.path("data").path("answer").asText().contains("文件需生成后"));
        JsonNode tools=result.path("data").path("tools");for(JsonNode receipt:tools)assertEquals(tools.get(0).path("data").path("id"),receipt.path("data").path("id"));
    }
    @Test void secondDifferentExportConditionsConflictWithoutCreatingAnotherTask() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {
            boolean first=((List<?>)((Map<?,?>)invocation.getArgument(0)).get("steps")).isEmpty();return plan("create_attendance_export",map("month",first?"2026-10":"2026-09","scope","SELF","deptId",null));
        });
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals("BUSINESS_CONFLICT",result.path("data").path("errorCode").asText());assertEquals(1,count("attendance_export_job"));
    }
    @Test void fiveToolBudgetStopsFurtherPlanning() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenReturn(plan("query_attendance_summary",map("month","2026-10","scope","SELF","deptId",null)));
        JsonNode result=run(employee,input(conversation(employee)));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals("TOOL_LIMIT_REACHED",result.path("data").path("errorCode").asText());assertTrue(result.path("data").path("answer").asText().contains("达到每轮5个工具的上限"));assertEquals(5,result.path("data").path("tools").size());verify(planner,times(5)).plan(anyMap(),anyLong());
    }
    @Test void exportStatusChecksOwnerEvenForAdministrator() throws Exception {
        planTool("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));run(employee,input(conversation(employee)));long id=db.queryForObject("SELECT id FROM attendance_export_job",Long.class);
        planTool("get_export_status",map("jobId",id));JsonNode result=run(admin,input(conversation(admin)));assertEquals("ACCESS_DENIED",result.path("data").path("errorCode").asText());
    }
    @Test void dailyExplanationIsSelfOnlyAndOmitsSensitiveEventData() throws Exception {
        db.update("INSERT INTO records(user_id,business_date,action,occurred_at,source,dept_id,address,create_time) VALUES(4,'2026-10-26','IN','2026-10-26 08:20:00','FACE',1,'private-address','2026-10-26 08:20:00')");
        planTool("explain_attendance_day",map("date","2026-10-26"));JsonNode result=run(employee,input(conversation(employee)));JsonNode day=result.path("data").path("tools").get(0).path("data");assertEquals(20,day.path("lateMinutes").asInt());assertEquals("FACE",day.path("checkIn").path("source").asText());assertFalse(result.toString().contains("private-address"));
    }
    @Test void dailyExplanationReturnsChineseStatusFromActualAttendanceReceipt() throws Exception {
        db.update("INSERT INTO records(user_id,business_date,action,occurred_at,source,dept_id,create_time) VALUES(4,'2026-10-23','IN','2026-10-23 08:15:00','CORRECTION',1,'2026-10-23 08:15:00'),(4,'2026-10-23','OUT','2026-10-23 17:45:00','CORRECTION',1,'2026-10-23 17:45:00')");
        planTool("explain_attendance_day",map("date","2026-10-23"));JsonNode result=run(employee,input(conversation(employee)));ok(result);
        JsonNode day=result.path("data").path("tools").get(0).path("data");
        assertEquals("LATE_AND_EARLY",day.path("status").asText());assertEquals(15,day.path("lateMinutes").asInt());assertEquals(15,day.path("earlyMinutes").asInt());
        String answer=result.path("data").path("answer").asText();assertTrue(answer.contains("本人日结果：迟到且早退"));assertTrue(answer.contains("迟到15分钟，早退15分钟"));assertFalse(answer.contains("LATE_AND_EARLY"));
    }
    @Test void plannerFailureKeepsHistoryAndDoesNotExposeErrorDetails() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenThrow(new AssistantPlannerClient.PlannerFailure("PLANNER_UNAVAILABLE"));
        long id=conversation(employee);JsonNode result=run(employee,input(id));assertEquals("FAILED",result.path("data").path("state").asText());assertEquals(1,getJson(employee,"/assistant/conversations/"+id).path("data").path("runs").size());
        assertEquals("200",getJson(employee,"/face/status").path("code").asText());
    }
    @Test void modelTimeoutBeforeToolsIsTerminalAndOriginalRequestDoesNotCallPlannerAgain() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenThrow(new AssistantPlannerClient.PlannerFailure("MODEL_TIMEOUT"));
        long conversation=conversation(employee);Map<String,Object> input=input(conversation);JsonNode first=run(employee,input);ok(first);
        JsonNode result=first.path("data");assertEquals("TIMED_OUT",result.path("state").asText());assertEquals("MODEL_TIMEOUT",result.path("errorCode").asText());
        assertTrue(result.path("answer").asText().contains("已超时"));assertEquals(0,count("attendance_export_job"));assertEquals(0,count("assistant_tool_call"));
        assertEquals(result,getJson(employee,"/assistant/runs/by-request?requestKey="+input.get("requestKey")).path("data"));
        assertEquals(result,run(employee,input).path("data"));assertEquals(1,count("assistant_run"));verify(planner,times(1)).plan(anyMap(),anyLong());
        assertEquals("200",getJson(employee,"/face/status").path("code").asText());
    }
    @Test void timeoutAfterCommittedExportKeepsReceiptAndNeverReplaysTheTask() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenAnswer(invocation -> {
            if(((List<?>)((Map<?,?>)invocation.getArgument(0)).get("steps")).isEmpty())
                return plan("create_attendance_export",map("month","2026-10","scope","SELF","deptId",null));
            throw new AssistantPlannerClient.PlannerFailure("MODEL_TIMEOUT");
        });
        long conversation=conversation(employee);Map<String,Object> input=input(conversation);JsonNode first=run(employee,input);ok(first);JsonNode result=first.path("data");
        assertEquals("TIMED_OUT",result.path("state").asText());assertEquals("MODEL_TIMEOUT",result.path("errorCode").asText());assertEquals(1,result.path("tools").size());
        JsonNode receipt=result.path("tools").get(0);assertEquals("SUCCEEDED",receipt.path("state").asText());assertTrue(receipt.path("data").path("id").asLong()>0);
        assertTrue(result.path("answer").asText().contains("已创建导出任务"));assertTrue(result.path("answer").asText().contains("已超时"));
        assertEquals(result,getJson(employee,"/assistant/runs/by-request?requestKey="+input.get("requestKey")).path("data"));
        assertEquals(result,getJson(employee,"/assistant/runs/"+result.path("id").asText()).path("data"));assertEquals(result,run(employee,input).path("data"));
        assertEquals(1,count("attendance_export_job"));assertEquals(1,count("assistant_tool_call"));assertEquals(1,count("assistant_run"));verify(planner,times(2)).plan(anyMap(),anyLong());
        assertCode("403",getJson(manager,"/assistant/runs/"+result.path("id").asText()));
    }
    @Test void departmentReceiptPrivateProofsNeverEnterHttpOrPlanner() throws Exception {
        planTool("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",1));
        JsonNode result=run(manager,input(conversation(manager)));ok(result);assertEquals("COMPLETED",result.path("data").path("state").asText());
        String stored=db.queryForObject("SELECT result_json FROM assistant_tool_call",String.class);assertTrue(stored.contains("_scopeSignature"));assertTrue(stored.contains("_accessSignature"));
        assertFalse(result.toString().contains("_scopeSignature"));assertFalse(result.toString().contains("_accessSignature"));
        verify(planner,atLeastOnce()).plan(argThat(payload -> !payload.toString().contains("_scopeSignature") && !payload.toString().contains("_accessSignature")),anyLong());
    }
    @Test void departmentAccessLossRedactsRunConversationRequestRecoveryAndModelHistory() throws Exception {
        long conversation=conversation(manager);planTool("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",1));Map<String,Object> input=input(conversation);
        JsonNode first=run(manager,input);String id=first.path("data").path("id").asText();assertTrue(first.path("data").path("tools").get(0).path("data").has("statistics"));
        db.update("UPDATE dept SET user_id=3 WHERE id=1");
        assertRestricted(getJson(manager,"/assistant/runs/"+id).path("data"));
        assertRestricted(getJson(manager,"/assistant/runs/by-request?requestKey="+input.get("requestKey")).path("data"));
        assertRestricted(getJson(manager,"/assistant/conversations/"+conversation).path("data").path("runs").get(0));
        doAnswer(invocation -> {
            Map<String,Object> payload=invocation.getArgument(0);assertTrue(payload.get("history").toString().contains("历史可见范围已变化"));assertFalse(payload.get("history").toString().contains("迟到"));assertFalse(payload.toString().contains("statistics"));return plan(null,null);
        }).when(planner).plan(anyMap(),anyLong());
        ok(run(manager,input(conversation)));
    }
    @Test void personnelMovementInvalidatesPreviouslyVisibleDepartmentSummary() throws Exception {
        planTool("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",1));JsonNode first=run(manager,input(conversation(manager)));String id=first.path("data").path("id").asText();
        db.update("UPDATE employee SET dept_id=2 WHERE user_id=4");assertRestricted(getJson(manager,"/assistant/runs/"+id).path("data"));
    }
    @Test void historicalDailyScopeChangeInvalidatesSummaryEvenWhenPeopleStaySame() throws Exception {
        db.update("INSERT INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES(4,'2026-10-23',1,true,1,'08:00:00','18:00:00',0,'2026-10-23 08:00:00')");
        planTool("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",1));JsonNode first=run(manager,input(conversation(manager)));String id=first.path("data").path("id").asText();
        db.update("UPDATE attendance_day SET dept_id=2 WHERE user_id=4 AND business_date='2026-10-23'");assertRestricted(getJson(manager,"/assistant/runs/"+id).path("data"));
    }
    @Test void oldDepartmentReceiptWithoutProofsIsNotAssumedVisible() throws Exception {
        planTool("query_attendance_summary",map("month","2026-10","scope","DEPARTMENT","deptId",1));JsonNode first=run(manager,input(conversation(manager)));String id=first.path("data").path("id").asText();
        JsonNode stored=json.readTree(db.queryForObject("SELECT result_json FROM assistant_tool_call",String.class));((com.fasterxml.jackson.databind.node.ObjectNode)stored).remove(Arrays.asList("_scopeSignature","_accessSignature"));
        db.update("UPDATE assistant_tool_call SET result_json=?",stored.toString());assertRestricted(getJson(manager,"/assistant/runs/"+id).path("data"));
    }
    @Test void sameSecondTurnsAndModelHistoryUseMonotonicIndexInsteadOfRandomUuid() throws Exception {
        when(planner.plan(anyMap(),anyLong())).thenReturn(plan(null,null));long conversation=conversation(employee);
        Map<String,Object> firstInput=input(conversation);firstInput.put("message","第一轮问题");JsonNode first=run(employee,firstInput);assertEquals(1,first.path("data").path("turnIndex").asLong());
        db.update("UPDATE assistant_run SET id='ffffffff-ffff-ffff-ffff-ffffffffffff' WHERE id=?",first.path("data").path("id").asText());
        Map<String,Object> secondInput=input(conversation);secondInput.put("message","第二轮问题");JsonNode second=run(employee,secondInput);assertEquals(2,second.path("data").path("turnIndex").asLong());
        assertEquals(first.path("data").path("createdAt"),second.path("data").path("createdAt"));
        db.update("UPDATE assistant_run SET id='00000000-0000-0000-0000-000000000000' WHERE id=?",second.path("data").path("id").asText());
        JsonNode history=getJson(employee,"/assistant/conversations/"+conversation).path("data").path("runs");assertEquals(2,history.size());
        assertEquals("第一轮问题",history.get(0).path("message").asText());assertEquals("第二轮问题",history.get(1).path("message").asText());
        assertEquals(1,history.get(0).path("turnIndex").asLong());assertEquals(2,history.get(1).path("turnIndex").asLong());
        doAnswer(invocation -> {
            Map<String,Object> payload=invocation.getArgument(0);List<?> previous=(List<?>)payload.get("history");assertEquals(2,previous.size());
            assertEquals("第一轮问题",((Map<?,?>)previous.get(0)).get("message"));assertEquals("第二轮问题",((Map<?,?>)previous.get(1)).get("message"));return plan(null,null);
        }).when(planner).plan(anyMap(),anyLong());
        JsonNode third=run(employee,input(conversation));assertEquals(3,third.path("data").path("turnIndex").asLong());
    }
    private void assertRestricted(JsonNode run){assertEquals("历史可见范围已变化，请重新查询",run.path("answer").asText());JsonNode receipt=run.path("tools").get(0);assertEquals("SUCCEEDED",receipt.path("state").asText());assertTrue(receipt.path("data").path("visibilityChanged").asBoolean());assertFalse(receipt.path("data").has("statistics"));assertFalse(receipt.path("data").has("departmentName"));assertFalse(run.toString().contains("_scopeSignature"));}

    private void planTool(String name,Map<String,Object> args){doAnswer(call -> ((List<?>)((Map<?,?>)call.getArgument(0)).get("steps")).isEmpty()?plan(name,args):plan(null,null)).when(planner).plan(anyMap(),anyLong());}
    private JsonNode plan(String tool,Map<String,Object> args){return json.valueToTree(map("contractVersion","assistant-plan-v1","mode","MOCK","message","已导出100000人","toolCalls",tool==null?Collections.emptyList():Collections.singletonList(map("name",tool,"arguments",args)),"usage",map("inputTokens",0,"outputTokens",0)));}
    private Map<String,Object> input(long conversation){return map("conversationId",conversation,"requestKey",UUID.randomUUID().toString(),"message","查询本月本人考勤");}
    private long conversation(String token)throws Exception{JsonNode result=postJson(token,"/assistant/conversations",map("title","合成测试"));ok(result);return result.path("data").path("id").asLong();}
    private JsonNode run(String token,Map<String,Object> input)throws Exception{return postJson(token,"/assistant/runs",input);}
    private JsonNode postJson(String token,String path,Map<String,Object> input)throws Exception{return body(mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andReturn());}
    private JsonNode getJson(String token,String path)throws Exception{return body(mvc.perform(get(path).header("Authorization",token)).andReturn());}
    private int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void ok(JsonNode result){assertCode("200",result);}
    private void assertCode(String code,JsonNode result){assertEquals(code,result.path("code").asText(),result.toString());}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
}
