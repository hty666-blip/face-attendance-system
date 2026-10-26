package com.example.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.project.entity.AttendanceRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AttendanceRequestMapper extends BaseMapper<AttendanceRequest> {
    @Select("SELECT * FROM attendance_request WHERE id=#{id} FOR UPDATE")
    AttendanceRequest lock(@Param("id") Long id);
}
