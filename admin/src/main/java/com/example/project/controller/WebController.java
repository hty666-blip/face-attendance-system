package com.example.project.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.example.project.common.FaceContrastServer;
import com.example.project.common.Result;
import com.example.project.common.annotation.AutoLog;
import com.example.project.controller.domain.LoginDTO;
import com.example.project.controller.domain.UserRequest;
import com.example.project.entity.Records;
import com.example.project.entity.Rules;
import com.example.project.entity.User;
import com.example.project.service.IRecordsService;
import com.example.project.service.IRulesService;
import com.example.project.service.IUserService;
import com.example.project.utils.SessionUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Api(tags = "无权限接口列表")
@RestController
@Slf4j
public class WebController {
    @Resource private com.example.project.service.impl.FaceCheckInService checkInService;

    @Resource
    IUserService userService;
    @Resource
    private FaceContrastServer faceContrastServer;
    @Resource
    private IRecordsService recordsService;
    @Resource
    private IRulesService rulesService;

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
        if (input.getId() == null || userService.getById(input.getId()) == null)
            throw new com.example.project.exception.ServiceException("用户不存在");
        if (input.getFace() == null || input.getFace().length() > 7_000_000 || !input.getFace().startsWith("data:image/"))
            throw new com.example.project.exception.ServiceException("请先采集有效的人脸图片");
        User patch = new User(); patch.setId(input.getId()); patch.setFace(input.getFace());
        userService.updateById(patch); return Result.success("录入人脸成功");
    }

    /**
     * 人脸验证
     * @param imageBase
     * @return
     */
    @AutoLog("人脸验证")
    @PostMapping("/faceVerify")

    public Result faceVerify(@RequestBody com.example.project.controller.domain.FaceDto input, @RequestParam String address) {
        checkInService.checkIn(input.getImageBase(), address);
        return Result.success();
    }
}
