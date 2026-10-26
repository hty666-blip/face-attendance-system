package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("assistant_run")
public class AssistantRun {
    @TableId(type=IdType.INPUT) private String id;
    private Long conversationId;
    private Long turnIndex;
    private Integer ownerId;
    private String requestKey,requestHash,message,answer,state,mode,errorCode;
    private LocalDateTime createdAt,finishedAt,deadlineAt;
}
