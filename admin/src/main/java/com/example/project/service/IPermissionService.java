package com.example.project.service;

import com.example.project.entity.Permission;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IPermissionService extends IService<Permission> {

    List<Permission> tree();

    void deletePermission(Integer id);
}
