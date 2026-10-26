package com.example.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.project.entity.AttendanceExportJob;
import org.apache.ibatis.annotations.*;

public interface AttendanceExportJobMapper extends BaseMapper<AttendanceExportJob> {
    @Select("SELECT file_content FROM attendance_export_job WHERE id=#{id} AND owner_id=#{ownerId} AND state='READY'")
    @Results(@Result(column="file_content",property="fileContent"))
    AttendanceExportJob contentRow(@Param("id") Long id,@Param("ownerId") Integer ownerId);

    default byte[] content(Long id,Integer ownerId) {
        AttendanceExportJob row=contentRow(id,ownerId);return row==null?null:row.getFileContent();
    }
}
