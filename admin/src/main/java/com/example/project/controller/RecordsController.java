package com.example.project.controller;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.example.project.common.annotation.AutoLog;
import cn.hutool.core.date.DateUtil;
import com.example.project.entity.Dept;
import com.example.project.entity.Employee;
import com.example.project.entity.User;
import com.example.project.mapper.UserMapper;
import com.example.project.service.IDeptService;
import com.example.project.service.IEmployeeService;
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
import com.example.project.service.IRecordsService;
import com.example.project.entity.Records;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
* 考勤记录 前端控制器
* </p>
*
* @author wx:ericxu1116
* @since 2025-01-23
*/
@RestController
@RequestMapping("/records")
public class RecordsController {
    @Resource private com.example.project.security.AccessPolicy accessPolicy;

    @Resource
    private IRecordsService recordsService;
    @Resource
    private IDeptService deptService;
    @Resource
    private IEmployeeService employeeService;

    @AutoLog("新增考勤记录")
    @PostMapping
    public Result save(@RequestBody Records records) {
        throw new com.example.project.exception.ServiceException("请使用人脸打卡，不能直接新增考勤");
    }

    @AutoLog("编辑考勤记录")
    @PutMapping
    public Result update(@RequestBody Records records) {
        recordsService.updateById(records);


        return Result.success();
    }

    @AutoLog("删除考勤记录")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        recordsService.removeById(id);
        return Result.success();
    }

    @AutoLog("批量删除考勤记录")
    @PostMapping("/del/batch")
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        recordsService.removeByIds(ids);
        return Result.success();
    }

    @GetMapping
    public Result findAll() {
        return Result.success(recordsService.list(accessPolicy.users(new QueryWrapper<Records>())));
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        return Result.success(recordsService.getOne(accessPolicy.users(new QueryWrapper<Records>().eq("id", id))));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<Records> queryWrapper = accessPolicy.users(new QueryWrapper<Records>()).orderByDesc("id");
        return Result.success(recordsService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<Records> list = recordsService.list(accessPolicy.users(new QueryWrapper<Records>()));
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        writer.write(list, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("Records信息表", "UTF-8");
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
