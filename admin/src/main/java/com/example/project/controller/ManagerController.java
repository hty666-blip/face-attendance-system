package com.example.project.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.example.project.common.annotation.AutoLog;
import cn.hutool.core.date.DateUtil;
import com.example.project.entity.User;
import com.example.project.mapper.UserMapper;
import com.example.project.utils.SessionUtils;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelWriter;
import com.example.project.utils.SpringContextUtil;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletOutputStream;
import java.net.URLEncoder;
import cn.dev33.satoken.annotation.SaIgnore;
import com.example.project.service.IUserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.common.Result;
import org.springframework.web.multipart.MultipartFile;
import com.example.project.service.IManagerService;
import com.example.project.entity.Manager;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
* 部门主管 前端控制器
* </p>
*
* @author wx:ericxu1116
* @since 2025-01-23
*/
@RestController
@RequestMapping("/manager")
public class ManagerController {
    @Resource private com.example.project.service.impl.AccountLifecycleService accounts;
    @Resource private com.example.project.security.AccessPolicy accessPolicy;
    private QueryWrapper<Manager> managerScope(QueryWrapper<Manager> query) {
        if (!accessPolicy.isAdmin()) query.eq("user_id", SessionUtils.getUser().getId());
        return query;
    }

    @Resource
    private IManagerService managerService;

    @AutoLog("新增部门主管")
    @PostMapping
    public Result save(@RequestBody Manager manager) {
        accounts.createManager(manager);
        return Result.success();
    }

    @AutoLog("编辑部门主管")
    @PutMapping
    public Result update(@RequestBody Manager manager) {
        accounts.updateManager(manager);
        return Result.success();
    }

    @AutoLog("删除部门主管")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        accounts.deleteManagers(java.util.Collections.singletonList(id));
        return Result.success();
    }

    @AutoLog("批量删除部门主管")
    @PostMapping("/del/batch")
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        accounts.deleteManagers(ids);
        return Result.success();
    }

    @GetMapping
    public Result findAll() {
        return Result.success(managerService.list(managerScope(new QueryWrapper<Manager>().inSql("user_id", "select id from sys_user where deleted = 0"))));
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        return Result.success(managerService.getOne(managerScope(new QueryWrapper<Manager>().inSql("user_id", "select id from sys_user where deleted = 0").eq("id", id))));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<Manager> queryWrapper = managerScope(new QueryWrapper<Manager>().inSql("user_id", "select id from sys_user where deleted = 0")).orderByDesc("id");
        queryWrapper.like(!"".equals(name), "name", name);
        return Result.success(managerService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<Manager> list = managerService.list(managerScope(new QueryWrapper<Manager>().inSql("user_id", "select id from sys_user where deleted = 0")));
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        writer.write(list, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("Manager信息表", "UTF-8");
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
