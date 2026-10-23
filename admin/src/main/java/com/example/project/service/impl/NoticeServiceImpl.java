package com.example.project.service.impl;

import com.example.project.entity.Notice;
import com.example.project.mapper.NoticeMapper;
import com.example.project.service.INoticeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class NoticeServiceImpl extends ServiceImpl<NoticeMapper, Notice> implements INoticeService {

    @Override
    public List<Notice> findTop10() {
        List<Notice> noticeList = query().orderByDesc("create_time").last("limit 10").list();
        return noticeList;
    }
}
