package com.example.project.mapper;

import com.example.project.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 */
public interface UserMapper extends BaseMapper<User> {

    int select1();

    // 更新积分
    @Update("update sys_user set score = score + #{score} where id = #{userId}")
    void setScore(@Param("score") Integer score, @Param("userId") Integer userId);

}
