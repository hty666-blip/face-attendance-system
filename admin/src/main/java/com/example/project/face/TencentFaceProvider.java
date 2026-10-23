package com.example.project.face;
import com.example.project.common.FaceContrastServer;
import com.example.project.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.util.*;
@Component
public class TencentFaceProvider implements FaceProvider {
    @Resource private FaceContrastServer cloud;
    @Value("${face.threshold:80}") private double threshold;
    @Value("${face.secret-id:}") private String secretId;
    @Value("${face.secret-key:}") private String secretKey;
    public String id(){return "tencent";}
    public String modelVersion(){return "tencent-3.0";}
    public double threshold(){return threshold;}
    @PostConstruct void validateConfiguration() {
        if(!Double.isFinite(threshold) || threshold<0 || threshold>100)
            throw new IllegalArgumentException("腾讯云人脸阈值须为0至100");
    }
    private double score(Map<String,Object> result) {
        if(!Integer.valueOf(200).equals(result.get("code"))) throw new ServiceException("503","腾讯云人脸服务不可用，请检查配置与账户状态");
        Object value=result.get("score");
        if(!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue()) || ((Number)value).doubleValue()<0 || ((Number)value).doubleValue()>100)
            throw new ServiceException("503","腾讯云返回无效分数");
        return ((Number)value).doubleValue();
    }
    public String enroll(List<String> images) {
        if(images==null || images.isEmpty() || images.size()>3) throw new ServiceException("422","请采集一至三张本人照片");
        String image;
        try {image=FaceContrastServer.normalize(images.get(0));}
        catch(IllegalArgumentException error) {throw new ServiceException("422","图片格式无效，请重新采集");}
        for(String sample:images) {
            if(score(cloud.faceContrast(image,sample))<threshold)
                throw new ServiceException("422","采集照片不是同一人或不清晰，请重新采集");
        }
        return image;
    }
    public double compare(String payload,String image){return score(cloud.faceContrast(payload,image));}
    public boolean ready(){return !secretId.isEmpty() && !secretKey.isEmpty();}
}
