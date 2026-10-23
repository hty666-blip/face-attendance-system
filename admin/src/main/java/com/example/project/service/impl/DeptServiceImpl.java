package com.example.project.service.impl;

import com.example.project.entity.Dept;
import com.example.project.mapper.DeptMapper;
import com.example.project.service.IDeptService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 部门信息 服务实现类
 * </p>
 *
 * @author
 * @since 2025-01-23
 */
@Service
public class DeptServiceImpl extends ServiceImpl<DeptMapper, Dept> implements IDeptService {

}
