package com.example.project.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.project.entity.Role;
import com.example.project.entity.RolePermission;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.RoleMapper;
import com.example.project.mapper.RolePermissionMapper;
import com.example.project.service.IRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements IRoleService {
    @Resource
    private RolePermissionMapper rolePermissionMapper;

    @Transactional
    @Override
public void savePermissions(Integer roleId, List<Integer> permissionIds) {
        if (permissionIds == null || roleId == null) {
            throw new ServiceException("数据不能为空");
        }
        rolePermissionMapper.delete(new UpdateWrapper<RolePermission>()
                .eq("role_id", roleId));
        permissionIds.forEach(v -> {
            RolePermission rolePermission = new RolePermission();
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(v);
            rolePermissionMapper.insert(rolePermission);
        });
    }

    @Override
    @Transactional
public void deleteRole(Integer id) {
        Role role = getById(id);
        if (role == null) throw new ServiceException("角色不存在");
        if (java.util.Arrays.asList("ADMIN","manager","employee").contains(role.getFlag())) throw new ServiceException("不能删除系统内置角色");
        if (com.example.project.utils.SpringContextUtil.getBean(com.example.project.service.IUserService.class)
                .count(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.example.project.entity.User>().eq("role",role.getFlag())) > 0)
            throw new ServiceException("角色仍被账号使用");
        rolePermissionMapper.delete(new UpdateWrapper<RolePermission>()
                .eq("role_id", id));
        removeById(id);
    }
}
