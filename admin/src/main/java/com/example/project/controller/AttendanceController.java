package com.example.project.controller;
import com.example.project.attendance.*;
import com.example.project.common.Result;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.time.*;

@RestController @RequestMapping("/attendance")
public class AttendanceController {
    @Resource private AttendanceService attendance;
    @Resource private AttendancePolicyService policies;
    @Data public static class PolicyInput {private LocalDate effectiveFrom;private LocalTime startTime;private LocalTime endTime;private Integer graceMinutes;}
    @Data public static class CalendarInput {private LocalDate date;private Boolean workingDay;private String note;}
    @GetMapping("/today") public Result today(){return Result.success(attendance.today());}
    @GetMapping("/month") public Result month(@RequestParam String month,@RequestParam(required=false) Integer userId){return Result.success(attendance.month(month,userId));}
    @GetMapping("/policies") public Result policies(){return Result.success(policies.list());}
    @PostMapping("/policies") public Result appendPolicy(@RequestBody PolicyInput input){return Result.success(policies.append(input.getEffectiveFrom(),input.getStartTime(),input.getEndTime(),input.getGraceMinutes()));}
    @GetMapping("/calendar") public Result calendar(@RequestParam String month){return Result.success(policies.calendar(month));}
    @PostMapping("/calendar") public Result configureDay(@RequestBody CalendarInput input){return Result.success(policies.configureDay(input.getDate(),input.getWorkingDay(),input.getNote()));}
}
