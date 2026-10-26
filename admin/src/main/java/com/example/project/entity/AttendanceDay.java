package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data @TableName("attendance_day")
public class AttendanceDay {
    @TableId(type=IdType.AUTO) private Long id;
    private Integer userId;
    private LocalDate businessDate;
    private Integer deptId;
    private Boolean workingDay;
    private Long policyId;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer graceMinutes;
    private LocalDateTime createdAt;
}
