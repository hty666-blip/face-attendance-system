package com.example.project.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("attendance_request")
public class AttendanceRequest {
    @TableId(type=IdType.AUTO) private Long id;
    private Integer userId;
    private Integer deptId;
    private String requestKey;
    @JsonIgnore private String requestHash;
    private String kind;
    private LocalDate businessDate;
    private String action;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime correctionTime;
    private LocalDate startDate;
    private String startHalf;
    private LocalDate endDate;
    private String endHalf;
    private String leaveType;
    private String reason;
    private String state;
    private Integer version;
    private Integer decisionBy;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime decisionAt;
    private String decisionComment;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAt;
    @TableField(exist=false) private String applicantName;
    /** 服务端按当前有效日历和半日边界重算的预计工作日数，不接受客户端赋值。 */
    @TableField(exist=false) private Double leaveDays;
}
