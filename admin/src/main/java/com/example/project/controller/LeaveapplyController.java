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
import com.example.project.service.ILeaveapplyService;
import com.example.project.entity.Leaveapply;

import org.springframework.web.bind.annotation.RestController;

/**
* <p>
* 请假申请 前端控制器
* </p>
*
* @author wx:ericxu1116
* @since 2025-01-23
*/
@RestController
@RequestMapping("/leaveapply")
public class LeaveapplyController {
    @Resource private com.example.project.security.AccessPolicy accessPolicy;

    @Resource
    private ILeaveapplyService leaveapplyService;
    @Resource
    private IDeptService deptService;
    @Resource
    private IEmployeeService employeeService;

    @AutoLog("新增请假申请")
    @PostMapping
    public Result save(@RequestBody Leaveapply input) {
        throw new com.example.project.exception.ServiceException("409", "请在考勤工作台提交带起止日期的请假申请");
    }
    private String clean(String text) {
        return org.jsoup.Jsoup.clean(text == null ? "" : text, org.jsoup.safety.Safelist.basicWithImages());
    }
    private void validate(Leaveapply row) {
        if (!java.util.Arrays.asList("事假","病假","婚假","丧假").contains(row.getTypeRadio())
                || row.getPeriods() == null || row.getPeriods().trim().isEmpty() || row.getPeriods().length() > 200)
            throw new com.example.project.exception.ServiceException("请填写有效的请假类型和时长");
    }
    private Leaveapply requireRow(Integer id) {
        Leaveapply row = leaveapplyService.getById(id);
        if (row == null) throw new com.example.project.exception.ServiceException("申请不存在");
        accessPolicy.requireUser(row.getUserId()); return row;
    }

    @AutoLog("编辑请假申请")
    @PutMapping
    public Result update(@RequestBody Leaveapply input) {
        Leaveapply old = requireRow(input.getId());
        if (!"审核中".equals(old.getStateRadio())) throw new com.example.project.exception.ServiceException("已审核的申请不能直接修改");
        Leaveapply patch = new Leaveapply(); patch.setId(old.getId());
        User actor = SessionUtils.getUser();
        if ("employee".equals(actor.getRole())) {
            throw new com.example.project.exception.ServiceException("409", "旧申请缺少可靠日期，请撤回后在考勤工作台重新提交");
        } else {
            if (actor.getId().equals(old.getUserId())) throw new com.example.project.exception.ServiceException("不能审核本人申请");
            if (!java.util.Arrays.asList("通过","不通过").contains(input.getStateRadio())) throw new com.example.project.exception.ServiceException("请选择审核结果");
            patch.setStateRadio(input.getStateRadio()); patch.setRemarks(input.getRemarks());
        }
        boolean ok = leaveapplyService.update(patch, new UpdateWrapper<Leaveapply>().eq("id",old.getId()).eq("state_radio","审核中"));
        if (!ok) throw new com.example.project.exception.ServiceException("申请状态已变更，请刷新");
        return Result.success();
    }

    @AutoLog("删除请假申请")
    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public Result delete(@PathVariable Integer id) {
        return deleteBatch(java.util.Collections.singletonList(id));
    }

    @AutoLog("批量删除请假申请")
    @PostMapping("/del/batch")
    @org.springframework.transaction.annotation.Transactional
    public Result deleteBatch(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw new com.example.project.exception.ServiceException("请选择1至100条记录");
        for (Integer id : ids) {
            Leaveapply row = requireRow(id);
            if (!accessPolicy.isAdmin()) accessPolicy.requireSelf(row.getUserId());
            if (!"审核中".equals(row.getStateRadio())) throw new com.example.project.exception.ServiceException("不能删除已审核申请");
            if (!leaveapplyService.remove(new QueryWrapper<Leaveapply>().eq("id",id).eq("state_radio","审核中")))
                throw new com.example.project.exception.ServiceException("申请状态已变更，请刷新");
        }
        return Result.success();
    }

    @GetMapping
    public Result findAll() {
        return Result.success(leaveapplyService.list(accessPolicy.users(new QueryWrapper<Leaveapply>())));
    }

    @GetMapping("/{id}")
    public Result findOne(@PathVariable Integer id) {
        return Result.success(leaveapplyService.getOne(accessPolicy.users(new QueryWrapper<Leaveapply>().eq("id", id))));
    }

    @GetMapping("/page")
    public Result findPage(@RequestParam(defaultValue = "") String name,
                           @RequestParam Integer pageNum,
                           @RequestParam Integer pageSize) {
        QueryWrapper<Leaveapply> queryWrapper = accessPolicy.users(new QueryWrapper<Leaveapply>()).orderByDesc("id");
        return Result.success(leaveapplyService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    /**
    * 导出接口
    */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        // 从数据库查询出所有的数据
        List<Leaveapply> list = leaveapplyService.list(accessPolicy.users(new QueryWrapper<Leaveapply>()));
        // 在内存操作，写出到浏览器
        ExcelWriter writer = ExcelUtil.getWriter(true);

        // 一次性写出list内的对象到excel，使用默认样式，强制输出标题
        writer.write(list, true);

        // 设置浏览器响应的格式
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        String fileName = URLEncoder.encode("Leaveapply信息表", "UTF-8");
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
        throw new com.example.project.exception.ServiceException("请假申请须通过提交与审核流程创建，暂不允许导入");
    }



}
