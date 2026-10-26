package com.example.project.assistant;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Python仅提出计划，外部错误使用固定代码，凭据和SDK响应不进入业务结果。 */
@Component
public class AssistantPlannerClient {
    public static final String CONTRACT="assistant-plan-v1";
    private final RestTemplateBuilder builder;
    private final ObjectMapper json;
    private final String url,token,provider;
    private final boolean enabled;
    public AssistantPlannerClient(RestTemplateBuilder builder,ObjectMapper json,
            @Value("${assistant.enabled:false}") boolean enabled,
            @Value("${assistant.url:http://127.0.0.1:8010}") String url,
            @Value("${assistant.token:}") String token,
            @Value("${assistant.provider:openai-compatible}") String provider) {
        this.builder=builder;this.json=json;this.enabled=enabled;this.token=token;this.url=url.replaceAll("/$","");this.provider=provider;
        try {
            java.net.URI parsed=java.net.URI.create(this.url);
            if(!Arrays.asList("http","https").contains(parsed.getScheme()) || parsed.getHost()==null || parsed.getUserInfo()!=null || parsed.getQuery()!=null || parsed.getFragment()!=null ||
                    (parsed.getPath()!=null && !parsed.getPath().isEmpty())) throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("助手服务地址应只包含协议与主机端口");}
    }
    public String mode(){return "mock".equals(provider)?"MOCK":"MODEL";}
    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",enabled);result.put("ready",false);result.put("mode",mode());
        if(!enabled){result.put("reason","ASSISTANT_DISABLED");return result;}
        if(token.length()<32){result.put("reason","ASSISTANT_NOT_CONFIGURED");return result;}
        try {
            JsonNode health=request("/assistant/health",null,3);
            if(!CONTRACT.equals(health.path("contractVersion").asText()) || !mode().equals(health.path("mode").asText())) throw new PlannerFailure("CONTRACT_MISMATCH");
            boolean ready=health.path("ready").asBoolean(false);result.put("ready",ready);result.put("reason",ready?null:"ASSISTANT_NOT_READY");
        } catch(PlannerFailure failure){result.put("reason",failure.code);}
        return result;
    }
    public JsonNode plan(Map<String,Object> input,long remainingMillis) {
        if(!enabled) throw new PlannerFailure("ASSISTANT_DISABLED");
        if(token.length()<32) throw new PlannerFailure("ASSISTANT_NOT_CONFIGURED");
        JsonNode result=request("/assistant/v1/plan",input,Math.max(1,Math.min(20,remainingMillis/1000)));
        if(!CONTRACT.equals(result.path("contractVersion").asText()) || !mode().equals(result.path("mode").asText()) ||
                !result.path("toolCalls").isArray() || result.path("toolCalls").size()>1 ||
                !result.path("message").isTextual() || result.path("message").asText().length()>2000)
            throw new PlannerFailure("MODEL_INVALID_RESPONSE");
        return result;
    }
    private JsonNode request(String path,Object input,long seconds) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Assistant-Service-Token",token);
        // 分轮使用有界超时的独立请求，主动关闭连接，避免占满本地ASGI的小并发预算。
        headers.set(HttpHeaders.CONNECTION,"close");
        try {
            ResponseEntity<String> response=builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(seconds)).build()
                    .exchange(url+path,input==null?HttpMethod.GET:HttpMethod.POST,new HttpEntity<>(input,headers),String.class);
            String body=response.getBody();if(body==null || body.length()>200*1024) throw new PlannerFailure("MODEL_INVALID_RESPONSE");
            return json.readTree(body);
        }catch(PlannerFailure failure){throw failure;}
        catch(Exception ignored){throw new PlannerFailure("PLANNER_UNAVAILABLE");}
    }
    public static class PlannerFailure extends RuntimeException {
        public final String code;
        public PlannerFailure(String code){super(code);this.code=code;}
    }
}
