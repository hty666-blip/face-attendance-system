package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("assistant_tool_call")
public class AssistantToolCall {
    @TableId(type=IdType.AUTO) private Long id;
    private String runId;
    private Integer callOrder;
    private String toolName,argumentsJson,state,resultJson,errorCode;
    private LocalDateTime createdAt;
}
