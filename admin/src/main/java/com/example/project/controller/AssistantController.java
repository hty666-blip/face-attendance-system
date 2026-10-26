package com.example.project.controller;
import com.example.project.assistant.AssistantService;
import com.example.project.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
@RestController @RequestMapping("/assistant")
public class AssistantController {
    @Resource private AssistantService assistant;
    @GetMapping("/status") public Result status(){return Result.success(assistant.status());}
    @GetMapping("/conversations") public Result conversations(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return Result.success(assistant.conversations(pageNum,pageSize));}
    @PostMapping("/conversations") public Result create(@RequestBody JsonNode input){return Result.success(assistant.createConversation(input));}
    @GetMapping("/conversations/{id}") public Result conversation(@PathVariable Long id){return Result.success(assistant.conversation(id));}
    @PostMapping("/runs") public Result run(@RequestBody JsonNode input){return Result.success(assistant.run(input));}
    @GetMapping("/runs/by-request") public Result byRequest(@RequestParam String requestKey){return Result.success(assistant.byRequest(requestKey));}
    @GetMapping("/runs/{id}") public Result detail(@PathVariable String id){return Result.success(assistant.detail(id));}
}
