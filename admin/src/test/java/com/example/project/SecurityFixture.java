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

@SpringBootTest(properties={"face.enabled=true", "face.template-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}) @AutoConfigureMockMvc @ActiveProfiles("test")
abstract class SecurityFixture {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean FaceContrastServer face;
    @MockBean com.example.project.face.LocalFaceProvider localFace;
    @SpyBean com.example.project.service.IRecordsService recordService;
    @SpyBean IEmployeeService employees;
    @SpyBean com.example.project.mapper.FaceTemplateMapper templateMapper;
    @SpyBean com.example.project.service.IUserService userService;
    static final String PASSWORD = "TestPassword8!";
    static final String HASH = BCrypt.hashpw(PASSWORD);
    String admin, manager, employee;

    @BeforeEach void fixture() throws Exception {
        // MyBatis MapperFactoryBean 产生的代理不依赖 Spring 的自动重置顺序。
        reset(templateMapper,userService,recordService,employees,localFace,face);
        for (String table : Arrays.asList("face_checkin_session","face_template","sys_role_permission","sys_permission","sys_role","employee","manager","dept","records","leaveapply","rules","notice","sys_user"))
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
        when(localFace.id()).thenReturn("local");
        when(localFace.modelVersion()).thenReturn(com.example.project.face.LocalFaceProvider.MODEL);
        when(localFace.threshold()).thenReturn(0.45d);
        when(localFace.ready()).thenReturn(true);
        when(localFace.enroll(anyList())).thenReturn("[[1,0]]");
        when(localFace.compare(anyString(),anyString())).thenReturn(0.8d);
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

}
