package com.example.project.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import java.io.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

public class AssistantPlannerClientTest {
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
    @Test void boundedInternalModelTimeoutIsDistinctAndDoesNotEchoTheMessage() {
        AssistantPlannerClient client=client("/assistant/v1/plan","{\"code\":\"MODEL_TIMEOUT\",\"msg\":\"private-provider-key-private-sdk-response\"}",HttpStatus.GATEWAY_TIMEOUT,"mock");
        AssistantPlannerClient.PlannerFailure error=assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000));
        assertEquals("MODEL_TIMEOUT",error.code);assertEquals("MODEL_TIMEOUT",error.getMessage());assertNull(error.getCause());server.verify();
    }
    @Test void socketReadTimeoutIsDistinctButConnectionRefusalRemainsUnavailable() {
        for(IOException failure:Arrays.asList(new SocketTimeoutException("private-socket-detail"),new ConnectException("private-connect-detail"))) {
            RestTemplateBuilder builder=new RestTemplateBuilder().additionalCustomizers(template -> {
                server=MockRestServiceServer.bindTo(template).build();
                server.expect(requestTo("http://127.0.0.1:8010/assistant/v1/plan")).andRespond(request -> {throw failure;});
            });
            AssistantPlannerClient client=new AssistantPlannerClient(builder,json,true,"http://127.0.0.1:8010",TOKEN,"mock");
            AssistantPlannerClient.PlannerFailure error=assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000));
            assertEquals(failure instanceof SocketTimeoutException?"MODEL_TIMEOUT":"PLANNER_UNAVAILABLE",error.code);
            assertFalse(error.getMessage().contains("private"));assertNull(error.getCause());server.verify();
        }
    }
    @Test void onlyExactCodeAnd504CanBecomeModelTimeout() {
        for(String body:Arrays.asList("{\"code\":\"MODEL_OTHER\",\"msg\":\"private-detail\"}","{\"code\":[\"MODEL_TIMEOUT\"]}","{\"code\":{\"code\":\"MODEL_TIMEOUT\"}}","[\"MODEL_TIMEOUT\"]","private-invalid-json")) {
            AssistantPlannerClient client=client("/assistant/v1/plan",body,HttpStatus.GATEWAY_TIMEOUT,"mock");
            AssistantPlannerClient.PlannerFailure error=assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000));
            assertEquals("PLANNER_UNAVAILABLE",error.code);assertFalse(error.getMessage().contains("private"));server.verify();
        }
        AssistantPlannerClient client=client("/assistant/v1/plan","{\"code\":\"MODEL_TIMEOUT\"}",HttpStatus.SERVICE_UNAVAILABLE,"mock");
        assertEquals("PLANNER_UNAVAILABLE",assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000)).code);server.verify();
    }
    @Test void responseBodyIsBoundedBeforeParsingEvenOnHttpErrors() {
        final int[] consumed={0};
        InputStream large=new InputStream() {
            @Override public int read(){if(consumed[0]>=1024*1024)return -1;consumed[0]++;return 'x';}
            @Override public int read(byte[] bytes,int offset,int length){int count=Math.min(length,1024*1024-consumed[0]);if(count==0)return -1;Arrays.fill(bytes,offset,offset+count,(byte)'x');consumed[0]+=count;return count;}
        };
        RestTemplateBuilder builder=new RestTemplateBuilder().additionalCustomizers(template -> {
            server=MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo("http://127.0.0.1:8010/assistant/v1/plan")).andRespond(request -> new MockClientHttpResponse(large,HttpStatus.GATEWAY_TIMEOUT));
        });
        AssistantPlannerClient client=new AssistantPlannerClient(builder,json,true,"http://127.0.0.1:8010",TOKEN,"mock");
        assertEquals("MODEL_INVALID_RESPONSE",assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000)).code);
        assertTrue(consumed[0]<=200*1024+4096,"response must stop reading once the fixed body budget is exceeded");server.verify();
    }
    @Test void oversizedBodyIsClosedBeforeResponseCleanupCanDrainTheRemainder() {
        final int[] consumed={0},cleanupConsumed={0};final boolean[] streamClosed={false},closedBeforeCleanup={false};
        InputStream large=new InputStream() {
            @Override public int read() throws IOException {byte[] value=new byte[1];return read(value,0,1)<0?-1:value[0];}
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                if(streamClosed[0])throw new IOException("synthetic-stream-closed");
                int count=Math.min(length,1024*1024-consumed[0]);if(count==0)return -1;
                Arrays.fill(bytes,offset,offset+count,(byte)'x');consumed[0]+=count;return count;
            }
            @Override public void close(){streamClosed[0]=true;}
        };
        RestTemplateBuilder builder=new RestTemplateBuilder().additionalCustomizers(template -> {
            server=MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo("http://127.0.0.1:8010/assistant/v1/plan")).andRespond(request -> new MockClientHttpResponse(large,HttpStatus.GATEWAY_TIMEOUT){
                @Override public void close(){
                    // Match clients that try to consume the remaining entity during response cleanup.
                    closedBeforeCleanup[0]=streamClosed[0];int before=consumed[0];
                    try {byte[] buffer=new byte[4096];while(large.read(buffer)!=-1){};}catch(IOException ignored){}
                    finally {cleanupConsumed[0]=consumed[0]-before;try{large.close();}catch(IOException ignored){}}
                }
            });
        });
        AssistantPlannerClient client=new AssistantPlannerClient(builder,json,true,"http://127.0.0.1:8010",TOKEN,"mock");
        assertEquals("MODEL_INVALID_RESPONSE",assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),60000)).code);
        assertTrue(closedBeforeCleanup[0],"close the input stream before RestTemplate closes the response");
        assertEquals(0,cleanupConsumed[0]);assertTrue(consumed[0]<=200*1024+4096);server.verify();
    }
    @Test void planningWaitMatchesPythonMaximumAndKeepsRemainingMilliseconds() {
        for(int[] bounds:new int[][]{{60000,32000,2000},{1501,1501,1501},{1,1,1}}) {
            CapturedTimeoutFactory factory=new CapturedTimeoutFactory();
            RestTemplateBuilder builder=new RestTemplateBuilder().requestFactory(()->factory).additionalCustomizers(template -> {
                server=MockRestServiceServer.bindTo(template).build();
                server.expect(requestTo("http://127.0.0.1:8010/assistant/v1/plan")).andRespond(withSuccess(response("MOCK","[]"),MediaType.APPLICATION_JSON));
            });
            AssistantPlannerClient client=new AssistantPlannerClient(builder,json,true,"http://127.0.0.1:8010",TOKEN,"mock");
            client.plan(Collections.emptyMap(),bounds[0]);assertEquals(bounds[1],factory.readMillis);assertEquals(bounds[2],factory.connectMillis);server.verify();
        }
        AssistantPlannerClient client=new AssistantPlannerClient(new RestTemplateBuilder(),json,true,"http://127.0.0.1:1",TOKEN,"mock");
        assertEquals("MODEL_TIMEOUT",assertThrows(AssistantPlannerClient.PlannerFailure.class,()->client.plan(Collections.emptyMap(),0)).code);
    }
    public static class CapturedTimeoutFactory extends SimpleClientHttpRequestFactory {
        int readMillis,connectMillis;
        @Override public void setReadTimeout(int timeout){readMillis=timeout;super.setReadTimeout(timeout);}
        @Override public void setConnectTimeout(int timeout){connectMillis=timeout;super.setConnectTimeout(timeout);}
    }
    @Test void plannerServiceUrlCannotContainCallbackPathsOrQuery() {
        for(String url:Arrays.asList("file:///secret","http://127.0.0.1:8010/private","http://127.0.0.1:8010?token=x"))
            assertThrows(IllegalArgumentException.class,()->new AssistantPlannerClient(new RestTemplateBuilder(),json,true,url,TOKEN,"mock"));
    }
    private String response(String mode,String tools){return "{\"contractVersion\":\"assistant-plan-v1\",\"mode\":\""+mode+"\",\"message\":\"请明确月份\",\"toolCalls\":"+tools+",\"usage\":{\"inputTokens\":0,\"outputTokens\":0}}";}
}
