package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("assistant_conversation")
public class AssistantConversation {
    @TableId(type=IdType.AUTO) private Long id;
    private Integer ownerId;
    private String title;
    private LocalDateTime createdAt,updatedAt;
}
