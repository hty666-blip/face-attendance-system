package com.example.project.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.project.entity.*;
import com.example.project.utils.SessionUtils;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;

/** 原始事件的列表、详情、导出和状态分布共用当前人员与历史部门的交集范围。 */
@Component
public class RecordVisibility {
    @Resource private AccessPolicy access;
    public QueryWrapper<Records> apply(QueryWrapper<Records> query) {
        User actor=SessionUtils.getUser();
        if("ADMIN".equals(actor.getRole())) return query;
        if(!"manager".equals(actor.getRole())) return query.eq("user_id",actor.getId());
        List<Integer> others=new ArrayList<>(access.visibleUserIds());others.remove(actor.getId());
        List<Integer> managed=access.departmentIds();
        return query.and(q -> {
            q.eq("user_id",actor.getId());
            if(!others.isEmpty() && !managed.isEmpty()) q.or(other -> other.in("user_id",others).in("dept_id",managed));
        });
    }
}
