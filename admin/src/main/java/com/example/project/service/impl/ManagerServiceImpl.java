package com.example.project.service.impl;

import com.example.project.entity.Manager;
import com.example.project.mapper.ManagerMapper;
import com.example.project.service.IManagerService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 部门主管 服务实现类
 * </p>
 *
 * @author
 * @since 2025-01-23
 */
@Service
public class ManagerServiceImpl extends ServiceImpl<ManagerMapper, Manager> implements IManagerService {

}
