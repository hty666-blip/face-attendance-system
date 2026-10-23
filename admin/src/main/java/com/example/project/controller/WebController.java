package com.example.project.controller;

import com.example.project.common.Result;
import com.example.project.common.annotation.AutoLog;
import com.example.project.controller.domain.LoginDTO;
import com.example.project.controller.domain.UserRequest;
import com.example.project.entity.User;
import com.example.project.service.IUserService;
import com.example.project.utils.SessionUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Api(tags = "会话与用户接口")
@RestController
@Slf4j
public class WebController {
    @Resource private com.example.project.face.FaceSessionService faceSessions;

    @Resource
    IUserService userService;

    @GetMapping("/getCurrentUser")
    public User getLoginUser(){
        return SessionUtils.getUser();
    }

    @GetMapping(value = "/")
    @ApiOperation(value = "版本校验接口")
    public String version() {
        String ver = "partner-back-0.0.1-SNAPSHOT";  // 应用版本号
        Package aPackage = WebController.class.getPackage();
        String title = aPackage.getImplementationTitle();
        String version = aPackage.getImplementationVersion();
        if (title != null && version != null) {
            ver = String.join("-", title, version);
        }
        return ver;
    }

    @AutoLog("用户登录")
    @ApiOperation(value = "用户登录接口")
    @PostMapping("/login")
    public Result login(@RequestBody UserRequest user) {
        long startTime = System.currentTimeMillis();
        LoginDTO res = userService.login(user);
        log.info("登录花费时间 {}ms", System.currentTimeMillis() - startTime);
        return Result.success(res);
    }

    @AutoLog("用户退出登录")
    @ApiOperation(value = "用户退出登录接口")
    @GetMapping("/logout/{uid}")

    public Result logout(@PathVariable String uid) {
        userService.logout(uid);
        return Result.success();
    }

    @AutoLog("用户注册")
    @ApiOperation(value = "用户注册接口")
    @PostMapping("/register")
    public Result register(@RequestBody UserRequest user) {
        userService.register(user);
        return Result.success();
    }

    @AutoLog("用户重置密码")
    @ApiOperation(value = "密码重置接口")
    @PostMapping("/password/reset")
    public Result passwordReset(@RequestBody UserRequest userRequest) {
        String newPass = userService.passwordReset(userRequest);
        return Result.success(newPass);
    }

    @AutoLog("用户修改密码")
    @PostMapping("/password/change")
    public Result passwordChange(@RequestBody UserRequest userRequest) {
        userService.passwordChange(userRequest);
        return Result.success();
    }

    @AutoLog("编辑用户")
    @PutMapping("/updateUser")
    public Result updateUser(@RequestBody com.example.project.controller.domain.ProfileUpdate input) {
        User actor = SessionUtils.getUser();
        if (input.getEmail() == null || !input.getEmail().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || input.getEmail().length() > 30)
            throw new com.example.project.exception.ServiceException("邮箱格式不正确，最多30个字符");
        User patch = new User(); patch.setId(actor.getId()); patch.setEmail(input.getEmail());
        if (input.getAvatar() != null) {
            if (!input.getAvatar().matches("(?:https?://[^/]+)?/file/download/[a-f0-9]{32}\\.(?:png|jpg|jpeg)"))
                throw new com.example.project.exception.ServiceException("头像必须使用系统上传的图片");
            patch.setAvatar(input.getAvatar());
        }
        userService.updateById(patch);
        return Result.success(userService.getById(actor.getId()));
    }

    @AutoLog("更新人脸信息")
    @PostMapping("/updateFace")
    public Result updateFace(@RequestBody User input) {
        throw new com.example.project.exception.ServiceException("409", "请使用员工人脸管理中的三张照片采集接口");
    }

    /**
     * 以当前登录用户和短期会话验证本人，并在事务中写入考勤。
     */
    @AutoLog("人脸验证")
    @PostMapping("/faceVerify")

    public Result faceVerify(@RequestBody com.example.project.controller.domain.FaceDto input, @RequestParam String address) {
        return Result.success(faceSessions.verify(input.getChallengeId(), input.getImageBase(), address));
    }
}
