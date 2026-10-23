package com.example.project.entity;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
@Data @TableName("face_checkin_session")
public class FaceCheckInSession {
    @TableId(type=IdType.INPUT) private String id;
    private Integer userId;
    private String provider;
    private Integer templateVersion;
    private LocalDateTime expiresAt;
    private String state;
    private String requestHash;
    private Integer recordId;
    private LocalDateTime createdAt;
}
