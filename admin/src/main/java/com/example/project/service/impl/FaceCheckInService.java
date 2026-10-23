package com.example.project.service.impl;

import com.example.project.common.FaceContrastServer;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.service.*;
import com.example.project.utils.SessionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.time.*;
import java.util.*;

@Service
public class FaceCheckInService {
    @Resource private FaceContrastServer face;
    @Resource private IRecordsService records;
    @Resource private IRulesService rules;
    @Value("${face.threshold:80}") private double threshold;
    public void checkIn(String image, String address) {
        User actor = SessionUtils.getUser();
        if (actor.getFace() == null || actor.getFace().trim().isEmpty()) throw new ServiceException("请先联系管理员采集人脸");
        if (address == null || address.trim().isEmpty() || "undefined".equals(address) || address.length() > 200)
            throw new ServiceException("请等待定位成功后重试");
        Map<String,Object> comparison = face.faceContrast(actor.getFace(), image);
        if (!Integer.valueOf(200).equals(comparison.get("code")))
            throw new ServiceException("503", String.valueOf(comparison.get("error")));
        Object score = comparison.get("score");
        if (!(score instanceof Number) || !Double.isFinite(((Number)score).doubleValue()))
            throw new ServiceException("503", "识别服务返回了无效分数");
        if (((Number)score).doubleValue() <= threshold) throw new ServiceException("人脸验证未通过，请本人重试");
        Map<String,String> times = new HashMap<>();
        for (Rules rule : rules.list()) times.put(rule.getName(), rule.getTime());
        LocalTime start, end;
        try { start = LocalTime.parse(times.get("上班时间")); end = LocalTime.parse(times.get("下班时间")); }
        catch (Exception e) { throw new ServiceException("考勤规则未配置完整，请联系管理员"); }
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Shanghai"));
        String status = now.isBefore(LocalTime.NOON) ? (now.isAfter(start) ? "迟到" : "正常") : (now.isBefore(end) ? "早退" : "正常");
        Records row = new Records(); row.setUserId(actor.getId()); row.setAddress(address); row.setStateRadio(status);
        records.save(row);
    }
}
