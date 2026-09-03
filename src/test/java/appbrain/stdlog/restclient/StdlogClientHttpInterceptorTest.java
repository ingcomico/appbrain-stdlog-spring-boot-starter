package appbrain.stdlog.restclient;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogClientHttpInterceptorTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void shouldNotLogWhenRestclientDisabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setEnabled(false);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/orders");

        ClientHttpResponse response = interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldLogSuccessWithoutBodyWhenStdlogNotInDebug() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/orders?id=1");

        interceptor.intercept(request, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                (req, body) -> jsonResponse(200, "{\"b\":2}"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("CLIENT_HTTP", payload.get("event"));
        assertEquals("IN", payload.get("direction"));
        assertEquals("SUCCESS", payload.get("outcome"));

        Map<?, ?> http = (Map<?, ?>) payload.get("http");
        assertEquals("GET", http.get("method"));
        assertEquals(200, http.get("status"));
        assertEquals("https://api.example.com/orders?id=1", http.get("url"));

        Map<?, ?> peer = (Map<?, ?>) payload.get("peer");
        assertEquals("api.example.com", peer.get("host"));

        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        assertFalse(reqNode.containsKey("body"), "no debería incluir body fuera de DEBUG");

        Map<?, ?> resNode = (Map<?, ?>) payload.get("response");
        assertFalse(resNode.containsKey("body"), "no debería incluir body fuera de DEBUG");
    }

    @Test
    void shouldIncludeBodiesWhenStdlogIsInDebug() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/orders");

        interceptor.intercept(request, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                (req, body) -> jsonResponse(200, "{\"b\":2}"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        assertEquals("{\"a\":1}", reqNode.get("body"));
        assertEquals("JSON", reqNode.get("bodyFormat"));

        Map<?, ?> resNode = (Map<?, ?>) payload.get("response");
        assertEquals("{\"b\":2}", resNode.get("body"));
        assertEquals("JSON", resNode.get("bodyFormat"));
    }

    @Test
    void shouldReturnReadableResponseBodyAfterLoggingIt() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(get("https://api.example.com/orders"),
                new byte[0], (req, body) -> jsonResponse(200, "{\"b\":2}"));

        String bodyAfterLogging = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals("{\"b\":2}", bodyAfterLogging);
        Map<String, Object> payload = onlyPayload();
        Map<?, ?> resNode = (Map<?, ?>) payload.get("response");
        assertEquals("{\"b\":2}", resNode.get("body"));
    }

    @Test
    void shouldUseFailure4xxLevelFor404() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setInLevelFailure4xx(StdlogLevel.WARN);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/missing"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 404));

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        Map<String, Object> payload = StdlogTestSupport.stdlogPayload(appender.list.get(0));
        assertEquals("FAILURE", payload.get("outcome"));
    }

    @Test
    void shouldUseFailure5xxLevelForServerError() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setInLevelFailure5xx(StdlogLevel.ERROR);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/boom"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 500));

        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void shouldLogAndRethrowOnIOException() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        IOException thrown = new IOException("connection refused");

        IOException caught = assertThrows(IOException.class, () ->
                interceptor.intercept(get("https://api.example.com/down"), new byte[0], (req, body) -> {
                    throw thrown;
                }));

        assertSame(thrown, caught);
        Map<String, Object> payload = onlyPayload();
        assertEquals("FAILURE", payload.get("outcome"));
        Map<?, ?> http = (Map<?, ?>) payload.get("http");
        assertEquals(500, http.get("status"));
        assertNotNull(appender.list.get(0).getThrowableProxy());
    }

    @Test
    void shouldSkipSuccessInProdWhenLogOnlyOnFailureInProdIsEnabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getRestclient().setLogOnlyOnFailureInProd(true);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/ok"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldStillLogFailureInProdWhenLogOnlyOnFailureInProdIsEnabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getRestclient().setLogOnlyOnFailureInProd(true);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/fail"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 500));

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldGenerateCallIdWhenCaptureCallIdEnabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setCaptureCallId(true);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/ok"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        assertNotNull(payload.get("call_id"));
    }

    @Test
    void shouldNotGenerateCallIdWhenCaptureCallIdDisabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setCaptureCallId(false);

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/ok"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        assertFalse(payload.containsKey("call_id"));
    }

    @Test
    void shouldIncludeOperationAndRequestIdFromMdc() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("operation", "OrdersController#get");
        MDC.put("request_id", "uuid-1");
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/ok"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        assertEquals("OrdersController#get", payload.get("operation"));
        assertEquals("uuid-1", payload.get("request_id"));
    }

    @Test
    void shouldIncludeTraceAndSpanIdsFromMdc() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("traceId", "trace-client-http");
        MDC.put("spanId", "span-client-http");
        StdlogProperties props = props();

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        interceptor.intercept(get("https://api.example.com/ok"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        assertEquals("trace-client-http", payload.get("trace_id"));
        assertEquals("span-client-http", payload.get("span_id"));
    }

    @Test
    void shouldOnlyIncludeAllowlistedRequestHeaders() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setLogAllRequestHeaders(false);
        props.getRestclient().setRequestHeadersAllowlist(List.of("x-admin-id"));

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/ok");
        request.getHeaders().add("x-admin-id", "fraudMP");
        request.getHeaders().add("authorization", "Bearer secret");

        interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        Map<?, ?> headers = (Map<?, ?>) reqNode.get("headers");
        assertEquals("fraudMP", headers.get("x-admin-id"));
        assertFalse(headers.containsKey("authorization"));
    }

    @Test
    void shouldIncludeAllRequestHeadersWhenLogAllEnabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setLogAllRequestHeaders(true);
        props.getRestclient().setRequestHeadersAllowlist(List.of());

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/ok");
        request.getHeaders().add("authorization", "Bearer secret");
        request.getHeaders().add("x-routing", "MCO");

        interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        Map<?, ?> headers = (Map<?, ?>) reqNode.get("headers");

        // logAllRequestHeaders incluye todas las cabeceras...
        assertTrue(headers.containsKey("authorization"));
        assertEquals("MCO", headers.get("x-routing"));
        // ...pero desde ADR-0010 el valor de las sensibles va enmascarado. Antes de ese ADR
        // esta misma aserción esperaba "Bearer secret" en claro: era el hallazgo F-04.
        assertEquals("***", headers.get("authorization"));
    }

    @Test
    void shouldOmitRequestHeadersWhenAllowlistIsEmptyAndLogAllDisabled() throws IOException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setLogAllRequestHeaders(false);
        props.getRestclient().setRequestHeadersAllowlist(List.of());

        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        MockClientHttpRequest request = get("https://api.example.com/ok");
        request.getHeaders().add("x-admin-id", "fraudMP");

        interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        assertTrue(((Map<?, ?>) reqNode.get("headers")).isEmpty());
    }

    private static MockClientHttpRequest get(String uri) {
        return new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
    }

    private static MockClientHttpResponse jsonResponse(int status, String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private static StdlogProperties props() {
        StdlogProperties props = new StdlogProperties();
        props.getRestclient().setEnabled(true);
        return props;
    }

    private Map<String, Object> onlyPayload() {
        assertEquals(1, appender.list.size());
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }
}
