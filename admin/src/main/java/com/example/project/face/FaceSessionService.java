package com.example.project.face;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.service.*;
import com.example.project.utils.SessionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class FaceSessionService {
    @Resource private FaceProviders providers;
    @Resource private FaceTemplateService templateService;
    @Resource private FaceTemplateMapper templates;
    @Resource private FaceCheckInSessionMapper sessions;
    @Resource private UserMapper userMapper;
    @Resource private IRecordsService records;
    @Resource private IRulesService rules;
    @Resource private TransactionTemplate tx;
    public static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
    public Map<String,Object> create() {
        User actor=SessionUtils.getUser();FaceProvider provider=providers.current();
        return tx.execute(status -> {
            // 锁定稳定存在的用户行，使计数与插入在并发创建时仍保证每人最多三个有效会话。
            if(userMapper.lockActiveId(actor.getId())==null) throw new ServiceException("401","账号已停用，请重新登录");
            cn.dev33.satoken.stp.StpUtil.checkPermission("records.add");
            FaceTemplate template=templateService.requireActive(actor.getId(),provider);
            LocalDateTime created=now();
            // 只清理本人30天前的会话；考勤记录不会随会话清理而删除。
            sessions.delete(new QueryWrapper<FaceCheckInSession>().eq("user_id",actor.getId()).lt("created_at",created.minusDays(30)));
            if(sessions.selectCount(new QueryWrapper<FaceCheckInSession>().eq("user_id",actor.getId())
                    .in("state",Arrays.asList("PENDING","PROCESSING")).gt("expires_at",created))>=3)
                throw new ServiceException("429","已有多个有效打卡会话，请稍后再试");
            FaceCheckInSession row=new FaceCheckInSession();row.setId(UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
            row.setUserId(actor.getId());row.setProvider(provider.id());row.setTemplateVersion(template.getVersion());
            row.setCreatedAt(created);row.setExpiresAt(created.plusMinutes(2));row.setState("PENDING");
            if(sessions.insert(row)!=1) throw new ServiceException("打卡会话创建失败，请稍后重试");
            Map<String,Object> result=new LinkedHashMap<>();result.put("challengeId",row.getId());result.put("expiresAt",row.getExpiresAt());
            result.put("provider",provider.id());return result;
        });
    }
    public Map<String,Object> verify(String challengeId,String image,String address) {
        User actor=SessionUtils.getUser();FaceProvider provider=providers.current();
        if(challengeId==null || !challengeId.matches("[a-f0-9]{64}")) throw new ServiceException("409","请重新打开打卡窗口获取会话");
        if(image==null || image.isEmpty() || image.length()>7_000_000) throw new ServiceException("图片为空或过大，请重新拍摄");
        if(address==null || address.trim().isEmpty() || "undefined".equals(address) || address.length()>200)
            throw new ServiceException("请等待定位成功后重试");
        FaceCheckInSession attempt=sessions.selectById(challengeId);
        if(attempt==null || !actor.getId().equals(attempt.getUserId()) || !provider.id().equals(attempt.getProvider()))
            throw new ServiceException("403","打卡会话不属于当前用户或识别方式");
        String fingerprint=fingerprint(image,address);
        if("SUCCEEDED".equals(attempt.getState())) {
            if(!fingerprint.equals(attempt.getRequestHash())) throw new ServiceException("409","会话已使用，请重新打开打卡窗口");
            return result(attempt.getRecordId(),true);
        }
        if(!attempt.getExpiresAt().isAfter(now())) throw new ServiceException("409","打卡会话已过期，请重新打开打卡窗口");
        FaceTemplate template=templateService.requireActive(actor.getId(),provider);
        if(!template.getVersion().equals(attempt.getTemplateVersion())) throw new ServiceException("409","人脸模板已更新，请重新打开打卡窗口");
        int reserved=sessions.update(null,new UpdateWrapper<FaceCheckInSession>().eq("id",challengeId).eq("state","PENDING")
                .gt("expires_at",now()).set("state","PROCESSING").set("request_hash",fingerprint));
        if(reserved!=1) {
            FaceCheckInSession latest=sessions.selectById(challengeId);
            if(latest!=null && "PROCESSING".equals(latest.getState()))
                throw new ServiceException("425","本次验证仍在处理，请稍后重试本次验证");
            if(latest!=null && "SUCCEEDED".equals(latest.getState()) && fingerprint.equals(latest.getRequestHash()))
                return result(latest.getRecordId(),true);
            throw new ServiceException("409","会话已失效，请检查记录后重新打开打卡窗口");
        }
        try {
            double score=provider.compare(templateService.decrypt(template),image);
            if(!Double.isFinite(score) || score<provider.threshold()) throw new ServiceException("422","人脸验证未通过，请本人重试");
            return tx.execute(status -> {
                if(userMapper.lockActiveId(actor.getId())==null) throw new ServiceException("401","账号已停用，请重新登录");
                User current=SessionUtils.getUser(); // 验证完成后再次检查账号与权限。
                cn.dev33.satoken.stp.StpUtil.checkPermission("records.add");
                FaceTemplate locked=templates.lock(current.getId(),provider.id());
                if(locked==null || !Boolean.TRUE.equals(locked.getActive()) || !locked.getVersion().equals(attempt.getTemplateVersion()))
                    throw new ServiceException("409","人脸模板已撤销或更新，请重新打开打卡窗口");
                if(!attempt.getExpiresAt().isAfter(now())) throw new ServiceException("409","打卡会话已过期，请重新打开窗口");
                Records row=new Records();row.setUserId(current.getId());row.setAddress(address);row.setStateRadio(attendanceState());
                if(!records.save(row) || row.getId()==null) throw new ServiceException("考勤保存失败，请稍后重试");
                int changed=sessions.update(null,new UpdateWrapper<FaceCheckInSession>().eq("id",challengeId).eq("state","PROCESSING")
                        .eq("request_hash",fingerprint).set("state","SUCCEEDED").set("record_id",row.getId()));
                if(changed!=1) throw new ServiceException("409","打卡状态冲突，请先检查考勤记录");
                return result(row.getId(),false);
            });
        } catch(RuntimeException error) {
            try {
                sessions.update(null,new UpdateWrapper<FaceCheckInSession>().eq("id",challengeId).eq("state","PROCESSING").set("state","FAILED"));
            } catch(RuntimeException cleanupError) {error.addSuppressed(cleanupError);}
            throw error;
        }
    }
    private String attendanceState() {
        Map<String,String> times=new HashMap<>();for(Rules rule:rules.list()) times.put(rule.getName(),rule.getTime());
        LocalTime start,end;
        try{start=LocalTime.parse(times.get("上班时间"));end=LocalTime.parse(times.get("下班时间"));}
        catch(Exception error){throw new ServiceException("考勤规则未配置完整，请联系管理员");}
        LocalTime current=now().toLocalTime();
        return current.isBefore(LocalTime.NOON)?(current.isAfter(start)?"迟到":"正常"):(current.isBefore(end)?"早退":"正常");
    }
    private Map<String,Object> result(Integer recordId,boolean replay) {
        if(recordId==null) throw new ServiceException("409","打卡状态异常，请先检查考勤记录");
        Map<String,Object> result=new LinkedHashMap<>();result.put("recordId",recordId);result.put("alreadyCompleted",replay);return result;
    }
    private String fingerprint(String image,String address) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(image.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            return Base64.getEncoder().encodeToString(digest.digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception error){throw new IllegalStateException(error);}
    }
}
