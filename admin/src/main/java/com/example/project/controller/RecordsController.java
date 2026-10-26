package com.example.project.controller;

import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.project.common.Result;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.security.RecordVisibility;
import com.example.project.service.IRecordsService;
import com.example.project.utils.SessionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.util.*;

/** 考勤事件只追加；修正通过补卡申请和审批保留完整事实链。 */
@RestController @RequestMapping("/records")
public class RecordsController {
    @Resource private RecordVisibility visibility;
    @Resource private IRecordsService recordsService;

    private ServiceException immutable(){return new ServiceException("409","考勤事件不可直接新增、修改或删除，请使用人脸打卡或补卡审批");}
    @PostMapping public Result save(@RequestBody Records input){throw immutable();}
    @PutMapping public Result update(@RequestBody Records input){throw immutable();}
    @DeleteMapping("/{id}") public Result delete(@PathVariable Integer id){throw immutable();}
    @PostMapping("/del/batch") public Result deleteBatch(@RequestBody List<Integer> ids){throw immutable();}
    @PostMapping("/import") public Result imp(MultipartFile file){throw immutable();}

    private QueryWrapper<Records> visibleEvents() {
        return visibility.apply(new QueryWrapper<Records>());
    }
    @GetMapping public Result findAll(){return Result.success(recordsService.list(visibleEvents().orderByDesc("id")));}
    @GetMapping("/{id}") public Result findOne(@PathVariable Integer id){return Result.success(recordsService.getOne(visibleEvents().eq("id",id)));}
    @GetMapping("/page") public Result findPage(@RequestParam(defaultValue="") String name,@RequestParam Integer pageNum,@RequestParam Integer pageSize){
        return Result.success(recordsService.page(new Page<>(pageNum,pageSize),visibleEvents().orderByDesc("id")));
    }
    @GetMapping("/export") public void export(HttpServletResponse response) throws Exception {
        List<Records> list=recordsService.list(visibleEvents().orderByDesc("id"));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8");
        response.setHeader("Content-Disposition","attachment;filename="+URLEncoder.encode("考勤事件记录","UTF-8")+".xlsx");
        try(ExcelWriter writer=ExcelUtil.getWriter(true);ServletOutputStream out=response.getOutputStream()) {
            writer.write(list,true);writer.flush(out,true);
        }
    }
}
