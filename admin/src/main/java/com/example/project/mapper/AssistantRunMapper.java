package com.example.project.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.project.entity.AssistantRun;
import org.apache.ibatis.annotations.*;
public interface AssistantRunMapper extends BaseMapper<AssistantRun> {
    @Select("SELECT COALESCE(MAX(turn_index),0)+1 FROM assistant_run WHERE conversation_id=#{conversationId}")
    Long nextTurnIndex(@Param("conversationId") Long conversationId);
    @Select("SELECT * FROM assistant_run WHERE id=#{id} FOR UPDATE")
    AssistantRun lock(@Param("id") String id);
}
