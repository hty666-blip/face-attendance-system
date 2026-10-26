package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;
@Data @TableName("attendance_calendar")
public class AttendanceCalendar {
    @TableId(type=IdType.INPUT) private LocalDate date;
    private Boolean workingDay;
    private String note;
    private Integer updatedBy;
    private LocalDateTime updatedAt;
}
