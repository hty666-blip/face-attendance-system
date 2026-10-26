package com.example.project;
import com.example.project.exception.ServiceException;
import com.example.project.entity.Records;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 复用基础权限fixture，额外覆盖加密模板、会话、回滚与并发路径。 */
class FaceWorkflowTest extends SecurityFixture {
    @org.springframework.beans.factory.annotation.Autowired com.example.project.face.FaceProviders providers;
    void enroll() throws Exception {
        mvc.perform(post("/face/enroll").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":4,\"images\":[\"image1\",\"image2\",\"image3\"]}"))
                .andExpect(jsonPath("$.code").value("200"));
    }
    String challenge() throws Exception {
        return body(mvc.perform(post("/face/challenge").header("Authorization",employee)).andReturn()).path("data").path("challengeId").asText();
    }
    org.springframework.test.web.servlet.ResultActions verifyFace(String id,String token) throws Exception {
        return mvc.perform(post("/faceVerify?address=office").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\""+id+"\",\"imageBase\":\"test-private-image\"}"));
    }
    @Test void enrollmentEncryptsPayloadAndStatusNeverReturnsIt() throws Exception {
        enroll();String cipher=db.queryForObject("SELECT encrypted_payload FROM face_template WHERE user_id=4",String.class);
        assertFalse(cipher.contains("[[1,0]]"));assertNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        mvc.perform(get("/face/status").header("Authorization",employee)).andExpect(jsonPath("$.data.enrolled").value(true))
                .andExpect(jsonPath("$.data.encryptedPayload").doesNotExist()).andExpect(jsonPath("$.data.embeddings").doesNotExist());
        mvc.perform(get("/face/status?userId=5").header("Authorization",employee)).andExpect(jsonPath("$.code").value("403"));
    }
    @Test void employeesCannotEnrollMigrateOrRevoke() throws Exception {
        for(String path:Arrays.asList("/face/enroll","/face/migrate","/face/revoke"))
            mvc.perform(post(path).header("Authorization",employee).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":5}"))
                    .andExpect(jsonPath("$.code").value("403"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template",Integer.class));
    }
    @Test void failedEnrollmentLeavesOldPhotoAndTemplateUnchanged() throws Exception {
        when(localFace.enroll(anyList())).thenThrow(new ServiceException("422","多人入镜"));
        mvc.perform(post("/face/migrate").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("422"));
        assertNotNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template",Integer.class));
        verifyNoInteractions(face);
    }
    @Test void successfulMigrationProducesOneTemplateAndClearsOnlyTargetPhoto() throws Exception {
        mvc.perform(post("/face/migrate").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("200"));
        verify(localFace).enroll(Collections.singletonList("data:image/png;base64,AQID"));
        assertNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        assertNotNull(db.queryForObject("SELECT face FROM sys_user WHERE id=5",String.class));
    }
    @Test void successfulRetryReturnsSameRecordAndDifferentContentIsRejected() throws Exception {
        enroll();String id=challenge();
        int first=body(verifyFace(id,employee).andExpect(jsonPath("$.code").value("200")).andReturn()).path("data").path("recordId").asInt();
        verifyFace(id,employee).andExpect(jsonPath("$.data.alreadyCompleted").value(true)).andExpect(jsonPath("$.data.recordId").value(first));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        verify(localFace,times(1)).compare(eq("[[1,0]]"),eq("test-private-image"));
        mvc.perform(post("/faceVerify?address=different").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\""+id+"\",\"imageBase\":\"test-private-image\"}"))
                .andExpect(jsonPath("$.code").value("409"));
    }
    @Test void anotherUserExpiredAndReplacedTemplateCannotUseSession() throws Exception {
        enroll();String id=challenge();
        verifyFace(id,manager).andExpect(jsonPath("$.code").value("403"));
        db.update("UPDATE face_checkin_session SET expires_at=? WHERE id=?",NOW.minusMinutes(1),id);
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("409"));
        String second=challenge();enroll();verifyFace(second,employee).andExpect(jsonPath("$.code").value("409"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
    }
    @Test void revocationRejectsOldSessionAndDeletesEncryptedPayload() throws Exception {
        enroll();String id=challenge();
        mvc.perform(post("/face/revoke").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("200"));
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("409"));
        assertNull(db.queryForObject("SELECT encrypted_payload FROM face_template WHERE user_id=4",String.class));
        assertEquals(2,db.queryForObject("SELECT version FROM face_template WHERE user_id=4",Integer.class));
    }
    @Test void serviceFailureAndLowScoreDoNotCreateAttendanceOrFallbackToCloud() throws Exception {
        enroll();when(localFace.compare(anyString(),anyString())).thenThrow(new ServiceException("503","offline"));
        String id=challenge();verifyFace(id,employee).andExpect(jsonPath("$.code").value("503"));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
        doReturn(0.2d).when(localFace).compare(anyString(),anyString());
        verifyFace(challenge(),employee).andExpect(jsonPath("$.code").value("422"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));verifyNoInteractions(face);
    }
    @Test void recordFailureRollsBackSuccessAndMarksSessionFailed() throws Exception {
        enroll();String id=challenge();
        doThrow(new IllegalStateException("injected record failure")).when(recordService).save(any(Records.class));
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("500"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
        assertNull(db.queryForObject("SELECT record_id FROM face_checkin_session WHERE id=?",Integer.class,id));
    }
    @Test void concurrentSubmissionHasOneExecutionAndOneRecord() throws Exception {
        enroll();String id=challenge();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return 0.8d;});
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<String> first=worker.submit(() -> body(verifyFace(id,employee).andReturn()).path("code").asText());
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            verifyFace(id,employee).andExpect(jsonPath("$.code").value("425"));
            release.countDown();assertEquals("200",first.get(5,TimeUnit.SECONDS));
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
            verify(localFace,times(1)).compare(anyString(),anyString());
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void missingAndDeletedTargetsCannotModifyAnyFace() throws Exception {
        for(String operation:Arrays.asList("enroll","migrate","revoke"))
            for(String target:Arrays.asList("null","999"))
                mvc.perform(post("/face/"+operation).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":"+target+",\"images\":[\"a\",\"b\",\"c\"]}"))
                        .andExpect(jsonPath("$.code").value("500"));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE face IS NOT NULL",Integer.class));
        verify(localFace,never()).enroll(anyList());
    }
    @Test void zeroTemplateWriteDoesNotClearLegacyPhoto() throws Exception {
        doReturn(0).when(templateMapper).insert(any(com.example.project.entity.FaceTemplate.class));
        mvc.perform(post("/face/migrate").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("500"));
        assertNotNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template",Integer.class));
    }
    @Test void photoCleanupFailureRollsBackTemplateWrite() throws Exception {
        doReturn(false).when(userService).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        mvc.perform(post("/face/migrate").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("500"));
        assertNotNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template",Integer.class));
    }
    @Test void falseRecordWriteDoesNotCompleteSession() throws Exception {
        enroll();String id=challenge();
        doReturn(false).when(recordService).save(any(Records.class));
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("500"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
    }
    @Test void concurrentCreationNeverExceedsThreeActiveSessions() throws Exception {
        enroll();ExecutorService workers=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<String>> replies=new ArrayList<>();
            for(int i=0;i<6;i++) replies.add(workers.submit(() -> {
                assertTrue(start.await(5,TimeUnit.SECONDS));
                return body(mvc.perform(post("/face/challenge").header("Authorization",employee)).andReturn()).path("code").asText();
            }));
            start.countDown();int success=0,rejected=0;
            for(Future<String> reply:replies) {
                String code=reply.get(10,TimeUnit.SECONDS);
                if("200".equals(code)) success++; else if("429".equals(code)) rejected++; else fail("unexpected code: "+code);
            }
            assertEquals(3,success);assertEquals(3,rejected);
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM face_checkin_session WHERE user_id=4",Integer.class));
        } finally {start.countDown();workers.shutdownNow();}
    }
    @Test void expiredSessionCleanupOnlyAffectsCurrentUser() throws Exception {
        enroll();
        for(int owner:Arrays.asList(4,5)) db.update("INSERT INTO face_checkin_session(id,user_id,provider,template_version,expires_at,state,created_at) VALUES(?,?,'local',1,DATEADD('DAY',-40,CURRENT_TIMESTAMP),'FAILED',DATEADD('DAY',-40,CURRENT_TIMESTAMP))",String.format("%064d",owner),owner);
        challenge();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM face_checkin_session WHERE user_id=4",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM face_checkin_session WHERE user_id=5",Integer.class));
    }
    @Test void revokedTemplateDuringInferenceCannotCreateAttendance() throws Exception {
        enroll();String id=challenge();
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {
            // 用数据库模拟另一管理员在推理期间提交的撤销操作。
            db.update("UPDATE face_template SET active=0,encrypted_payload=NULL,version=version+1 WHERE user_id=4");
            return 0.8d;
        });
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("409"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
    }
    @Test void revokedPermissionDuringInferenceCannotCreateAttendance() throws Exception {
        enroll();String id=challenge();
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {
            db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.add')");
            return 0.8d;
        });
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("403"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
    }
    @Test void disabledAccountDuringInferenceCannotCreateAttendance() throws Exception {
        enroll();String id=challenge();
        when(localFace.compare(anyString(),anyString())).thenAnswer(call -> {
            db.update("UPDATE sys_user SET deleted=1 WHERE id=4");return 0.8d;
        });
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("401"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
    }
    @Test void reenrollmentInvalidatesOldSessionsAndFreesSessionLimit() throws Exception {
        enroll();String old=challenge();challenge();challenge();
        mvc.perform(post("/face/challenge").header("Authorization",employee)).andExpect(jsonPath("$.code").value("429"));
        enroll();
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM face_checkin_session WHERE state='INVALIDATED'",Integer.class));
        verifyFace(old,employee).andExpect(jsonPath("$.code").value("409"));
        mvc.perform(post("/face/challenge").header("Authorization",employee)).andExpect(jsonPath("$.code").value("200"));
    }
    @Test void revocationClearsEveryProviderAndLegacyPhoto() throws Exception {
        enroll();String id=challenge();
        db.update("INSERT INTO face_template(user_id,provider,model_version,encrypted_payload,version,active,updated_by,updated_at) VALUES(4,'tencent','tencent-3.0','old-cloud-cipher',7,1,1,CURRENT_TIMESTAMP)");
        mvc.perform(post("/face/revoke").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template WHERE user_id=4 AND (encrypted_payload IS NOT NULL OR active=1)",Integer.class));
        assertEquals(8,db.queryForObject("SELECT version FROM face_template WHERE user_id=4 AND provider='tencent'",Integer.class));
        assertEquals("INVALIDATED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
        mvc.perform(get("/face/status").header("Authorization",employee)).andExpect(jsonPath("$.data.revocable").value(false));
    }
    @Test void statusAllowsRevokingOtherProviderWithoutCurrentEnrollment() throws Exception {
        db.update("UPDATE sys_user SET face=NULL WHERE id=4");
        db.update("INSERT INTO face_template(user_id,provider,model_version,encrypted_payload,version,active,updated_by,updated_at) VALUES(4,'tencent','tencent-3.0','old-cloud-cipher',7,1,1,CURRENT_TIMESTAMP)");
        mvc.perform(get("/face/status?userId=4").header("Authorization",admin))
                .andExpect(jsonPath("$.data.enrolled").value(false)).andExpect(jsonPath("$.data.revocable").value(true));
    }
    @Test void deactivationClearsBiometricsAndInvalidatesSessionsButPreservesRecords() throws Exception {
        enroll();String id=challenge();
        db.update("INSERT INTO face_template(user_id,provider,model_version,encrypted_payload,version,active,updated_by,updated_at) VALUES(4,'tencent','tencent-3.0','old-cloud-cipher',7,1,1,CURRENT_TIMESTAMP)");
        mvc.perform(delete("/employee/1").header("Authorization",admin)).andExpect(jsonPath("$.code").value("200"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template WHERE user_id=4 AND (encrypted_payload IS NOT NULL OR active=1)",Integer.class));
        assertNull(db.queryForObject("SELECT face FROM sys_user WHERE id=4",String.class));
        assertEquals("INVALIDATED",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM records WHERE user_id=4",Integer.class));
        verifyFace(id,employee).andExpect(jsonPath("$.code").value("401"));
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void malformedFaceRequestDoesNotEchoSensitiveInput(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String secret="private-photo-input-must-not-be-logged";
        String response=mvc.perform(post("/face/enroll").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\""+secret+"\",\"images\":[\"image\"]}"))
                .andExpect(jsonPath("$.code").value("400")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret));assertFalse(output.getAll().contains(secret));
    }
    @Test void disabledRecognitionStillAllowsInspectionAndRevocation() throws Exception {
        enroll();
        org.springframework.test.util.ReflectionTestUtils.setField(providers,"enabled",false);
        try {
            mvc.perform(get("/face/status?userId=4").header("Authorization",admin))
                    .andExpect(jsonPath("$.data.revocable").value(true));
            mvc.perform(get("/face/service-status").header("Authorization",admin))
                    .andExpect(jsonPath("$.data.ready").value(false));
            mvc.perform(post("/face/revoke").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":4}"))
                    .andExpect(jsonPath("$.code").value("200"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM face_template WHERE active=1",Integer.class));
            verify(localFace,never()).ready();
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(providers,"enabled",true);}
    }
    @Test void failedBiometricCleanupRollsBackAccountDeactivation() throws Exception {
        enroll();String id=challenge();
        doReturn(false).when(userService).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        mvc.perform(delete("/employee/1").header("Authorization",admin)).andExpect(jsonPath("$.code").value("500"));
        assertEquals(0,db.queryForObject("SELECT deleted FROM sys_user WHERE id=4",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM face_template WHERE user_id=4 AND encrypted_payload IS NOT NULL AND active=1",Integer.class));
        assertEquals("PENDING",db.queryForObject("SELECT state FROM face_checkin_session WHERE id=?",String.class,id));
    }
}
