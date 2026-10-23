package com.example.project.face;
import com.example.project.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
@Component
public class FaceProviders {
    @Resource private LocalFaceProvider local;
    @Resource private TencentFaceProvider tencent;
    @Value("${face.enabled:true}") private boolean enabled;
    @Value("${face.provider:local}") private String selected;
    public FaceProvider current() {
        if(!enabled) throw new ServiceException("503","人脸服务已关闭，请联系管理员");
        return configured();
    }
    public boolean isEnabled(){return enabled;}
    public FaceProvider configured() {
        if("local".equals(selected)) return local;
        if("tencent".equals(selected)) return tencent;
        throw new ServiceException("503","人脸识别提供方配置无效");
    }
}
