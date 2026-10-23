package com.example.project.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.service.*;
import com.example.project.utils.SessionUtils;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/** 页面、详情、导出及统计共用的数据范围，不接受客户端声明的角色或部门范围。 */
@Component
public class AccessPolicy {
    @Resource private IDeptService deptService;
    @Resource private IEmployeeService employeeService;
    public boolean isAdmin() { return "ADMIN".equals(SessionUtils.getUser().getRole()); }
    public void adminOnly() { if (!isAdmin()) deny(); }
    public static void deny() { throw new ServiceException("403", "无权执行此操作"); }
    public List<Integer> departmentIds() {
        User actor = SessionUtils.getUser();
        if ("manager".equals(actor.getRole())) {
            return deptService.list(new QueryWrapper<Dept>().eq("user_id", actor.getId()))
                    .stream().map(Dept::getId).collect(Collectors.toList());
        }
        return employeeService.list(new QueryWrapper<Employee>().eq("user_id", actor.getId()))
                .stream().map(Employee::getDeptId).filter(Objects::nonNull).collect(Collectors.toList());
    }
    public List<Integer> visibleUserIds() {
        User actor = SessionUtils.getUser();
        List<Integer> ids = new ArrayList<>();
        ids.add(actor.getId());
        if ("manager".equals(actor.getRole())) {
            List<Integer> departments = departmentIds();
            if (!departments.isEmpty()) {
                ids.addAll(employeeService.list(new QueryWrapper<Employee>().in("dept_id", departments))
                        .stream().map(Employee::getUserId).collect(Collectors.toList()));
            }
        }
        return ids;
    }
    public <T> QueryWrapper<T> users(QueryWrapper<T> query) {
        if (!isAdmin()) query.in("user_id", visibleUserIds());
        return query;
    }
    public <T> QueryWrapper<T> departments(QueryWrapper<T> query) {
        if (!isAdmin()) {
            List<Integer> ids = departmentIds();
            if (ids.isEmpty()) query.apply("1 = 0"); else query.in("id", ids);
        }
        return query;
    }
    public void requireUser(Integer userId) {
        if (userId == null || (!isAdmin() && !visibleUserIds().contains(userId))) deny();
    }
    public void requireSelf(Integer userId) {
        if (!Objects.equals(SessionUtils.getUser().getId(), userId)) deny();
    }
}
