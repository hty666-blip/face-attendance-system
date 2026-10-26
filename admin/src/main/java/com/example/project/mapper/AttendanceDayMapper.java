package com.example.project.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.project.entity.AttendanceDay;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
public interface AttendanceDayMapper extends BaseMapper<AttendanceDay> {
    // MySQL/H2(MySQL模式)同时支持；唯一键使并发读取首次快照不会彼此覆盖。
    @Insert("INSERT IGNORE INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES(#{userId},#{businessDate},#{deptId},#{workingDay},#{policyId},#{startTime},#{endTime},#{graceMinutes},#{createdAt})")
    int insertIfAbsent(AttendanceDay day);
    @Select("SELECT * FROM attendance_day WHERE user_id=#{userId} AND business_date=#{date} FOR UPDATE")
    AttendanceDay lock(@Param("userId") Integer userId,@Param("date") LocalDate date);
}
