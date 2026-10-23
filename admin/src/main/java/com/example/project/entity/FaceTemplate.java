package com.example.project.entity;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
@Data @TableName("face_template")
public class FaceTemplate {
    @TableId(type=IdType.AUTO) private Long id;
    private Integer userId;
    private String provider;
    private String modelVersion;
    @JsonIgnore private String encryptedPayload;
    private Integer version;
    private Boolean active;
    private Integer updatedBy;
    private LocalDateTime updatedAt;
    public String context(){return userId+":"+provider+":"+modelVersion+":"+version;}
}
