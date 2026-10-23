package com.example.project.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.project.entity.FaceTemplate;
import org.apache.ibatis.annotations.*;
public interface FaceTemplateMapper extends BaseMapper<FaceTemplate> {
    @Select("SELECT * FROM face_template WHERE user_id=#{userId} AND provider=#{provider} FOR UPDATE")
    FaceTemplate lock(@Param("userId") Integer userId,@Param("provider") String provider);
}
