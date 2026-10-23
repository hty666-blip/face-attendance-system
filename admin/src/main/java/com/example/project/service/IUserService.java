package com.example.project.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.project.controller.domain.LoginDTO;
import com.example.project.controller.domain.UserRequest;
import com.example.project.entity.Permission;
import com.example.project.entity.User;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 */
public interface IUserService extends IService<User> {

    LoginDTO login(UserRequest user);

    void register(UserRequest user);

    String passwordReset(UserRequest userRequest);

    void logout(String uid);

    User saveUser(User user);

    List<Permission> getPermissions(String roleFlag);

    void passwordChange(UserRequest userRequest);
}
