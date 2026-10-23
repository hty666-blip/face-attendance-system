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
import com.example.project.service.IRulesService;
import com.example.project.entity.Rules;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
* 考勤规则 前端控制器
* </p>
*
* @author wx:ericxu1116
* @since 2025-01-23
*/
@RestController
@RequestMapping("/rules")
public class RulesController {

    @Resource
    private IRulesService rulesService;
    private void validate(Rules rule) {
        if (!java.util.Arrays.asList("上班时间","下班时间").contains(rule.getName())) throw new com.example.project.exception.ServiceException("规则名称无效");
        try { java.time.LocalTime.parse(rule.getTime(), java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
        catch (Exception e) { throw new com.example.project.exception.ServiceException("时间格式应为HH:mm"); }
        if (rulesService.count(new QueryWrapper<Rules>().eq("name",rule.getName()).ne(rule.getId()!=null,"id",rule.getId())) > 0)
            throw new com.example.project.exception.ServiceException("规则名称不能重复");
    }

    @AutoLog("新增考勤规则")
    @PostMapping
    public Result save(@RequestBody Rules rules) {



        validate(rules); rulesService.save(rules);
        return Result.success();
    }

    @AutoLog("编辑考勤规则")
    @PutMapping
    public Result update(@RequestBody Rules rules) {
        validate(rules);
        com.example.project.entity.Rules old = rulesService.getById(rules.getId());
        if (old == null || !old.getName().equals(rules.getName())) throw new com.example.project.exception.ServiceException("规则名称不可修改");
        rulesService.updateById(rules);


        return Result.success();
    }

    @AutoLog("删除考勤规则")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        throw new com.example.project.exception.ServiceException("系统规则不可删除，请修改时间");
    }

    @AutoLog("批量删除考勤规则")
    @PostMapping("/del/batch")
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        throw new com.example.project.exception.ServiceException("系统规则不可删除，请修改时间");
    }

    @GetMapping
    public Result findAll() {
        return Result.success(rulesService.list());
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        return Result.success(rulesService.getById(id));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<Rules> queryWrapper = new QueryWrapper<Rules>().orderByDesc("id");
        queryWrapper.like(!"".equals(name), "name", name);

        //User user = SessionUtils.getUser();  //获取登录用户



        return Result.success(rulesService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<Rules> list = rulesService.list();
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        writer.write(list, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("Rules信息表", "UTF-8");
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
