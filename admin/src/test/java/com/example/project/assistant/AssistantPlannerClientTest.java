package com.example.project.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AssistantPlannerClientTest {
    private static final String TOKEN="test-only-assistant-internal-token-0123456789";
    private final ObjectMapper json=new ObjectMapper();
    private MockRestServiceServer server;
    private AssistantPlannerClient client(String path,String body,HttpStatus status,String provider) {
        RestTemplateBuilder builder=new RestTemplateBuilder().additionalCustomizers(template -> {
            server=MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo("http://127.0.0.1:8010"+path)).andExpect(header("X-Assistant-Service-Token",TOKEN)).andExpect(header(HttpHeaders.CONNECTION,"close"))
                    .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        });
        return new AssistantPlannerClient(builder,json,true,"http://127.0.0.1:8010/",TOKEN,provider);
    }
    @Test void disabledByDefaultAndMissingTokenNeverContactService() {
        AssistantPlannerClient disabled=new AssistantPlannerClient(new RestTemplateBuilder(),json,false,"http://127.0.0.1:1","","openai-compatible");
        assertEquals(false,disabled.status().get("enabled"));assertEquals(false,disabled.status().get("ready"));assertEquals("ASSISTANT_DISABLED",disabled.status().get("reason"));
        assertEquals("ASSISTANT_DISABLED",assertThrows(AssistantPlannerClient.PlannerFailure.class,()->disabled.plan(Collections.emptyMap(),60000)).code);
        AssistantPlannerClient unconfigured=new AssistantPlannerClient(new RestTemplateBuilder(),json,true,"http://127.0.0.1:1","","mock");
        assertEquals("ASSISTANT_NOT_CONFIGURED",unconfigured.status().get("reason"));assertEquals("MOCK",unconfigured.status().get("mode"));
    }
    @Test void healthUsesIndependentPrivateHeaderAndStrictMode() {
        AssistantPlannerClient client=client("/assistant/health","{\"contractVersion\":\"assistant-plan-v1\",\"ready\":true,\"mode\":\"MOCK\"}",HttpStatus.OK,"mock");
        assertEquals(true,client.status().get("ready"));server.verify();
        client=client("/assistant/health","{\"contractVersion\":\"assistant-plan-v1\",\"ready\":true,\"mode\":\"MOCK\"}",HttpStatus.OK,"openai-compatible");
        assertEquals("CONTRACT_MISMATCH",client.status().get("reason"));server.verify();
    }
    @Test void validPlanRequiresExplicitMatchingModeAndContract() {
        AssistantPlannerClient client=client("/assistant/v1/plan",response("MOCK","[]"),HttpStatus.OK,"mock");
        assertEquals("MOCK",client.plan(Collections.singletonMap("message","本月本人考勤"),60000).path("mode").asText());server.verify();
    }
    @Test void invalidContractWrongModeAndParallelToolsAreRejected() {
        for(String body:Arrays.asList(response("MODEL","[]"),response("MOCK","[{},{}]"),response("MOCK","null"),response("MOCK","[]").replace("assistant-plan-v1","v0"),"not-json")) {
            AssistantPlannerClient client=client("/assistant/v1/plan",body,HttpStatus.OK,"mock");
            String code=assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000)).code;
            assertTrue(Arrays.asList("MODEL_INVALID_RESPONSE","PLANNER_UNAVAILABLE").contains(code));server.verify();
        }
    }
    @Test void remoteFailureDoesNotExposeCredentialsOrProviderResponse() {
        AssistantPlannerClient client=client("/assistant/v1/plan","private-provider-key-private-sdk-response",HttpStatus.INTERNAL_SERVER_ERROR,"mock");
        AssistantPlannerClient.PlannerFailure error=assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000));
        assertEquals("PLANNER_UNAVAILABLE",error.code);assertFalse(error.getMessage().contains("private"));server.verify();
    }
    @Test void plannerServiceUrlCannotContainCallbackPathsOrQuery() {
        for(String url:Arrays.asList("file:///secret","http://127.0.0.1:8010/private","http://127.0.0.1:8010?token=x"))
            assertThrows(IllegalArgumentException.class,()->new AssistantPlannerClient(new RestTemplateBuilder(),json,true,url,TOKEN,"mock"));
    }
    private String response(String mode,String tools){return "{\"contractVersion\":\"assistant-plan-v1\",\"mode\":\""+mode+"\",\"message\":\"请明确月份\",\"toolCalls\":"+tools+",\"usage\":{\"inputTokens\":0,\"outputTokens\":0}}";}
}
