package com.example.project.service;

import com.example.project.entity.Role;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IRoleService extends IService<Role> {

    void savePermissions(Integer roleId, List<Integer> permissionIds);

    void deleteRole(Integer id);
}
