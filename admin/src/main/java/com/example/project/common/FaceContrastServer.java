package com.example.project.common;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.*;
import com.tencentcloudapi.iai.v20200303.IaiClient;
import com.tencentcloudapi.iai.v20200303.models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
@Component @Slf4j
public class FaceContrastServer {
    @Value("${face.enabled:false}") private boolean enabled;
    @Value("${face.secret-id:}") private String secretId;
    @Value("${face.secret-key:}") private String secretKey;
    @Value("${face.region:ap-shanghai}") private String region;
    public Map<String,Object> faceContrast(String imageA, String imageB) {
        Map<String,Object> result = new HashMap<>(); result.put("code", 500);
        if (!enabled || secretId.isEmpty() || secretKey.isEmpty()) {
            result.put("error", "人脸服务未启用，请联系管理员配置"); return result;
        }
        try {
            HttpProfile http = new HttpProfile(); http.setEndpoint("iai.tencentcloudapi.com"); http.setConnTimeout(5); http.setReadTimeout(10);
            ClientProfile profile = new ClientProfile(); profile.setHttpProfile(http);
            IaiClient client = new IaiClient(new Credential(secretId, secretKey), region, profile);
            CompareFaceRequest request = new CompareFaceRequest();
            request.setFaceModelVersion("3.0");
            request.setImageA(normalize(imageA)); request.setImageB(normalize(imageB));
            CompareFaceResponse response = client.CompareFace(request);
            result.put("score", response.getScore()); result.put("code",200);
        } catch (IllegalArgumentException e) {
            result.put("error", "图片格式无效，请重新采集");
        } catch (TencentCloudSDKException e) {
            log.warn("face provider failure code={} requestId={}", e.getErrorCode(), e.getRequestId());
            result.put("error", "人脸识别服务暂不可用，错误码：" + e.getErrorCode());
        }
        return result;
    }
    public static String normalize(String image) {
        if (image == null || image.length() > 7_000_000) throw new IllegalArgumentException("image");
        String raw = image.startsWith("data:") ? image.substring(image.indexOf(',') + 1) : image;
        byte[] bytes = Base64.getDecoder().decode(raw);
        if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("image size");
        return raw;
    }
}
