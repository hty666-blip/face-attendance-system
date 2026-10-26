package com.example.project;

import com.example.project.common.FaceContrastServer;
import com.example.project.entity.Employee;
import com.example.project.service.IEmployeeService;
import com.fasterxml.jackson.databind.*;
import cn.dev33.satoken.secure.BCrypt;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.apache.poi.ss.usermodel.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FoundationSecurityTest extends SecurityFixture {
    @Test void anonymousCannotExportReadUsersResetOrDeleteNotice() throws Exception {
        for(String path:Arrays.asList("/user","/records/export","/employee/export","/file/download/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"))
            mvc.perform(get(path)).andExpect(jsonPath("$.code").value("401"));
        mvc.perform(post("/password/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value("401"));
        mvc.perform(delete("/notice/1")).andExpect(jsonPath("$.code").value("401"));
    }
    @Test void employeeAndManagerHaveScopedListsDetailsAndExports() throws Exception {
        for(String token:Arrays.asList(employee,manager)) {
            mvc.perform(get("/records/page?pageNum=1&pageSize=10").header("Authorization",token))
                    .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].userId").value(4));
            mvc.perform(get("/records/2").header("Authorization",token)).andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(get("/leaveapply/2").header("Authorization",token)).andExpect(jsonPath("$.data").isEmpty());
            byte[] bytes=mvc.perform(get("/records/export").header("Authorization",token)).andReturn().getResponse().getContentAsByteArray();
            try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                Sheet sheet=book.getSheetAt(0); assertEquals(1,sheet.getLastRowNum());
                String all=sheet.getRow(1).toString(); assertFalse(all.contains("地点B"));
            }
        }
    }
    @Test void managerStatisticsDoNotIncludeOtherDepartment() throws Exception {
        mvc.perform(get("/statistics/recordsStatics").header("Authorization",manager))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/statistics/recordsStatics").header("Authorization",employee))
                .andExpect(jsonPath("$.code").value("403"));
    }
    @Test void loggedInEmployeeCannotUseAdminOperationsEvenWithGrantedMenuPermission() throws Exception {
        mvc.perform(delete("/employee/2").header("Authorization",employee)).andExpect(jsonPath("$.code").value("403"));
        mvc.perform(put("/role").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value("403"));
        mvc.perform(post("/password/reset").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value("403"));
    }
    @Test void personalProfileIgnoresForgedIdentityRolePasswordAndFace() throws Exception {
        mvc.perform(put("/updateUser").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"role\":\"ADMIN\",\"password\":\"forged\",\"face\":\"forged\",\"email\":\"changed@test.invalid\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals("employee",db.queryForObject("SELECT role FROM sys_user WHERE id=4",String.class));
        assertEquals(HASH,db.queryForObject("SELECT password FROM sys_user WHERE id=4",String.class));
        assertEquals("u1@test.invalid",db.queryForObject("SELECT email FROM sys_user WHERE id=1",String.class));
        mvc.perform(get("/user/1").header("Authorization",employee)).andExpect(jsonPath("$.code").value("403"));
    }
    @Test void registrationCannotCreateManagersAndDefaultIsClosed() throws Exception {
        mvc.perform(post("/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"evil\",\"role\":\"ADMIN\",\"password\":\"Password88\"}"))
                .andExpect(jsonPath("$.code").value("403"));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class));
    }
    @Test void legacyFreeTextLeaveCannotCreateNewAttendanceFacts() throws Exception {
        mvc.perform(post("/leaveapply").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":99,\"userId\":5,\"typeRadio\":\"事假\",\"periods\":\"1天\",\"stateRadio\":\"通过\"}"))
                .andExpect(jsonPath("$.code").value("409"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM leaveapply",Integer.class));
    }
    @Test void employeeCannotApproveAndManagerCannotApproveOtherDepartment() throws Exception {
        mvc.perform(put("/leaveapply").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"typeRadio\":\"事假\",\"periods\":\"2天\",\"stateRadio\":\"通过\"}"))
                .andExpect(jsonPath("$.code").value("409"));
        assertEquals("审核中",db.queryForObject("SELECT state_radio FROM leaveapply WHERE id=1",String.class));
        mvc.perform(put("/leaveapply").header("Authorization",manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":2,\"stateRadio\":\"通过\"}"))
                .andExpect(jsonPath("$.code").value("403"));
        mvc.perform(put("/leaveapply").header("Authorization",manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"stateRadio\":\"通过\",\"userId\":5}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(4,db.queryForObject("SELECT user_id FROM leaveapply WHERE id=1",Integer.class));
        mvc.perform(put("/leaveapply").header("Authorization",manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"stateRadio\":\"不通过\"}"))
                .andExpect(jsonPath("$.code").value("500"));
    }
    @Test void accountAndProfileCreationRollbackTogether() throws Exception {
        doThrow(new IllegalStateException("injected profile failure")).when(employees).save(any(Employee.class));
        mvc.perform(post("/employee").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rollback\",\"name\":\"测试\",\"deptId\":1,\"initialPassword\":\"Password88\"}"))
                .andExpect(jsonPath("$.code").value("500"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='rollback'",Integer.class));
        verify(employees).save(any(Employee.class));
    }
    @Test void accountDeleteRevokesAccessAndPreservesAttendance() throws Exception {
        mvc.perform(delete("/employee/1").header("Authorization",admin)).andExpect(jsonPath("$.code").value("200"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM records WHERE user_id=4",Integer.class));
        mvc.perform(get("/records").header("Authorization",employee)).andExpect(jsonPath("$.code").value("401"));
    }
    @Test void roleChangesAndMissingActionPermissionsTakeEffectImmediately() throws Exception {
        db.update("DELETE FROM sys_role_permission WHERE role_id=3 AND permission_id IN(SELECT id FROM sys_permission WHERE auth='records.export')");
        mvc.perform(get("/records/export").header("Authorization",employee)).andExpect(jsonPath("$.code").value("403"));
        db.update("UPDATE sys_user SET role='employee' WHERE id=2");
        mvc.perform(get("/records/page?pageNum=1&pageSize=10").header("Authorization",manager))
                .andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void adminUserExportContainsNoHashesOrTemplates() throws Exception {
        byte[] bytes=mvc.perform(get("/user/export").header("Authorization",admin)).andReturn().getResponse().getContentAsByteArray();
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(4,book.getSheetAt(0).getRow(0).getLastCellNum());
        }
    }
    @Test void corsHasNoDateExpiryAndRejectsUnknownOrigins() throws Exception {
        mvc.perform(options("/login").header("Origin","http://localhost:7000").header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","content-type,authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:7000"));
        mvc.perform(options("/login").header("Origin","https://unknown.invalid").header("Access-Control-Request-Method","POST"))
                .andExpect(status().isForbidden());
    }
    @Test void excessivePaginationIsRejected() throws Exception {
        mvc.perform(get("/records/page?pageNum=1&pageSize=100000").header("Authorization",admin))
                .andExpect(jsonPath("$.code").value("422"));
    }
    @Test void successfulCreationAndUpdateKeepAccountProfileLinked() throws Exception {
        mvc.perform(post("/employee").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newstaff\",\"name\":\"新员工\",\"deptId\":1,\"initialPassword\":\"Password88\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        Integer userId=db.queryForObject("SELECT id FROM sys_user WHERE username='newstaff'",Integer.class);
        Integer profile=db.queryForObject("SELECT id FROM employee WHERE user_id=?",Integer.class,userId);
        mvc.perform(put("/employee").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":"+profile+",\"userId\":1,\"username\":\"forged\",\"name\":\"新姓名\",\"deptId\":1}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals("新姓名",db.queryForObject("SELECT name FROM sys_user WHERE id=?",String.class,userId));
        assertEquals(userId,db.queryForObject("SELECT user_id FROM employee WHERE id=?",Integer.class,profile));
        assertEquals("newstaff",db.queryForObject("SELECT username FROM employee WHERE id=?",String.class,profile));
    }
    @Test void managerWithoutDepartmentGetsEmptyPageAndExport() throws Exception {
        db.update("UPDATE dept SET user_id=3 WHERE id=1");
        mvc.perform(get("/records/page?pageNum=1&pageSize=10").header("Authorization",manager))
                .andExpect(jsonPath("$.code").value("200")).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void changingPasswordUsesCurrentIdentityAndRevokesSession() throws Exception {
        String target=db.queryForObject("SELECT uid FROM sys_user WHERE id=1",String.class);
        mvc.perform(post("/password/change").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\""+target+"\",\"password\":\""+PASSWORD+"\",\"newPassword\":\"NewPassword88\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        assertEquals(HASH,db.queryForObject("SELECT password FROM sys_user WHERE id=1",String.class));
        assertTrue(BCrypt.checkpw("NewPassword88",db.queryForObject("SELECT password FROM sys_user WHERE id=4",String.class)));
        mvc.perform(get("/records").header("Authorization",employee)).andExpect(jsonPath("$.code").value("401"));
    }
    @Test void userCannotLogoutAnotherAccountOrEnrollAnotherFace() throws Exception {
        String target=db.queryForObject("SELECT uid FROM sys_user WHERE id=1",String.class);
        mvc.perform(get("/logout/"+target).header("Authorization",employee)).andExpect(jsonPath("$.code").value("403"));
        mvc.perform(post("/updateFace").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"face\":\"data:image/png;base64,AQID\"}"))
                .andExpect(jsonPath("$.code").value("403"));
    }
    @Test void healthIsReachableAndSensitiveResponsesStayRedacted() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/getCurrentUser").header("Authorization",employee))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.face").doesNotExist());
    }
    @Test void departmentsWithPeopleAndBuiltInRolesCannotBeDeleted() throws Exception {
        mvc.perform(delete("/dept/1").header("Authorization",admin)).andExpect(jsonPath("$.code").value("500"));
        mvc.perform(delete("/role/3").header("Authorization",admin)).andExpect(jsonPath("$.code").value("500"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM dept",Integer.class));
    }
}
