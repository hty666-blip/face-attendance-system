package com.example.project.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.secure.BCrypt;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelWriter;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletOutputStream;
import java.net.URLEncoder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.project.common.annotation.AutoLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.InputStream;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.common.Result;
import org.springframework.web.multipart.MultipartFile;
import com.example.project.service.IUserService;
import com.example.project.entity.User;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
*  前端控制器
* </p>
*/
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {
    @Resource private com.example.project.service.impl.AccountLifecycleService accounts;
    @Resource private com.example.project.security.AccessPolicy policy;

    @Resource
    private IUserService userService;

    // 新增或者更新
    @AutoLog("新增用户")
    @PostMapping
    public Result save(@RequestBody User user) {
        accounts.createUser(user);
        return Result.success();
    }

    @AutoLog("编辑用户")
    @PutMapping
    public Result update(@RequestBody User user) {
        accounts.updateUser(user);
        return Result.success();
    }

    @AutoLog("删除用户")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        accounts.deleteUsers(java.util.Collections.singletonList(id));
        return Result.success();
    }

    @AutoLog("批量删除用户")
    @PostMapping("/del/batch")
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        accounts.deleteUsers(ids);
        return Result.success();
    }

    @GetMapping

    public Result findAll() {
        return Result.success(userService.list(new QueryWrapper<User>().select("id", "name", "role")
                .in(!policy.isAdmin(), "id", policy.visibleUserIds())));
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        if (!policy.isAdmin()) policy.requireSelf(id);
        return Result.success(userService.getById(id));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam(defaultValue = "") String address,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>().orderByDesc("id");
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        // and
        queryWrapper.like(StrUtil.isNotBlank(address), "address", address);
        return Result.success(userService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<User> list = userService.list();
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>();
        for (User u : list) {
            java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
            row.put("账号",u.getUsername()); row.put("姓名",u.getName()); row.put("邮箱",u.getEmail()); row.put("角色",u.getRole());
            rows.add(row);
        }
        writer.write(rows, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("User信息表", "UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");

        ServletOutputStream out = response.getOutputStream();
        writer.flush(out, true);
        out.close();
        writer.close();
    }

    /**
    * excel 导入
    * @param file
    * @throws Exception
    */
    @PostMapping("/import")
    public Result imp(MultipartFile file) throws Exception {
        throw new com.example.project.exception.ServiceException("基础修复阶段暂停账号/档案批量导入，请使用新增功能");
    }

}
