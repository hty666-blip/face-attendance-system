package com.example.project.face;

import com.example.project.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LocalFaceProviderTest {
    private static final String TOKEN="test-internal-token-with-at-least-32-chars";
    private final ObjectMapper json=new ObjectMapper();
    private MockRestServiceServer server;
    private LocalFaceProvider provider;

    @BeforeEach void setup() {
        RestTemplateBuilder builder=new RestTemplateBuilder().additionalCustomizers(client -> server=MockRestServiceServer.bindTo(client).build());
        provider=new LocalFaceProvider(builder,json,"http://127.0.0.1:8010/",TOKEN,0.45);
    }
    private String embeddings(int count) throws Exception {
        List<List<Double>> values=new ArrayList<>();
        for(int i=0;i<count;i++) {
            List<Double> vector=new ArrayList<>(Collections.nCopies(128,0d));vector.set(0,1d);values.add(vector);
        }
        return json.writeValueAsString(values);
    }
    private String response(String extra) {return "{\"modelVersion\":\""+LocalFaceProvider.MODEL+"\","+extra+"}";}

    @Test void enrollmentUsesPrivateTokenAndValidatesNormalizedVectors() throws Exception {
        String vectors=embeddings(3);
        server.expect(requestTo("http://127.0.0.1:8010/v1/enroll")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Face-Service-Token",TOKEN)).andExpect(content().json("{\"images\":[\"a\",\"b\",\"c\"]}"))
                .andRespond(withSuccess(response("\"embeddings\":"+vectors),MediaType.APPLICATION_JSON));
        assertEquals(vectors,provider.enroll(Arrays.asList("a","b","c")));server.verify();
    }
    @Test void comparisonSendsVersionAndAcceptsFiniteCosineScore() throws Exception {
        String vectors=embeddings(1);
        server.expect(requestTo("http://127.0.0.1:8010/v1/compare")).andExpect(header("X-Face-Service-Token",TOKEN))
                .andExpect(content().json("{\"embeddings\":"+vectors+",\"image\":\"private-image\",\"modelVersion\":\""+LocalFaceProvider.MODEL+"\"}"))
                .andRespond(withSuccess(response("\"score\":0.72"),MediaType.APPLICATION_JSON));
        assertEquals(0.72,provider.compare(vectors,"private-image"));server.verify();
    }
    @Test void wrongModelAndMalformedEnrollmentNeverBecomeStoredTemplates() throws Exception {
        for(String body:Arrays.asList("{\"modelVersion\":\"different\",\"embeddings\":"+embeddings(1)+"}",
                response("\"embeddings\":[[1,0]]"),response("\"embeddings\":[]"),
                response("\"embeddings\":"+embeddings(1).replace("1.0","2.0")),"not-json")) {
            server.expect(requestTo("http://127.0.0.1:8010/v1/enroll")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertEquals("503",assertThrows(ServiceException.class,() -> provider.enroll(Collections.singletonList("image"))).getCode());
            server.verify();server.reset();
        }
    }
    @Test void invalidScoresAndWrongModelsAreRejected() throws Exception {
        String vectors=embeddings(1);
        for(String body:Arrays.asList(response("\"score\":1.01"),response("\"score\":-1.01"),response("\"score\":\"NaN\""),
                response("\"score\":null"),"{\"modelVersion\":\"other\",\"score\":0.9}")) {
            server.expect(requestTo("http://127.0.0.1:8010/v1/compare")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertEquals("503",assertThrows(ServiceException.class,() -> provider.compare(vectors,"image")).getCode());
            server.verify();server.reset();
        }
    }
    @Test void qualityErrorsUseFixedMessagesWithoutRemoteSensitiveContent() {
        server.expect(requestTo("http://127.0.0.1:8010/v1/enroll"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"MULTIPLE_FACES\",\"message\":\"private-image-secret-embedding\"}"));
        ServiceException error=assertThrows(ServiceException.class,() -> provider.enroll(Collections.singletonList("image")));
        assertEquals("422",error.getCode());assertTrue(error.getMessage().contains("多张"));assertFalse(error.getMessage().contains("secret"));server.verify();
    }
    @Test void unknownRemoteErrorsAndNetworkFailureStayGeneric() {
        server.expect(requestTo("http://127.0.0.1:8010/v1/enroll"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("private-image-secret-embedding"));
        ServiceException error=assertThrows(ServiceException.class,() -> provider.enroll(Collections.singletonList("image")));
        assertEquals("503",error.getCode());assertFalse(error.getMessage().contains("secret"));server.verify();server.reset();
        server.expect(requestTo("http://127.0.0.1:8010/v1/enroll"))
                .andRespond(request -> {throw new ResourceAccessException("private-network-detail");});
        error=assertThrows(ServiceException.class,() -> provider.enroll(Collections.singletonList("image")));
        assertEquals("503",error.getCode());assertFalse(error.getMessage().contains("private"));server.verify();
    }
    @Test void readinessRequiresHealthyMatchingModel() {
        for(String body:Arrays.asList("{\"status\":\"UP\",\"modelVersion\":\"other\"}","{\"status\":\"UP\"}","invalid")) {
            server.expect(requestTo("http://127.0.0.1:8010/health")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertFalse(provider.ready());server.verify();server.reset();
        }
        server.expect(requestTo("http://127.0.0.1:8010/health")).andRespond(withSuccess(response("\"status\":\"UP\""),MediaType.APPLICATION_JSON));
        assertTrue(provider.ready());server.verify();
    }
    @Test void missingTokenAndInvalidStoredTemplateNeverCallNetwork() {
        LocalFaceProvider unconfigured=new LocalFaceProvider(new RestTemplateBuilder(),json,"http://127.0.0.1:8010","",0.45);
        assertFalse(unconfigured.ready());
        assertEquals("503",assertThrows(ServiceException.class,() -> unconfigured.enroll(Collections.singletonList("image"))).getCode());
        for(String payload:Arrays.asList("[]","[[0,0]]","null","malformed")) assertThrows(ServiceException.class,() -> provider.compare(payload,"image"));
        server.verify();
    }
}
