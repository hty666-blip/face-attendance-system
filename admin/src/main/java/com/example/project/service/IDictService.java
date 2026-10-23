package com.example.project.service;

import com.example.project.entity.Dict;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IDictService extends IService<Dict> {

    List<Dict> findIcons();
}
