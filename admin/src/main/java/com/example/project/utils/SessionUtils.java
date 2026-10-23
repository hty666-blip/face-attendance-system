package com.example.project.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.example.project.common.Constants;
import com.example.project.entity.User;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SessionUtils {

    // 获取当前登录的用户信息
    public static User getUser() {
        StpUtil.checkLogin();
        User user = SpringContextUtil.getBean(com.example.project.service.IUserService.class)
                .getOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                        .eq("uid", StpUtil.getLoginIdAsString()));
        if (user == null) {
            StpUtil.logout();
            throw new com.example.project.exception.ServiceException("401", "账号已停用，请重新登录");
        }
        return user;
    }

}
