package com.example.project.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.entity.*;
import com.example.project.service.*;
import com.example.project.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;

/** 一个入口负责账户和档案，任一写入失败时回滚。历史考勤保留，账户采用逻辑删除。 */
@Service
public class AccountLifecycleService {
    @Resource private IUserService users;
    @Resource private IEmployeeService employees;
    @Resource private IManagerService managers;
    @Resource private IDeptService departments;
    @Resource private com.example.project.face.FaceTemplateService faceTemplates;

    @Transactional public void createUser(User input) {
        if (input.getId() != null) throw new ServiceException("新增账号不能指定编号");
        users.saveUser(input);
        if ("employee".equals(input.getRole())) {
            Employee e = new Employee(); e.setUsername(input.getUsername()); e.setName(input.getName());
            e.setUserId(input.getId()); employees.save(e);
        } else if ("manager".equals(input.getRole())) {
            Manager m = new Manager(); m.setUsername(input.getUsername()); m.setName(input.getName());
            m.setUserId(input.getId()); managers.save(m);
        }
    }
    @Transactional public void createEmployee(Employee input) {
        checkDepartment(input.getDeptId());
        User u = newUser(input.getUsername(), input.getName(), input.getInitialPassword(), "employee");
        users.saveUser(u); input.setId(null); input.setUserId(u.getId()); employees.save(input);
    }
    @Transactional public void createManager(Manager input) {
        checkDepartment(input.getDeptId());
        User u = newUser(input.getUsername(), input.getName(), input.getInitialPassword(), "manager");
        users.saveUser(u); input.setId(null); input.setUserId(u.getId()); managers.save(input);
    }
    private User newUser(String username, String name, String password, String role) {
        User u = new User(); u.setUsername(username); u.setName(name); u.setPassword(password);
        // 人员表单未采集邮箱；占位地址与用户名解耦，并满足现有30字符列及邮箱校验。
        u.setRole(role); u.setEmail(RandomUtil.randomString(14) + "@example.invalid"); return u;
    }
    private void checkDepartment(Integer id) {
        if (id != null && departments.getById(id) == null) throw new ServiceException("部门不存在");
    }
    @Transactional public void updateEmployee(Employee input) {
        Employee old = employees.getById(input.getId());
        if (old == null) throw new ServiceException("员工不存在");
        requireActive(old.getUserId()); checkDepartment(input.getDeptId());
        input.setUserId(old.getUserId()); input.setUsername(old.getUsername());
        employees.updateById(input); updateName(old.getUserId(), input.getName());
    }
    @Transactional public void updateManager(Manager input) {
        Manager old = managers.getById(input.getId());
        if (old == null) throw new ServiceException("主管不存在");
        requireActive(old.getUserId()); checkDepartment(input.getDeptId());
        input.setUserId(old.getUserId()); input.setUsername(old.getUsername());
        managers.updateById(input); updateName(old.getUserId(), input.getName());
    }
    private void updateName(Integer id, String name) {
        if (StrUtil.isBlank(name)) throw new ServiceException("姓名不能为空");
        User patch = new User(); patch.setId(id); patch.setName(name); users.updateById(patch);
    }
    public User requireActive(Integer id) {
        User u = users.getById(id); if (u == null) throw new ServiceException("账号不存在或已停用"); return u;
    }
    @Transactional public void updateUser(User input) {
        User old = requireActive(input.getId());
        if (input.getRole() != null && !Objects.equals(input.getRole(), old.getRole()))
            throw new ServiceException("基础修复阶段不支持直接变更账号角色，请通过人员管理维护");
        User patch = new User(); patch.setId(old.getId()); patch.setName(input.getName());
        patch.setEmail(input.getEmail()); patch.setAddress(input.getAddress());
        if (StrUtil.isNotBlank(input.getNewpassword())) {
            UserServiceImpl.checkPassword(input.getNewpassword());
            patch.setPassword(cn.dev33.satoken.secure.BCrypt.hashpw(input.getNewpassword()));
        }
        users.updateById(patch);
        if (input.getName() != null) {
            employees.update().eq("user_id", old.getId()).set("name", input.getName()).update();
            managers.update().eq("user_id", old.getId()).set("name", input.getName()).update();
        }
        StpUtil.logout(old.getUid());
    }
    @Transactional public void deleteUsers(List<Integer> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw new ServiceException("请选择1至100个账号");
        List<User> targets = new ArrayList<>();
        for (Integer id : ids) {
            User u = requireActive(id);
            if ("ADMIN".equals(u.getRole())) throw new ServiceException("不允许在此停用管理员账号");
            if (departments.count(new QueryWrapper<Dept>().eq("user_id", id)) > 0)
                throw new ServiceException("请先解除部门主管关联再停用账号");
            targets.add(u);
        }
        for (User u : targets) {
            faceTemplates.purgeForDeactivation(u.getId());
            if(!users.removeById(u.getId())) throw new ServiceException("账号停用失败，请稍后重试");
            StpUtil.logout(u.getUid());
        }
    }
    @Transactional public void deleteEmployees(List<Integer> ids) {
        deleteUsers(ids.stream().map(id -> {
            Employee e = employees.getById(id); if (e == null) throw new ServiceException("员工不存在"); return e.getUserId();
        }).collect(java.util.stream.Collectors.toList()));
    }
    @Transactional public void deleteManagers(List<Integer> ids) {
        deleteUsers(ids.stream().map(id -> {
            Manager m = managers.getById(id); if (m == null) throw new ServiceException("主管不存在"); return m.getUserId();
        }).collect(java.util.stream.Collectors.toList()));
    }
}
