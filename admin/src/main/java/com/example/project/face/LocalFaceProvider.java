package com.example.project.face;

import com.example.project.exception.ServiceException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.time.Duration;
import java.util.*;

@Component
public class LocalFaceProvider implements FaceProvider {
    public static final String MODEL = "sface-2021dec-yunet-2023mar-v1";
    private final RestTemplate client;
    private final ObjectMapper json;
    private final String url, token;
    private final double threshold;
    public LocalFaceProvider(RestTemplateBuilder builder, ObjectMapper json,
            @Value("${face.local.url:http://127.0.0.1:8010}") String url,
            @Value("${face.local.token:}") String token,
            @Value("${face.local.threshold:0.45}") double threshold) {
        this.client = builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(12)).build();
        this.json = json; this.url = url.replaceAll("/$", ""); this.token = token; this.threshold = threshold;
        if (threshold < -1 || threshold > 1 || !Double.isFinite(threshold)) throw new IllegalArgumentException("本地人脸阈值须为-1至1");
        if (!this.url.matches("https?://[^/]+(?::\\d+)?")) throw new IllegalArgumentException("本地人脸服务地址应只包含协议与主机端口");
    }
    public String id() {return "local";}
    public String modelVersion() {return MODEL;}
    public double threshold() {return threshold;}
    private JsonNode request(String path, Map<String,Object> body) {
        if (token.length() < 32) throw new ServiceException("503", "本地人脸服务令牌未配置，请运行人脸服务启动脚本");
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Face-Service-Token",token);
        try {
            String response = client.postForObject(url+path,new HttpEntity<>(body,headers),String.class);
            JsonNode result=json.readTree(response);
            if (!MODEL.equals(result.path("modelVersion").asText())) throw new ServiceException("503","本地模型版本不匹配，请检查模型与服务版本");
            return result;
        } catch(HttpStatusCodeException error) {
            String code = "503", message = "本地识别服务暂不可用，请稍后重试";
            try {
                JsonNode failure=json.readTree(error.getResponseBodyAsString());
                String reason=failure.path("code").asText();
                // 错误消息使用固定映射，避免把外部响应中的敏感信息传给浏览器。
                Map<String,String> messages=new HashMap<>();
                messages.put("NO_FACE","未检测到人脸，请正对摄像头并改善光线");
                messages.put("MULTIPLE_FACES","画面中有多张人脸，请保持本人单独入镜");
                messages.put("FACE_TOO_SMALL","请靠近摄像头，让完整面部出现在画面中");
                messages.put("BAD_LIGHTING","面部光线过暗或过亮，请调整光线后重拍");
                messages.put("BLURRY_FACE","面部不清晰，请保持稳定后重拍");
                messages.put("INCONSISTENT_SAMPLES","三张照片差异过大，请由同一人重新采集");
                messages.put("INVALID_IMAGE","图片损坏或尺寸不合适，请重新拍摄");
                messages.put("INVALID_TEMPLATE","人脸模板无效，请联系管理员重新录入");
                messages.put("MODEL_MISMATCH","人脸模型已变更，请重新录入");
                messages.put("BUSY","识别服务忙，请稍后重试");
                messages.put("MODELS_NOT_READY","本地模型未就绪，请先准备模型");
                if(messages.containsKey(reason)) message=messages.get(reason);
                if(error.getRawStatusCode()==422) code="422";
            } catch(Exception ignored) { /* 返回固定错误，不透传远端内容。 */ }
            throw new ServiceException(code,message);
        } catch(ServiceException error) {throw error;}
        catch(Exception error) {throw new ServiceException("503","无法连接本地识别服务或响应无效，请检查服务后重试");}
    }
    public String enroll(List<String> images) {
        if(images==null || images.isEmpty() || images.size()>3) throw new ServiceException("422","请采集一至三张本人照片");
        JsonNode result=request("/v1/enroll",Collections.singletonMap("images",images));
        JsonNode embeddings=result.path("embeddings");
        validateEmbeddings(embeddings,images.size());
        return embeddings.toString();
    }
    static void validateEmbeddings(JsonNode embeddings,int count) {
        if(count<1 || count>3 || !embeddings.isArray() || embeddings.size()!=count) throw new ServiceException("503","本地服务返回无效模板");
        for(JsonNode vector:embeddings) {
            if(!vector.isArray() || vector.size()!=128) throw new ServiceException("503","本地服务返回无效模板");
            double norm=0;
            for(JsonNode value:vector) {
                if(!value.isNumber() || !Double.isFinite(value.asDouble())) throw new ServiceException("503","本地服务返回无效模板");
                norm+=value.asDouble()*value.asDouble();
            }
            if(Math.abs(norm-1)>0.01) throw new ServiceException("503","本地服务返回未归一化模板");
        }
    }
    public double compare(String payload,String image) {
        try {
            JsonNode embeddings=json.readTree(payload); validateEmbeddings(embeddings,embeddings.size());
            Map<String,Object> body=new HashMap<>(); body.put("embeddings",embeddings); body.put("image",image); body.put("modelVersion",MODEL);
            JsonNode score=request("/v1/compare",body).path("score");
            if(!score.isNumber() || !Double.isFinite(score.asDouble()) || Math.abs(score.asDouble())>1)
                throw new ServiceException("503","识别服务返回无效分数");
            return score.asDouble();
        } catch(ServiceException error) {throw error;}
        catch(Exception error) {throw new ServiceException("409","人脸模板不可用，请重新录入");}
    }
    public boolean ready() {
        if(token.length()<32) return false;
        try {
            JsonNode health=json.readTree(client.getForObject(url+"/health",String.class));
            return "UP".equals(health.path("status").asText()) && MODEL.equals(health.path("modelVersion").asText());
        }
        catch(Exception error) {return false;}
    }
}
