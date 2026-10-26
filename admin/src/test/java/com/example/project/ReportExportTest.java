package com.example.project;

import com.example.project.entity.AttendanceExportJob;
import com.example.project.mapper.AttendanceExportJobMapper;
import com.example.project.report.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ReportExportTest extends SecurityFixture {
    @Autowired ReportExportService exports;
    @Autowired ReportExportWorker worker;
    @Autowired AttendanceExportJobMapper jobs;
    @Autowired MonthlyReportService reports;
    @SpyBean ReportWorkbook workbook;
    @BeforeEach void clearWorkbookSpy(){reset(workbook);}

    @Test void normalizedCreationIsIdempotentAndIgnoresClientOwnerStateAndFile() throws Exception {
        Map<String,Object> input=input();input.put("scope"," self ");input.put("month"," 2026-10 ");input.put("ownerId",1);input.put("state","READY");input.put("fileContent","private");
        JsonNode first=create(employee,input);ok(first);long id=first.path("data").path("id").asLong();
        input.put("scope","SELF");input.put("month","2026-10");JsonNode again=create(employee,input);ok(again);
        assertEquals(id,again.path("data").path("id").asLong());assertEquals(4,jobs.selectById(id).getOwnerId());assertEquals("QUEUED",state(id));
        assertEquals(1,count());assertMetadata(first.path("data"));input.put("month","2026-09");assertCode("409",create(employee,input));
    }
    @Test void concurrentRetryCreatesOneJobAndActiveLimitCannotBeBypassed() throws Exception {
        Map<String,Object> input=input();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        try {
            Future<JsonNode> a=pool.submit(()->{gate.await();return create(employee,input);});Future<JsonNode> b=pool.submit(()->{gate.await();return create(employee,input);});gate.countDown();
            JsonNode first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);ok(first);ok(second);assertEquals(first.path("data").path("id"),second.path("data").path("id"));
        }finally{pool.shutdownNow();}
        ok(create(employee,input()));ok(create(employee,input()));assertEquals(3,count());assertCode("409",create(employee,input()));
        JsonNode listed=list(employee);long id=listed.path("data").path("records").get(0).path("id").asLong();ok(cancel(employee,id));ok(create(employee,input()));
    }
    @Test void allTaskOperationsAreOwnerOnlyEvenForAdministrators() throws Exception {
        long id=id(create(employee,input()));
        for(String other:Arrays.asList(admin,manager,login("u5"))) {
            assertCode("403",detail(other,id));assertCode("403",cancel(other,id));assertCode("403",downloadError(other,id));
            ok(list(other));assertEquals(0,list(other).path("data").path("total").asInt());
        }
        ok(cancel(employee,id));ok(cancel(employee,id));assertEquals("CANCELLED",state(id));assertCode("409",downloadError(employee,id));
        assertCode("422",body(mvc.perform(get("/attendance/reports/exports").header("Authorization",employee).param("pageSize","101")).andReturn()));
    }
    @Test void workbookMatchesPreviewAndUserTextCannotBecomeFormula() throws Exception {
        db.update("UPDATE sys_user SET name='=HYPERLINK(\"https://invalid.example\",\"text\")' WHERE id=4");
        JsonNode preview=body(mvc.perform(get("/attendance/reports/preview").header("Authorization",employee).param("month","2026-10").param("scope","SELF")).andReturn());ok(preview);
        long id=id(create(employee,input()));assertTrue(worker.runOnce());assertEquals("READY",state(id));
        byte[] bytes=mvc.perform(get("/attendance/reports/exports/"+id+"/download").header("Authorization",employee)).andReturn().getResponse().getContentAsByteArray();
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(3,book.getNumberOfSheets());assertEquals("说明",book.getSheetName(0));assertEquals("人员汇总",book.getSheetName(1));assertEquals("每日明细",book.getSheetName(2));
            Cell name=book.getSheetAt(1).getRow(1).getCell(2);assertEquals(CellType.STRING,name.getCellType());assertTrue(name.getStringCellValue().startsWith("=HYPERLINK"));
            assertEquals(preview.path("data").path("statistics").path("workingDays").asInt(),book.getSheetAt(1).getRow(1).getCell(3).getNumericCellValue());
            assertEquals(32,book.getSheetAt(2).getPhysicalNumberOfRows());
            for(Sheet sheet:book) for(Row row:sheet) for(Cell cell:row) {assertNotEquals(CellType.FORMULA,cell.getCellType());if(cell.getCellType()==CellType.STRING) {String value=cell.getStringCellValue();assertFalse(value.contains("test.invalid"));assertFalse(value.contains(HASH));assertFalse(value.contains("地点A"));}}
        }
        assertMetadata(detail(employee,id).path("data"));assertCode("409",cancel(employee,id));
    }
    @Test void currentPermissionsAreCheckedOnCreationExecutionAndDownload() throws Exception {
        long ready=id(create(employee,input()));worker.runOnce();assertEquals("READY",state(ready));long queued=id(create(employee,input()));
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.export')");
        assertCode("403",create(employee,input()));assertCode("403",downloadError(employee,ready));worker.runOnce();assertEquals("FAILED",state(queued));assertEquals("ACCESS_CHANGED",jobs.selectById(queued).getErrorCode());
        ok(list(employee));ok(detail(employee,queued));
    }
    @Test void deletedOwnerCannotBeUsedByBackgroundWorker() throws Exception {
        long id=id(create(employee,input()));db.update("UPDATE sys_user SET deleted=id WHERE id=4");worker.runOnce();assertEquals("FAILED",state(id));assertEquals("ACCESS_CHANGED",jobs.selectById(id).getErrorCode());
    }
    @Test void personnelScopeChangesBeforeExecutionOrDownloadRequireNewTask() throws Exception {
        long ready=id(create(manager,department()));worker.runOnce();assertEquals("READY",state(ready));long queued=id(create(manager,department()));
        db.update("UPDATE employee SET dept_id=2 WHERE user_id=4");
        assertCode("409",downloadError(manager,ready));worker.runOnce();assertEquals("FAILED",state(queued));assertEquals("SCOPE_CHANGED",jobs.selectById(queued).getErrorCode());
    }
    @Test void dailyHistoricalScopeChangesAreRecheckedWhenDownloading() throws Exception {
        knownDay();long id=id(create(manager,department()));worker.runOnce();assertEquals("READY",state(id));
        db.update("UPDATE attendance_day SET dept_id=2 WHERE user_id=4 AND business_date='2026-10-23'");
        assertCode("409",downloadError(manager,id));assertEquals("READY",state(id));
    }
    @Test void changedFactsDoNotRewriteAnAlreadyGeneratedFile() throws Exception {
        knownDay();long id=id(create(employee,input()));worker.runOnce();byte[] original=jobs.content(id,4);
        db.update("INSERT INTO records(user_id,business_date,action,occurred_at,source,dept_id,create_time) VALUES(4,'2026-10-23','IN','2026-10-23 08:00:00','CORRECTION',1,'2026-10-26 09:00:00')");
        byte[] downloaded=mvc.perform(get("/attendance/reports/exports/"+id+"/download").header("Authorization",employee)).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(original,downloaded);
    }
    @Test void cancellationWhileRunningPreventsLatePublication() throws Exception {
        long id=id(create(employee,input()));AttendanceExportJob lease=exports.claimNext();Map<String,Object> report=exports.build(lease);byte[] bytes=workbook.create(report);
        ok(cancel(employee,id));assertFalse(exports.publish(lease,report,bytes));assertEquals("CANCELLED",state(id));assertNull(jobs.content(id,4));
        exports.fail(lease,"GENERATION_FAILED","late error",true);assertEquals("CANCELLED",state(id));
    }
    @Test void publicationRechecksVisibilityAfterWorkbookGeneration() throws Exception {
        knownDay();long id=id(create(manager,department()));
        doAnswer(invocation -> {
            byte[] bytes=(byte[])invocation.callRealMethod();
            db.update("UPDATE attendance_day SET dept_id=2 WHERE user_id=4 AND business_date='2026-10-23'");return bytes;
        }).when(workbook).create(anyMap());
        worker.runOnce();assertEquals("FAILED",state(id));assertEquals("SCOPE_CHANGED",jobs.selectById(id).getErrorCode());
        assertNull(db.queryForObject("SELECT file_content FROM attendance_export_job WHERE id=?",byte[].class,id));
    }
    @Test void simultaneousWorkersCanOnlyClaimOneLease() throws Exception {
        long id=id(create(employee,input()));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        try {
            Future<AttendanceExportJob> first=pool.submit(()->{gate.await();return exports.claimNext();});
            Future<AttendanceExportJob> second=pool.submit(()->{gate.await();return exports.claimNext();});gate.countDown();
            int claimed=0;if(first.get(20,TimeUnit.SECONDS)!=null) claimed++;if(second.get(20,TimeUnit.SECONDS)!=null) claimed++;
            assertEquals(1,claimed);assertEquals("RUNNING",state(id));assertEquals(1,jobs.selectById(id).getAttempts());
        }finally{pool.shutdownNow();}
    }
    @Test void expiredLeaseCanRecoverAndOldWorkerCannotOverwriteNewResult() throws Exception {
        long id=id(create(employee,input()));AttendanceExportJob old=exports.claimNext();Map<String,Object> report=exports.build(old);byte[] bytes=workbook.create(report);
        doReturn(NOW.plusMinutes(6)).when(attendanceClock).now();exports.recoverAndExpire();assertEquals("QUEUED",state(id));
        AttendanceExportJob next=exports.claimNext();assertEquals(2,next.getAttempts());assertNotEquals(old.getLeaseToken(),next.getLeaseToken());
        assertFalse(exports.publish(old,report,bytes));exports.fail(old,"LATE","old failure",false);assertEquals("RUNNING",state(id));
        assertTrue(exports.publish(next,exports.build(next),bytes));assertEquals("READY",state(id));
    }
    @Test void thirdExpiredLeaseTerminatesWithoutLeavingBytes() throws Exception {
        long id=id(create(employee,input()));for(int attempt=1;attempt<=3;attempt++) {
            assertNotNull(exports.claimNext());doReturn(NOW.plusMinutes(6L*attempt)).when(attendanceClock).now();exports.recoverAndExpire();
            assertEquals(attempt<3?"QUEUED":"FAILED",state(id));
        }
        assertNull(exports.claimNext());assertEquals(3,jobs.selectById(id).getAttempts());assertEquals("RETRY_EXHAUSTED",jobs.selectById(id).getErrorCode());assertNull(jobs.content(id,4));
    }
    @Test void transientGenerationFailuresHaveOnlyThreeAttempts() throws Exception {
        long id=id(create(employee,input()));doThrow(new IllegalStateException("sensitive exception details")).when(workbook).create(anyMap());
        for(int attempt=1;attempt<=3;attempt++) {assertTrue(worker.runOnce());assertEquals(attempt<3?"QUEUED":"FAILED",state(id));}
        assertFalse(worker.runOnce());assertEquals(3,jobs.selectById(id).getAttempts());assertFalse(detail(employee,id).toString().contains("sensitive exception"));
    }
    @Test void expiredFileIsRejectedAndContentIsRemovedButMetadataRemains() throws Exception {
        long id=id(create(employee,input()));worker.runOnce();assertNotNull(jobs.content(id,4));
        doReturn(NOW.plusHours(24)).when(attendanceClock).now();exports.recoverAndExpire();assertEquals("EXPIRED",state(id));
        assertNull(db.queryForObject("SELECT file_content FROM attendance_export_job WHERE id=?",byte[].class,id));assertCode("409",downloadError(employee,id));ok(detail(employee,id));assertEquals(1,count());
    }
    @Test void oversizedFileFailsWithoutPublishingPartialContent() throws Exception {
        long id=id(create(employee,input()));doReturn(new byte[ReportWorkbook.MAX_BYTES+1]).when(workbook).create(anyMap());worker.runOnce();
        assertEquals("FAILED",state(id));assertEquals("INVALID_REPORT",jobs.selectById(id).getErrorCode());assertNull(db.queryForObject("SELECT file_content FROM attendance_export_job WHERE id=?",byte[].class,id));
    }
    @Test void malformedConditionsAndUnauthorizedDepartmentAreRejected() throws Exception {
        for(String month:Arrays.asList("2026-1","2026-13","2026-02-30")) {Map<String,Object> value=input();value.put("month",month);assertCode("422",create(employee,value));}
        Map<String,Object> value=input();value.put("requestKey","x");assertCode("422",create(employee,value));value=input();value.put("deptId",1);assertCode("422",create(employee,value));
        assertCode("403",create(employee,department()));
    }
    private void knownDay(){db.update("INSERT INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES(4,'2026-10-23',1,true,1,'08:00:00','18:00:00',0,'2026-10-23 08:00:00')");}
    private Map<String,Object> input(){Map<String,Object> value=new LinkedHashMap<>();value.put("requestKey",UUID.randomUUID().toString());value.put("month","2026-10");value.put("scope","SELF");return value;}
    private Map<String,Object> department(){Map<String,Object> value=input();value.put("scope","DEPARTMENT");value.put("deptId",1);return value;}
    private JsonNode create(String actor,Map<String,Object> input)throws Exception{return body(mvc.perform(post("/attendance/reports/exports").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andReturn());}
    private JsonNode list(String actor)throws Exception{return body(mvc.perform(get("/attendance/reports/exports").header("Authorization",actor)).andReturn());}
    private JsonNode detail(String actor,long id)throws Exception{return body(mvc.perform(get("/attendance/reports/exports/"+id).header("Authorization",actor)).andReturn());}
    private JsonNode cancel(String actor,long id)throws Exception{return body(mvc.perform(post("/attendance/reports/exports/"+id+"/cancel").header("Authorization",actor)).andReturn());}
    private JsonNode downloadError(String actor,long id)throws Exception{return body(mvc.perform(get("/attendance/reports/exports/"+id+"/download").header("Authorization",actor)).andReturn());}
    private String state(long id){return jobs.selectById(id).getState();}
    private int count(){return db.queryForObject("SELECT COUNT(*) FROM attendance_export_job",Integer.class);}
    private long id(JsonNode result){ok(result);return result.path("data").path("id").asLong();}
    private void ok(JsonNode result){assertCode("200",result);}
    private void assertCode(String code,JsonNode result){assertEquals(code,result.path("code").asText(),result.toString());}
    private void assertMetadata(JsonNode metadata){Set<String> actual=new HashSet<>();metadata.fieldNames().forEachRemaining(actual::add);assertEquals(new HashSet<>(Arrays.asList("id","month","scope","deptId","state","attempts","createdAt","startedAt","finishedAt","expiresAt","errorCode","errorMessage")),actual);}
}
