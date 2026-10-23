package com.example.project.controller;
import com.example.project.common.Result;
import com.example.project.face.*;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.List;
@RestController @RequestMapping("/face")
public class FaceController {
    @Resource private FaceTemplateService templates;
    @Resource private FaceSessionService sessions;
    @Data public static class Enrollment {private Integer userId;private List<String> images;}
    @Data public static class Target {private Integer userId;}
    @GetMapping("/status") public Result status(@RequestParam(required=false) Integer userId){return Result.success(templates.status(userId));}
    @GetMapping("/service-status") public Result serviceStatus(){return Result.success(templates.serviceStatus());}
    @PostMapping("/enroll") public Result enroll(@RequestBody Enrollment input){templates.enroll(input.getUserId(),input.getImages());return Result.success();}
    @PostMapping("/migrate") public Result migrate(@RequestBody Target input){templates.migrate(input.getUserId());return Result.success();}
    @PostMapping("/revoke") public Result revoke(@RequestBody Target input){templates.revoke(input.getUserId());return Result.success();}
    @PostMapping("/challenge") public Result challenge(){return Result.success(sessions.create());}
}
