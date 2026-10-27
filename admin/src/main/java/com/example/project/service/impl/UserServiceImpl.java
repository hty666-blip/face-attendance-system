package com.example.project.service.impl;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.project.common.Constants;
import com.example.project.controller.domain.LoginDTO;
import com.example.project.controller.domain.UserRequest;
import com.example.project.entity.Permission;
import com.example.project.entity.Role;
import com.example.project.entity.RolePermission;
import com.example.project.entity.User;
import com.example.project.entity.Manager;
import com.example.project.mapper.ManagerMapper;
import com.example.project.entity.Employee;
import com.example.project.mapper.EmployeeMapper;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.RolePermissionMapper;
import com.example.project.mapper.UserMapper;
import com.example.project.service.IPermissionService;
import com.example.project.service.IRoleService;
import com.example.project.service.IUserService;
import com.example.project.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @org.springframework.beans.factory.annotation.Value("${app.registration-enabled:false}")
    private boolean registrationEnabled;

    private static final long TIME_IN_MS5 = 5 * 60 * 1000;  // 表示5分钟的毫秒数

    @Resource
    RolePermissionMapper rolePermissionMapper;

    @Resource
    IRoleService roleService;

    @Resource
    IPermissionService permissionService;

    @Override
    public LoginDTO login(UserRequest user) {
        if (StrUtil.isBlank(user.getUsername()) || StrUtil.isBlank(user.getPassword())) throw new ServiceException("请输入账号和密码");
        User dbUser;
        try {
            dbUser = getOne(new UpdateWrapper<User>().eq("username", user.getUsername())
                    .or().eq("email", user.getUsername()));
        } catch (Exception e) {
            throw new RuntimeException("数据库异常");
        }
        if (dbUser == null) {
            throw new ServiceException("用户名或密码错误");
        }
//        String securePass = SaSecureUtil.aesEncrypt(Constants.LOGIN_USER_KEY, user.getPassword());
//        if (!securePass.equals(dbUser.getPassword())) {
//            throw new ServiceException("用户名或密码错误");
//        }
        if (!BCrypt.checkpw(user.getPassword(), dbUser.getPassword())) {
            throw new ServiceException("用户名或密码错误");
        }
        // 登录
        StpUtil.login(dbUser.getUid());  // loginId
        StpUtil.getSession().set(Constants.LOGIN_USER_KEY, dbUser);
        String tokenValue = StpUtil.getTokenInfo().getTokenValue();
//        LoginDTO loginDTO = new LoginDTO(dbUser, tokenValue);

        // 查询用户的菜单树（2层）
        String flag = dbUser.getRole();
        List<Permission> all = getPermissions(flag);  // 水平
        List<Permission> menus = getTreePermissions(all); // 树
        // 页面的按钮权限集合
        List<Permission> auths = all.stream().filter(permission -> permission.getType() == 3).collect(Collectors.toList());
        return LoginDTO.builder().user(dbUser).token(tokenValue).menus(menus).auths(auths).build();
    }

    public List<Permission> getPermissions(String roleFlag) {
        Role role = roleService.getOne(new QueryWrapper<Role>().eq("flag", roleFlag));
        if (role == null) return new ArrayList<>();
        List<RolePermission> rolePermissions = rolePermissionMapper.selectList(new QueryWrapper<RolePermission>().eq("role_id", role.getId()));
        List<Integer> permissionIds = rolePermissions.stream().map(RolePermission::getPermissionId).collect(Collectors.toList());
        List<Permission> permissionList = permissionService.list();
        List<Permission> all = new ArrayList<>();  // 水平的菜单树
        for (Integer permissionId : permissionIds) {
            permissionList.stream().filter(permission -> permission.getId().equals(permissionId)).findFirst()
                    .ifPresent(all::add);
        }
        return all;
    }

    @Override
    public void passwordChange(UserRequest request) {
        User actor = com.example.project.utils.SessionUtils.getUser();
        if (StrUtil.isBlank(request.getPassword()) || !BCrypt.checkpw(request.getPassword(), actor.getPassword()))
            throw new ServiceException("原密码错误");
        checkPassword(request.getNewPassword());
        User patch = new User(); patch.setId(actor.getId()); patch.setPassword(BCrypt.hashpw(request.getNewPassword()));
        updateById(patch); StpUtil.logout(actor.getUid());
    }

    // 获取角色对应的菜单树
    private List<Permission> getTreePermissions(List<Permission> all) {
        // 菜单树 1级 -> 2级
        List<Permission> parentList = all.stream().filter(permission -> permission.getType() == 1
                || (permission.getType() == 2 && permission.getPid() == null)).collect(Collectors.toList());// type==1 是目录  或者  pid = null
        for (Permission permission : parentList) {
            Integer pid = permission.getId();
            List<Permission> level2List = all.stream().filter(permission1 -> pid.equals(permission1.getPid())).collect(Collectors.toList());// 2级菜单
            permission.setChildren(level2List);
        }
        return parentList.stream().sorted(Comparator.comparing(Permission::getOrders)).collect(Collectors.toList());  // 排序
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void register(UserRequest request) {
        if (!registrationEnabled) throw new ServiceException("403", "当前关闭自助注册，请联系管理员创建账号");
        if (request.getRole() != null && !"employee".equals(request.getRole()))
            throw new ServiceException("403", "自助注册只能创建员工账号");
        User u = new User(); u.setUsername(request.getUsername()); u.setEmail(request.getEmail());
        u.setName(request.getName()); u.setPassword(request.getPassword()); u.setRole("employee");
        saveUser(u);
        Employee profile = new Employee(); profile.setUserId(u.getId()); profile.setUsername(u.getUsername()); profile.setName(u.getName());
        SpringContextUtil.getBean(EmployeeMapper.class).insert(profile);
    }

    /**
     * 重置密码
     *
     * @param userRequest
     * @return
     */
    @Override
    public String passwordReset(UserRequest request) {
        if (!"ADMIN".equals(com.example.project.utils.SessionUtils.getUser().getRole()))
            throw new ServiceException("403", "只有管理员可以重置密码");
        StpUtil.checkPermission("user.edit"); checkPassword(request.getNewPassword());
        User target = getOne(new QueryWrapper<User>().eq("uid", request.getUid()));
        if (target == null) throw new ServiceException("账号不存在");
        User patch = new User(); patch.setId(target.getId()); patch.setPassword(BCrypt.hashpw(request.getNewPassword()));
        updateById(patch); StpUtil.logout(target.getUid()); return "密码已重置";
    }

    @Override public void logout(String uid) {
        String current = StpUtil.getLoginIdAsString();
        if (!current.equals(uid)) throw new ServiceException("403", "只能退出当前登录账号");
        StpUtil.logout();
    }

    public static void checkPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ServiceException("密码须为8至64位，UTF-8编码不超过72字节");
    }

    public User saveUser(User user) {
        User dbUser = getOne(new UpdateWrapper<User>().eq("username", user.getUsername()));
        if (dbUser != null) {
            throw new ServiceException("用户已存在");
        }
        dbUser = getOne(new UpdateWrapper<User>().eq("email", user.getEmail()));
        if (dbUser != null) {
            throw new ServiceException("邮箱已经存在");
        }
        // 设置昵称
        if (StrUtil.isBlank(user.getName())) {
//                String name = Constants.USER_NAME_PREFIX + DateUtil.format(new Date(), Constants.DATE_RULE_YYYYMMDD)
//                        + RandomUtil.randomString(4);
            user.setName(user.getUsername());
        }
        String password = StrUtil.isNotBlank(user.getNewpassword()) ? user.getNewpassword() : user.getPassword();
        checkPassword(password); user.setPassword(password);
        if (!java.util.Arrays.asList("ADMIN", "manager", "employee").contains(user.getRole())) throw new ServiceException("角色无效");
        if (StrUtil.isBlank(user.getUsername()) || user.getUsername().length() > 64
                || user.getEmail() == null || !user.getEmail().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || user.getEmail().length() > 30)
            throw new ServiceException("账号或邮箱格式不正确，邮箱最多30个字符");
        user.setId(null); user.setDeleted(0); user.setFace(null); user.setScore(0);
        // 加密用户密码
        user.setPassword(BCrypt.hashpw(user.getPassword()));  // BCrypt加密
        // 设置唯一标识
        user.setUid(IdUtil.fastSimpleUUID());
        boolean saved;
        try {
            saved = save(user);
        } catch (Exception e) {
            throw new RuntimeException("注册失败", e);
        }
        if (!saved) throw new ServiceException("账号创建失败，请稍后重试");
        return user;
    }

    /**
     *  更新用户积分
     * @param score 待更新的积分
     * @param userId  用户id
     */
    public static void setScore(Integer score, Integer userId) {
        UserMapper userMapper = SpringContextUtil.getBean(UserMapper.class);
        userMapper.setScore(score, userId);
    }

}
