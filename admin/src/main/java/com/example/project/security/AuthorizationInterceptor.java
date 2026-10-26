package com.example.project.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.project.utils.SessionUtils;
import com.example.project.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.annotation.Resource;
import javax.servlet.http.*;
import java.util.*;

/** 默认拒绝未登记的业务路由；新增路由必须声明其策略。 */
@Component
public class AuthorizationInterceptor implements HandlerInterceptor {
    @Resource private AccessPolicy policy;
    private static final Set<String> RESOURCES = new HashSet<>(Arrays.asList(
            "user", "role", "permission", "dict", "employee", "manager", "dept", "records", "rules", "leaveapply", "notice"));
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if (("/".equals(path) || "/health".equals(path)) && "GET".equals(method)) return true;
        if (("/login".equals(path) || "/register".equals(path)) && "POST".equals(method)) return true;
        StpUtil.checkLogin();
        SessionUtils.getUser(); // 每次从数据库读取，账号删除/角色变更即时生效。
        for (String key : Arrays.asList("pageNum", "pageSize")) {
            String value = request.getParameter(key);
            if (value != null) {
                try {
                    int n = Integer.parseInt(value);
                    if (n < 1 || ("pageSize".equals(key) && n > 100)) throw new NumberFormatException();
                } catch (NumberFormatException e) { throw new ServiceException("422", "分页参数无效，单页最多100条"); }
            }
        }
        if ("/getCurrentUser".equals(path) && "GET".equals(method)) return true;
        if (path.startsWith("/logout/") && "GET".equals(method)) return true;
        if ("/password/change".equals(path) && "POST".equals(method)) return true;
        if ("/updateUser".equals(path) && "PUT".equals(method)) return true;
        if ("/password/reset".equals(path) && "POST".equals(method)) {
            policy.adminOnly(); StpUtil.checkPermission("user.edit"); return true;
        }
        if ("/updateFace".equals(path) && "POST".equals(method)) {
            policy.adminOnly(); StpUtil.checkPermission("employee.edit"); return true;
        }
        if ("/faceVerify".equals(path) && "POST".equals(method)) {
            StpUtil.checkPermission("records.add"); return true;
        }
        if (path.startsWith("/face/")) {
            if ("GET".equals(method) && ("/face/status".equals(path) || "/face/service-status".equals(path))) return true;
            if ("POST".equals(method) && "/face/challenge".equals(path)) {StpUtil.checkPermission("records.add");return true;}
            if ("POST".equals(method) && Arrays.asList("/face/enroll","/face/migrate","/face/revoke").contains(path)) {
                policy.adminOnly();StpUtil.checkPermission("employee.edit");return true;
            }
            AccessPolicy.deny();
        }
        if (path.startsWith("/attendance/")) {
            if (path.startsWith("/attendance/reports/")) {
                if ("GET".equals(method) && "/attendance/reports/preview".equals(path)) {
                    StpUtil.checkPermission("records.list"); return true;
                }
                if ("POST".equals(method) && "/attendance/reports/exports".equals(path)) {
                    StpUtil.checkPermission("records.list"); StpUtil.checkPermission("records.export"); return true;
                }
                if ("GET".equals(method) && path.matches("/attendance/reports/exports/\\d+/download")) {
                    StpUtil.checkPermission("records.list"); StpUtil.checkPermission("records.export"); return true;
                }
                if ("GET".equals(method) && ("/attendance/reports/exports".equals(path) || path.matches("/attendance/reports/exports/\\d+"))) return true;
                if ("POST".equals(method) && path.matches("/attendance/reports/exports/\\d+/cancel")) return true;
                AccessPolicy.deny();
            }
            if ("GET".equals(method) && "/attendance/today".equals(path)) {
                StpUtil.checkPermission("records.add"); return true;
            }
            if ("GET".equals(method) && "/attendance/month".equals(path)) {
                StpUtil.checkPermission("records.list"); return true;
            }
            if (Arrays.asList("/attendance/policies", "/attendance/calendar").contains(path)) {
                if ("GET".equals(method)) {
                    if (!StpUtil.hasPermission("rules.list") && !StpUtil.hasPermission("records.list")
                            && !StpUtil.hasPermission("leaveapply.add")) AccessPolicy.deny();
                    return true;
                }
                if ("POST".equals(method)) {policy.adminOnly(); StpUtil.checkPermission("rules.edit"); return true;}
            }
            if ("GET".equals(method) && ("/attendance/requests".equals(path) || path.matches("/attendance/requests/\\d+"))) {
                if (!StpUtil.hasPermission("records.list") && !StpUtil.hasPermission("leaveapply.list")) AccessPolicy.deny();
                return true; // Service根据申请类型、本人/部门与历史部门再次过滤。
            }
            if ("POST".equals(method) && "/attendance/requests".equals(path)) {
                if (!StpUtil.hasPermission("records.add") && !StpUtil.hasPermission("leaveapply.add")) AccessPolicy.deny();
                return true;
            }
            if ("POST".equals(method) && path.matches("/attendance/requests/\\d+/cancel")) return true;
            if ("POST".equals(method) && path.matches("/attendance/requests/\\d+/decision")) {
                if (!Arrays.asList("ADMIN", "manager").contains(SessionUtils.getUser().getRole())) AccessPolicy.deny();
                if (!StpUtil.hasPermission("records.edit") && !StpUtil.hasPermission("leaveapply.edit")) AccessPolicy.deny();
                return true;
            }
            AccessPolicy.deny();
        }
        if (path.startsWith("/file/")) return true; // 文件内部检查所有者与共享图片属性。
        if (path.startsWith("/statistics/") && "GET".equals(method)) {
            if (!Arrays.asList("ADMIN", "manager").contains(SessionUtils.getUser().getRole())) AccessPolicy.deny();
            if ("/statistics/recordsStatics".equals(path)) {StpUtil.checkPermission("records.list"); return true;}
            if ("/statistics/leaveStatics".equals(path)) {StpUtil.checkPermission("leaveapply.list"); return true;}
            AccessPolicy.deny();
        }
        String[] parts = path.substring(1).split("/");
        String resource = parts[0];
        if (!RESOURCES.contains(resource)) { AccessPolicy.deny(); return false; }
        if (Arrays.asList("role", "permission", "dict").contains(resource)) policy.adminOnly();
        // 这些下拉列表返回受限字段/范围，不要求额外取得系统用户管理权限。
        if ("GET".equals(method) && parts.length == 1 && Arrays.asList("user", "dept", "manager").contains(resource)) return true;
        if ("user".equals(resource) && "GET".equals(method) && parts.length == 2 && parts[1].matches("\\d+")) return true;
        if ("notice".equals(resource) && "GET".equals(method) && (parts.length == 1 || "findTop10".equals(parts[1]) || parts[1].matches("\\d+"))) return true;
        String action;
        if ("GET".equals(method)) action = path.endsWith("/export") ? "export" : "list";
        else if ("POST".equals(method)) action = path.endsWith("/import") ? "import" : path.endsWith("/del/batch") ? "deleteBatch" : "add";
        else if ("PUT".equals(method)) action = "edit";
        else if ("DELETE".equals(method)) action = "delete";
        else { AccessPolicy.deny(); return false; }
        if (!"GET".equals(method) && !"leaveapply".equals(resource)) policy.adminOnly();
        if ("import".equals(action)) policy.adminOnly();
        if ("user".equals(resource)) policy.adminOnly();
        StpUtil.checkPermission(resource + "." + action);
        return true;
    }
}
