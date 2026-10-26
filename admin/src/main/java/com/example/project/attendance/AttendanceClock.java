package com.example.project.attendance;

import org.springframework.stereotype.Component;
import java.time.*;

/** 所有新考勤业务共用上海时钟，可在测试中固定 now()。 */
@Component
public class AttendanceClock {
    public LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);}
    public LocalDate today(){return now().toLocalDate();}
}
