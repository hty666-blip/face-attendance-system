package com.example.project.controller;

import com.example.project.common.Result;
import com.example.project.report.*;
import com.example.project.utils.SessionUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

@RestController @RequestMapping("/attendance/reports")
public class AttendanceReportController {
    @Resource private MonthlyReportService reports;
    @Resource private ReportExportService exports;
    @GetMapping("/preview")
    public Result preview(@RequestParam String month,@RequestParam String scope,@RequestParam(required=false) Integer deptId) {
        return Result.success(reports.build(SessionUtils.getUser().getId(),month,scope,deptId,false));
    }
    @GetMapping("/exports")
    public Result list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {return Result.success(exports.list(pageNum,pageSize));}
    @PostMapping("/exports") public Result create(@RequestBody ReportExportService.CreateInput input) {return Result.success(exports.create(input));}
    @GetMapping("/exports/{id}") public Result detail(@PathVariable Long id) {return Result.success(exports.detail(id));}
    @PostMapping("/exports/{id}/cancel") public Result cancel(@PathVariable Long id) {return Result.success(exports.cancel(id));}
    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        byte[] bytes=exports.download(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance-report-"+id+".xlsx")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);
    }
}
