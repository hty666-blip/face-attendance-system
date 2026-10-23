package com.example.project.face;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.FaceTemplateMapper;
import com.example.project.mapper.UserMapper;
import com.example.project.mapper.FaceCheckInSessionMapper;
import com.example.project.security.AccessPolicy;
import com.example.project.service.IUserService;
import com.example.project.utils.SessionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.*;
import java.util.*;

@Service
public class FaceTemplateService {
    @Resource private FaceProviders providers;
    @Resource private TemplateCrypto crypto;
    @Resource private FaceTemplateMapper templates;
    @Resource private IUserService users;
    @Resource private UserMapper userMapper;
    @Resource private FaceCheckInSessionMapper sessions;
    @Resource private AccessPolicy policy;
    @Resource private TransactionTemplate tx;
    public FaceTemplate requireActive(Integer userId,FaceProvider provider) {
        FaceTemplate template=templates.selectOne(new QueryWrapper<FaceTemplate>().eq("user_id",userId).eq("provider",provider.id()));
        if(template==null || !Boolean.TRUE.equals(template.getActive())) throw new ServiceException("409","尚未录入当前识别方式的人脸，请联系管理员采集或迁移");
        if(!provider.modelVersion().equals(template.getModelVersion())) throw new ServiceException("409","模板模型版本已变更，请重新录入");
        return template;
    }
    public Map<String,Object> status(Integer userId) {
        User actor=SessionUtils.getUser(); int target=userId==null?actor.getId():userId;
        if(!Objects.equals(actor.getId(),target)) {policy.adminOnly();StpUtil.checkPermission("employee.edit");}
        User user=users.getById(target); if(user==null) throw new ServiceException("用户不存在");
        FaceProvider provider=providers.configured();
        FaceTemplate template=templates.selectOne(new QueryWrapper<FaceTemplate>().eq("user_id",target).eq("provider",provider.id()));
        Map<String,Object> result=new LinkedHashMap<>();result.put("userId",target);result.put("provider",provider.id());
        result.put("modelVersion",provider.modelVersion());result.put("legacyAvailable",user.getFace()!=null && !user.getFace().isEmpty());
        result.put("revocable",(user.getFace()!=null && !user.getFace().isEmpty()) ||
                templates.selectCount(new QueryWrapper<FaceTemplate>().eq("user_id",target).eq("active",true))>0);
        boolean active=template!=null && Boolean.TRUE.equals(template.getActive()) && provider.modelVersion().equals(template.getModelVersion());
        result.put("enrolled",active);result.put("version",template==null?0:template.getVersion());
        result.put("updatedAt",template==null?null:template.getUpdatedAt());return result;
    }
    public Map<String,Object> serviceStatus() {
        FaceProvider provider=providers.configured();Map<String,Object> result=new LinkedHashMap<>();
        result.put("provider",provider.id());result.put("ready",providers.isEnabled() && crypto.configured() && provider.ready());
        result.put("modelVersion",provider.modelVersion());result.put("livenessSupported",false);return result;
    }
    public void enroll(Integer userId,List<String> images) {
        authorize();
        if(images==null || images.size()!=3) throw new ServiceException("请采集三张本人清晰照片");
        save(userId,images);
    }
    public void migrate(Integer userId) {
        authorize();requireTarget(userId);User user=users.getById(userId);
        if(user==null || user.getFace()==null || user.getFace().isEmpty()) throw new ServiceException("没有可以迁移的旧照片");
        save(userId,Collections.singletonList(user.getFace()));
    }
    private void authorize(){policy.adminOnly();StpUtil.checkPermission("employee.edit");}
    private void requireTarget(Integer userId) {
        if(userId==null || userId<=0 || users.getById(userId)==null) throw new ServiceException("用户不存在或已停用");
    }
    private void save(Integer userId,List<String> images) {
        requireTarget(userId);
        if(!crypto.configured()) throw new ServiceException("503","人脸模板加密密钥未配置，请运行启动脚本");
        for(String image:images) {
            if(image==null || image.isEmpty() || image.length()>7_000_000) throw new ServiceException("图片为空或过大");
        }
        FaceProvider provider=providers.current();String payload=provider.enroll(images);
        tx.executeWithoutResult(status -> {
            authorize(); // 网络推理期间权限可能发生变更。
            // 同一用户的首次录入、更新和撤销串行，避免尚无模板行时的并发插入。
            if(userMapper.lockActiveId(userId)==null) throw new ServiceException("用户已停用");
            FaceTemplate old=templates.lock(userId,provider.id());
            FaceTemplate row=old==null?new FaceTemplate():old;
            row.setUserId(userId);row.setProvider(provider.id());row.setModelVersion(provider.modelVersion());
            row.setVersion(old==null?1:old.getVersion()+1);row.setActive(true);
            row.setUpdatedBy(SessionUtils.getUser().getId());row.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            row.setEncryptedPayload(crypto.encrypt(payload,row.context()));
            int changed=old==null?templates.insert(row):templates.updateById(row);
            if(changed!=1) throw new ServiceException("人脸模板保存失败，请稍后重试");
            invalidateSessions(userId,provider.id());
            // 仅在加密模板写入成功的同一事务中清除该用户旧明文照片。
            if(!users.update(new UpdateWrapper<User>().eq("id",userId).set("face",null)))
                throw new ServiceException("旧照片清理失败，请稍后重试");
        });
    }
    public void revoke(Integer userId) {
        authorize();requireTarget(userId);
        tx.executeWithoutResult(status -> {
            authorize();
            if(userMapper.lockActiveId(userId)==null) throw new ServiceException("用户已停用");
            revokeLocked(userId);
        });
    }
    /** 账户生命周期内部入口；与账号逻辑删除加入同一事务，不依赖识别服务是否启用。 */
    public void purgeForDeactivation(Integer userId) {
        tx.executeWithoutResult(status -> {
            if(userId==null || userMapper.lockActiveId(userId)==null) throw new ServiceException("账号不存在或已停用");
            revokeLocked(userId);
        });
    }
    private void revokeLocked(Integer userId) {
        // 清除全部 Provider，避免日后切回另一识别方式时恢复已撤销的旧人脸。
        for(FaceTemplate row:templates.selectList(new QueryWrapper<FaceTemplate>().eq("user_id",userId))) {
            if(templates.update(null,new UpdateWrapper<FaceTemplate>().eq("id",row.getId())
                    .set("encrypted_payload",null).set("active",false).set("version",row.getVersion()+1)
                    .set("updated_by",SessionUtils.getUser().getId()).set("updated_at",LocalDateTime.now(ZoneId.of("Asia/Shanghai"))))!=1)
                throw new ServiceException("人脸撤销失败，请稍后重试");
        }
        invalidateSessions(userId,null);
        if(!users.update(new UpdateWrapper<User>().eq("id",userId).set("face",null)))
            throw new ServiceException("旧照片清理失败，请稍后重试");
    }
    private void invalidateSessions(Integer userId,String provider) {
        UpdateWrapper<FaceCheckInSession> update=new UpdateWrapper<FaceCheckInSession>().eq("user_id",userId)
                .in("state",Arrays.asList("PENDING","PROCESSING")).set("state","INVALIDATED");
        if(provider!=null) update.eq("provider",provider);
        sessions.update(null,update);
    }
    public String decrypt(FaceTemplate template){return crypto.decrypt(template.getEncryptedPayload(),template.context());}
}
