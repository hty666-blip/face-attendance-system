package com.example.project.controller;
import com.example.project.common.Result;
import com.example.project.entity.*;
import com.example.project.service.*;
import com.example.project.security.AccessPolicy;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
@RestController @RequestMapping("/statistics")
public class StatisticsController {
    @Resource private AccessPolicy policy;
    @Resource private com.example.project.security.RecordVisibility recordVisibility;
    @Resource private IRecordsService records;
    @Resource private ILeaveapplyService leaves;
    @GetMapping("/leaveStatics") public Result leaveStatics() {
        return Result.success(leaves.listMaps(policy.users(new QueryWrapper<Leaveapply>())
                .select("type_radio AS name", "COUNT(id) AS `value`").groupBy("type_radio")));
    }
    @GetMapping("/recordsStatics") public Result recordsStatics() {
        return Result.success(records.listMaps(recordVisibility.apply(new QueryWrapper<Records>())
                .select("state_radio AS name", "COUNT(id) AS `value`").groupBy("state_radio")));
    }
}
