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
import com.example.project.service.IDeptService;
import com.example.project.entity.Dept;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
* 部门信息 前端控制器
* </p>
*
* @author wx:ericxu1116
* @since 2025-01-23
*/
@RestController
@RequestMapping("/dept")
public class DeptController {
    @Resource private com.example.project.security.AccessPolicy accessPolicy;

    @Resource
    private IDeptService deptService;

    @AutoLog("新增部门管理")
    @PostMapping
    public Result save(@RequestBody Dept dept) {
        validateManager(dept); dept.setId(null); deptService.save(dept);
        return Result.success();
    }

    @AutoLog("编辑部门管理")
    @PutMapping
    public Result update(@RequestBody Dept dept) {
        validateManager(dept); deptService.updateById(dept);


        return Result.success();
    }

    @AutoLog("删除部门管理")
    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public Result delete(@PathVariable Integer id) { return deleteBatch(java.util.Collections.singletonList(id)); }
    @PostMapping("/del/batch")
    @org.springframework.transaction.annotation.Transactional
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw new com.example.project.exception.ServiceException("请选择1至100个部门");
        com.example.project.service.IEmployeeService employees = SpringContextUtil.getBean(com.example.project.service.IEmployeeService.class);
        com.example.project.service.IManagerService managers = SpringContextUtil.getBean(com.example.project.service.IManagerService.class);
        if (employees.count(new QueryWrapper<com.example.project.entity.Employee>().in("dept_id",ids)) > 0
                || managers.count(new QueryWrapper<com.example.project.entity.Manager>().in("dept_id",ids)) > 0)
            throw new com.example.project.exception.ServiceException("部门仍有关联人员，不能删除");
        deptService.removeByIds(ids); return Result.success();
    }
    private void validateManager(Dept dept) {
        if (dept.getName() == null || dept.getName().trim().isEmpty()) throw new com.example.project.exception.ServiceException("部门名称不能为空");
        if (dept.getUserId() != null) {
            User manager = SpringContextUtil.getBean(com.example.project.service.IUserService.class).getById(dept.getUserId());
            if (manager == null || !"manager".equals(manager.getRole())) throw new com.example.project.exception.ServiceException("请选择有效的部门主管账号");
        }
    }

    @GetMapping
    public Result findAll() {
        return Result.success(deptService.list(accessPolicy.departments(new QueryWrapper<Dept>())));
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        return Result.success(deptService.getOne(accessPolicy.departments(new QueryWrapper<Dept>().eq("id", id))));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<Dept> queryWrapper = accessPolicy.departments(new QueryWrapper<Dept>()).orderByDesc("id");
        queryWrapper.like(!"".equals(name), "name", name);
        return Result.success(deptService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<Dept> list = deptService.list(accessPolicy.departments(new QueryWrapper<Dept>()));
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        writer.write(list, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("Dept信息表", "UTF-8");
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
        throw new com.example.project.exception.ServiceException("基础修复阶段暂停此类批量导入，请使用业务表单");
    }



}
