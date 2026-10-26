package com.example.project.controller;

import com.example.project.attendance.AttendanceRequestService;
import com.example.project.common.Result;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

/** 新的日期化申请入口；旧请假记录保留原入口，不猜测迁移自由文本日期。 */
@RestController
@RequestMapping("/attendance/requests")
public class AttendanceRequestController {
    @Resource private AttendanceRequestService requests;

    @GetMapping
    public Result list(@RequestParam(defaultValue="mine") String scope,
                       @RequestParam(defaultValue="1") int pageNum,
                       @RequestParam(defaultValue="10") int pageSize) {
        return Result.success(requests.list(scope,pageNum,pageSize));
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable Long id) {return Result.success(requests.detail(id));}

    @PostMapping
    public Result create(@RequestBody AttendanceRequestService.CreateInput input) {
        return Result.success(requests.create(input));
    }

    @PostMapping("/{id}/decision")
    public Result decision(@PathVariable Long id,@RequestBody AttendanceRequestService.DecisionInput input) {
        return Result.success(requests.decide(id,input));
    }

    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable Long id,@RequestBody AttendanceRequestService.CancelInput input) {
        return Result.success(requests.cancel(id,input));
    }
}
