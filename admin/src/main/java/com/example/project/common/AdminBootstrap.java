package com.example.project.common;
import com.example.project.entity.User;
import com.example.project.service.IUserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

/** 首次建空库时显式启用；已有管理员则拒绝覆盖。密码只从环境读取，不写日志。 */
@Component
public class AdminBootstrap implements CommandLineRunner {
    @Resource private IUserService users;
    @Value("${app.bootstrap-admin.enabled:false}") private boolean enabled;
    @Value("${app.bootstrap-admin.username:admin}") private String username;
    @Value("${app.bootstrap-admin.password:}") private String password;
    @Override public void run(String... args) {
        if (!enabled) return;
        if (users.count(new QueryWrapper<User>().eq("role","ADMIN")) > 0)
            throw new IllegalStateException("已有管理员，禁止重复初始化；请关闭 BOOTSTRAP_ADMIN_ENABLED");
        User u=new User(); u.setUsername(username); u.setPassword(password); u.setName("管理员");
        u.setEmail("admin@example.invalid"); u.setRole("ADMIN"); users.saveUser(u);
    }
}
