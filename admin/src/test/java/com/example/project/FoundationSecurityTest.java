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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class FoundationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean FaceContrastServer face;
    @SpyBean IEmployeeService employees;
    static final String PASSWORD = "TestPassword8!";
    static final String HASH = BCrypt.hashpw(PASSWORD);
    String admin, manager, employee;

    @BeforeEach void fixture() throws Exception {
        for (String table : Arrays.asList("sys_role_permission","sys_permission","sys_role","employee","manager","dept","records","leaveapply","rules","notice","sys_user"))
            db.update("DELETE FROM " + table);
        db.update("INSERT INTO sys_role(id,name,flag,deleted) VALUES(1,'admin','ADMIN',0),(2,'manager','manager',0),(3,'employee','employee',0)");
        int id=1;
        for (String resource : Arrays.asList("user","role","permission","dict","employee","manager","dept","records","rules","leaveapply","notice"))
            for (String action : Arrays.asList("list","add","edit","delete","deleteBatch","import","export")) {
                db.update("INSERT INTO sys_permission(id,name,auth,type,orders,deleted) VALUES(?,?,?,3,1,0)",id,"test",resource+"."+action);
                for(int role=1;role<=3;role++) db.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",role,id);
                id++;
            }
        for (int u=1;u<=5;u++) {
            String role = u==1 ? "ADMIN" : u<=3 ? "manager" : "employee";
            db.update("INSERT INTO sys_user(id,username,password,name,email,uid,role,deleted,face) VALUES(?,?,?,?,?,?,?,0,?)",
                    u,"u"+u,HASH,"人员"+u,"u"+u+"@test.invalid",UUID.randomUUID().toString(),role,"data:image/png;base64,AQID");
        }
        db.update("INSERT INTO dept(id,name,user_id) VALUES(1,'部门A',2),(2,'部门B',3)");
        db.update("INSERT INTO employee(id,username,name,user_id,dept_id) VALUES(1,'u4','人员4',4,1),(2,'u5','人员5',5,2)");
        db.update("INSERT INTO manager(id,username,name,user_id,dept_id) VALUES(1,'u2','人员2',2,1),(2,'u3','人员3',3,2)");
        db.update("INSERT INTO records(id,user_id,address,state_radio) VALUES(1,4,'地点A','正常'),(2,5,'地点B','迟到')");
        db.update("INSERT INTO leaveapply(id,user_id,type_radio,periods,content,state_radio) VALUES(1,4,'事假','1天','原因A','审核中'),(2,5,'病假','1天','原因B','审核中')");
        db.update("INSERT INTO rules(id,name,time) VALUES(1,'上班时间','08:00'),(2,'下班时间','18:00')");
        admin=login("u1"); manager=login("u2"); employee=login("u4");
    }
    String login(String user) throws Exception {
        JsonNode result = body(mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new HashMap<String,String>(){{put("username",user);put("password",PASSWORD);}}))).andReturn());
        assertEquals("200",result.path("code").asText(),result.toString());
        assertFalse(result.path("data").path("user").has("password"));
        assertFalse(result.path("data").path("user").has("face"));
        return "Bearer "+result.path("data").path("token").asText();
    }
    JsonNode body(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsByteArray());}

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
    @Test void leaveSubmissionOverridesOwnerAndApprovalFields() throws Exception {
        mvc.perform(post("/leaveapply").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":99,\"userId\":5,\"typeRadio\":\"事假\",\"periods\":\"1天\",\"content\":\"<script>bad()</script><p>原因</p>\",\"stateRadio\":\"通过\",\"remarks\":\"伪造审批\"}"))
                .andExpect(jsonPath("$.code").value("200"));
        Map<String,Object> row=db.queryForMap("SELECT user_id,state_radio,remarks,content FROM leaveapply ORDER BY id DESC LIMIT 1");
        assertEquals(4,row.get("user_id")); assertEquals("审核中",row.get("state_radio")); assertNull(row.get("remarks"));
        assertFalse(row.get("content").toString().contains("script"));
    }
    @Test void employeeCannotApproveAndManagerCannotApproveOtherDepartment() throws Exception {
        mvc.perform(put("/leaveapply").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"typeRadio\":\"事假\",\"periods\":\"2天\",\"stateRadio\":\"通过\"}"))
                .andExpect(jsonPath("$.code").value("200"));
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
    @Test void cloudFailureDoesNotCreateAttendanceAndNumericScoreUsesOnlySelf() throws Exception {
        Map<String,Object> failure=new HashMap<>(); failure.put("code",500); failure.put("error","测试服务故障");
        when(face.faceContrast(anyString(),anyString())).thenReturn(failure);
        mvc.perform(post("/faceVerify?address=office").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageBase\":\"AQID\"}")).andExpect(jsonPath("$.code").value("503"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records",Integer.class));
        Map<String,Object> success=new HashMap<>(); success.put("code",200); success.put("score",90.5d);
        when(face.faceContrast(anyString(),anyString())).thenReturn(success);
        mvc.perform(post("/faceVerify?address=office").header("Authorization",employee).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageBase\":\"AQID\"}")).andExpect(jsonPath("$.code").value("200"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM records WHERE user_id=4",Integer.class));
        verify(face,times(2)).faceContrast(eq("data:image/png;base64,AQID"),eq("AQID"));
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
                .andExpect(jsonPath("$.code").value("500"));
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
