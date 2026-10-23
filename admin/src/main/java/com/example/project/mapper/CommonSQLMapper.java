package com.example.project.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface CommonSQLMapper {

    /**
     * 通用查询
     * @return
     */
    @Select("${sql}")
    List<LinkedHashMap<String, Object>> select(Map<String, Object> map);
}
