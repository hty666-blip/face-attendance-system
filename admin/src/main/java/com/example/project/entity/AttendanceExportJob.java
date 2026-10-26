package com.example.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

/** 文件与任务结果存储在同一行；所有 HTTP 返回均由服务层白名单投影。 */
@Data
@TableName("attendance_export_job")
public class AttendanceExportJob {
    @TableId(type=IdType.AUTO) private Long id;
    @JsonIgnore private Integer ownerId;
    @JsonIgnore private String requestKey;
    @JsonIgnore private String requestHash;
    private String month;
    private String scope;
    private Integer deptId;
    @JsonIgnore private String scopeSignature;
    @JsonIgnore private String accessSignature;
    private String state;
    private Integer attempts;
    @JsonIgnore private String leaseToken;
    @JsonIgnore private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime expiresAt;
    private String errorCode;
    private String errorMessage;
    @JsonIgnore @TableField(select=false) private byte[] fileContent;
}
