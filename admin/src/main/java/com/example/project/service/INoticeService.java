package com.example.project.service;

import com.example.project.entity.Notice;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface INoticeService extends IService<Notice> {

    //查找前面10条公告
    List<Notice> findTop10();
}
