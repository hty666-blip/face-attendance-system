package com.example.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("attendance_approval")
public class AttendanceApproval {
    @TableId(type=IdType.AUTO) private Long id;
    private Long requestId;
    private Integer actorId;
    private String fromState;
    private String toState;
    private String comment;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAt;
    @TableField(exist=false) private String actorName;
}
