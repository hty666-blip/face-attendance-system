package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data @TableName("attendance_policy")
public class AttendancePolicy {
    @TableId(type=IdType.AUTO) private Long id;
    private LocalDate effectiveFrom;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer graceMinutes;
    private Integer createdBy;
    private LocalDateTime createdAt;
}
