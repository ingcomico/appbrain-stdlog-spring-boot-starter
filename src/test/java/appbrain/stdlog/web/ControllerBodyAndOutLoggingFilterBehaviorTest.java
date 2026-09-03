package appbrain.stdlog.web;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ejercita {@code doFilterInternal} de punta a punta (via {@code doFilter}) para cubrir
 * los dos modos de operación (con y sin captura de body) y el evento extra de excepción.
 */
class ControllerBodyAndOutLoggingFilterBehaviorTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    // ---------------- Modo sin body ----------------

    @Test
    void shouldEmitInAndOutEventsWithoutBodyWhenBodyLoggingDisabled() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> ((HttpServletResponse) s).setStatus(200));

        assertEquals(2, appender.list.size());
        Map<String, Object> in = payload(0);
        assertEquals("CONTROLLER_HTTP", in.get("event"));
        assertEquals("IN", in.get("direction"));
        Map<?, ?> inRequest = (Map<?, ?>) in.get("request");
        assertEquals("NONE", inRequest.get("inputType"));

        Map<String, Object> out = payload(1);
        assertEquals("OUT", out.get("direction"));
        assertEquals("SUCCESS", out.get("outcome"));
        Map<?, ?> outResponse = (Map<?, ?>) out.get("response");
        assertEquals("DISABLED", outResponse.get("bodyCapture"));
    }

    @Test
    void shouldMarkOutcomeFailureAndUseFailure4xxLevelFor404() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setOutLevelFailure4xx(StdlogLevel.WARN);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/missing"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(404));

        Map<String, Object> out = payload(1);
        assertEquals("FAILURE", out.get("outcome"));
        assertEquals(Level.WARN, appender.list.get(1).getLevel());
    }

    @Test
    void shouldUseFailure5xxLevelFor500() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setOutLevelFailure5xx(StdlogLevel.ERROR);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/boom"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(500));

        assertEquals(Level.ERROR, appender.list.get(1).getLevel());
    }

    @Test
    void shouldIncludeQueryParamsAndFullPathWhenPresent() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setQueryString("site_id=MCO");
        req.addParameter("site_id", "MCO");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> ((HttpServletResponse) s).setStatus(200));

        Map<String, Object> in = payload(0);
        Map<?, ?> http = (Map<?, ?>) in.get("http");
        assertEquals("/orders?site_id=MCO", http.get("fullPath"));
        Map<?, ?> reqNode = (Map<?, ?>) in.get("request");
        assertEquals("QUERY", reqNode.get("inputType"));
        assertEquals(Map.of("site_id", "MCO"), reqNode.get("queryParams"));
    }

    @Test
    void shouldOnlyIncludeAllowlistedHeaders() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setAllowedHeaders(List.of("x-routing"));

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("x-routing", "beta");
        req.addHeader("authorization", "secret");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> ((HttpServletResponse) s).setStatus(200));

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        Map<?, ?> headers = (Map<?, ?>) reqNode.get("headers");
        assertEquals("beta", headers.get("x-routing"));
        assertFalse(headers.containsKey("authorization"));
    }

    @Test
    void shouldIncludeOperationRouteAndRequestIdFromContextSetByUpstreamComponents() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("request_id", "uuid-42");
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            // Simula lo que StdlogMvcOperationInterceptor haría más abajo en la cadena.
            r.setAttribute(StdlogAttrs.OPERATION, "OrdersController#list");
            r.setAttribute(StdlogAttrs.ROUTE, "GET /orders");
            ((HttpServletResponse) s).setStatus(200);
        });

        Map<String, Object> in = payload(0);
        assertEquals("OrdersController#list", in.get("operation"));
        assertEquals("GET /orders", in.get("route"));
        assertEquals("uuid-42", in.get("request_id"));
    }

    @Test
    void shouldKeepTraceAndSpanIdsCapturedBeforeMdcIsCleared() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("traceId", "trace-controller");
        MDC.put("spanId", "span-controller");
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            MDC.remove("traceId");
            MDC.remove("spanId");
            ((HttpServletResponse) s).setStatus(200);
        });

        Map<String, Object> in = payload(0);
        Map<String, Object> out = payload(1);
        assertEquals("trace-controller", in.get("trace_id"));
        assertEquals("span-controller", in.get("span_id"));
        assertEquals("trace-controller", out.get("trace_id"));
        assertEquals("span-controller", out.get("span_id"));
    }

    @Test
    void shouldComputeNonNegativeElapsedMs() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(200));

        Object elapsed = payload(1).get("elapsedMs");
        assertTrue(((Number) elapsed).longValue() >= 0);
    }

    // ---------------- Exclusión: @StdlogExcluded (via MDC) ----------------
    //
    // El interceptor marca la exclusión poniendo StdlogEmitter.MDC_EXCLUDED en el MDC
    // ANTES de que corra el controller (ver StdlogMvcOperationInterceptorTest). Acá lo
    // simulamos poniéndolo directamente dentro del FilterChain, que es exactamente donde
    // correría el resto del pipeline MVC en un request real.

    @Test
    void shouldSuppressInfoEventsWhenExcludedViaMdcAndStatusIsSuccess() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders/ping"), new MockHttpServletResponse(), (r, s) -> {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
            ((HttpServletResponse) s).setStatus(200);
        });

        assertTrue(appender.list.isEmpty(), "IN y OUT son INFO por default: deben suprimirse");
    }

    @Test
    void shouldStillEmitOutEventWhenExcludedButStatusIs4xx() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders/ping"), new MockHttpServletResponse(), (r, s) -> {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
            ((HttpServletResponse) s).setStatus(404);
        });

        // IN es INFO y se suprime por la exclusión; OUT es WARN por default y no se suprime.
        // Desde ADR-0012 (regla 6) se emite además el evento extra de error, porque ahora se
        // guía por el status y no por la existencia de excepción: un 404 sin excepción también
        // es un resultado que hay que registrar. Antes de ese ADR aquí sólo salía el OUT.
        assertEquals(2, appender.list.size(), "OUT (WARN) + evento extra de error (WARN)");
        assertEquals("OUT", payload(0).get("direction"));
        assertEquals("WARN", payload(1).get("event"));
        assertEquals(404, ((java.util.Map<?, ?>) payload(1).get("http")).get("status"));
        assertEquals("HTTP 404 (sin excepcion asociada)",
                ((java.util.Map<?, ?>) payload(1).get("error")).get("message"));
    }

    @Test
    void shouldStillEmitOutAndExceptionEventsWhenExcludedAndStatusIs5xx() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders/ping"), new MockHttpServletResponse(), (r, s) -> {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
            r.setAttribute(StdlogAttrs.ERROR, new RuntimeException("boom"));
            ((HttpServletResponse) s).setStatus(500);
        });

        assertEquals(2, appender.list.size(), "OUT y el evento ERROR son ambos ERROR por default: nunca se suprimen");
    }

    @Test
    void shouldSuppressInfoEventsWhenExcludedViaMdcInBodyMode() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders/ping"), new MockHttpServletResponse(), (r, s) -> {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
            HttpServletResponse resp = (HttpServletResponse) s;
            resp.setContentType("application/json");
            resp.setStatus(200);
            resp.getWriter().write("{\"ok\":true}");
            resp.getWriter().flush();
        });

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldClearExclusionFlagAfterRequestSoItDoesNotLeakToTheNextOne() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders/ping"), new MockHttpServletResponse(), (r, s) -> {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
            ((HttpServletResponse) s).setStatus(200);
        });

        assertNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    // ---------------- Exclusión: excluded-path-patterns (via MDC) ----------------

    @Test
    void shouldSuppressInfoEventsForExcludedPathOnSuccess() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setExcludedPathPatterns(List.of("/actuator/**"));

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(200));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldStillEmitFailureEventsForExcludedPath() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setExcludedPathPatterns(List.of("/actuator/**"));

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, new RuntimeException("down"));
            ((HttpServletResponse) s).setStatus(500);
        });

        assertEquals(2, appender.list.size(), "OUT y el evento ERROR (ambos ERROR por default) igual se emiten");
    }

    @Test
    void shouldNotSuppressEventsForNonExcludedPath() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getController().setExcludedPathPatterns(List.of("/actuator/**"));

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(200));

        assertEquals(2, appender.list.size());
    }

    // ---------------- Evento extra WARN/ERROR ----------------

    @Test
    void shouldEmitWarnEventFor4xxWithCapturedException() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        IllegalArgumentException ex = new IllegalArgumentException("bad input");

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, ex);
            ((HttpServletResponse) s).setStatus(400);
        });

        assertEquals(3, appender.list.size());
        Map<String, Object> extra = payload(2);
        assertEquals("WARN", extra.get("event"));
        assertEquals(Level.WARN, appender.list.get(2).getLevel());
        Map<?, ?> err = (Map<?, ?>) extra.get("error");
        assertEquals("java.lang.IllegalArgumentException", err.get("type"));
        assertEquals("bad input", err.get("message"));
        assertNotNull(appender.list.get(2).getThrowableProxy());
    }

    @Test
    void shouldEmitErrorEventFor5xxWithCapturedException() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        RuntimeException ex = new RuntimeException("db down");

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, ex);
            ((HttpServletResponse) s).setStatus(500);
        });

        Map<String, Object> extra = payload(2);
        assertEquals("ERROR", extra.get("event"));
        assertEquals(Level.ERROR, appender.list.get(2).getLevel());
    }

    @Test
    void shouldNotEmitExtraEventWhenErrorModuleDisabled() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.getError().setEnabled(false);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, new RuntimeException("x"));
            ((HttpServletResponse) s).setStatus(500);
        });

        assertEquals(2, appender.list.size(), "solo IN y OUT, sin evento extra");
    }

    @Test
    void shouldNotEmitExtraEventWhenNoExceptionWasCaptured() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(200));

        assertEquals(2, appender.list.size());
    }

    @Test
    void shouldFilterAppTraceByConfiguredConsumerBasePackage() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        props.setConsumerBasePackage("appbrain.stdlog.web");

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.springframework.web.Dispatcher", "handle", "Dispatcher.java", 1),
                new StackTraceElement("appbrain.stdlog.web.SomeConsumerCode", "doWork", "SomeConsumerCode.java", 7),
        });

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, ex);
            ((HttpServletResponse) s).setStatus(500);
        });

        Map<?, ?> err = (Map<?, ?>) payload(2).get("error");
        assertEquals(List.of("appbrain.stdlog.web.SomeConsumerCode#doWork:7"), err.get("app_trace"));
    }

    @Test
    void shouldReturnEmptyAppTraceWhenConsumerBasePackageIsNotConfigured() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = noBodyProps();
        // sin setConsumerBasePackage(...)

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("appbrain.stdlog.web.SomeConsumerCode", "doWork", "SomeConsumerCode.java", 7),
        });

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            r.setAttribute(StdlogAttrs.ERROR, ex);
            ((HttpServletResponse) s).setStatus(500);
        });

        Map<?, ?> err = (Map<?, ?>) payload(2).get("error");
        assertEquals(List.of(), err.get("app_trace"));
    }

    // ---------------- Modo con body ----------------

    @Test
    void shouldCaptureJsonRequestAndResponseBodyWhenContentTypeAllowed() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
        req.setContentType("application/json");
        req.setContent("{\"id\":10}".getBytes(StandardCharsets.UTF_8));

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            readFully((HttpServletRequest) r);
            HttpServletResponse resp = (HttpServletResponse) s;
            resp.setContentType("application/json");
            resp.setStatus(200);
            resp.getWriter().write("{\"ok\":true}");
            resp.getWriter().flush();
        });

        Map<String, Object> in = payload(0);
        Map<?, ?> reqNode = (Map<?, ?>) in.get("request");
        assertEquals(Map.of("id", 10), reqNode.get("body"));
        assertEquals("JSON", reqNode.get("bodyFormat"));
        assertEquals("BODY", reqNode.get("inputType"));

        Map<String, Object> out = payload(1);
        Map<?, ?> resNode = (Map<?, ?>) out.get("response");
        assertEquals(Map.of("ok", true), resNode.get("body"));
        assertEquals("JSON", resNode.get("bodyFormat"));
    }

    @Test
    void shouldMarkSkippedContentTypeWhenRequestContentTypeNotAllowed() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/upload");
        req.setContentType("application/octet-stream");
        req.setContent(new byte[] {1, 2, 3});

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            readFully((HttpServletRequest) r);
            ((HttpServletResponse) s).setStatus(200);
        });

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        assertEquals("SKIPPED_CONTENT_TYPE", reqNode.get("bodyCapture"));
    }

    @Test
    void shouldMarkNotAvailableWhenBodyExpectedButNeverReadDownstream() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
        req.setContentType("application/json");
        req.setContent("{\"id\":10}".getBytes(StandardCharsets.UTF_8));

        // El downstream nunca lee el input stream, así que ContentCachingRequestWrapper
        // no llega a cachear ningún byte.
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> ((HttpServletResponse) s).setStatus(200));

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        assertEquals("NOT_AVAILABLE", reqNode.get("bodyCapture"));
    }

    @Test
    void shouldMarkDisabledWhenLogRequestBodyIsFalse() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();
        props.getController().setLogRequestBody(false);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
        req.setContentType("application/json");
        req.setContent("{\"id\":10}".getBytes(StandardCharsets.UTF_8));

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            readFully((HttpServletRequest) r);
            ((HttpServletResponse) s).setStatus(200);
        });

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        assertEquals("DISABLED", reqNode.get("bodyCapture"));
    }

    @Test
    void shouldMarkDisabledWhenLogResponseBodyIsFalse() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();
        props.getController().setLogResponseBody(false);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            HttpServletResponse resp = (HttpServletResponse) s;
            resp.setContentType("application/json");
            resp.setStatus(200);
            resp.getWriter().write("{\"ok\":true}");
            resp.getWriter().flush();
        });

        Map<?, ?> resNode = (Map<?, ?>) payload(1).get("response");
        assertEquals("DISABLED", resNode.get("bodyCapture"));
    }

    @Test
    void shouldMarkEmptyWhenResponseHasNoBody() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(204));

        Map<?, ?> resNode = (Map<?, ?>) payload(1).get("response");
        assertEquals("EMPTY", resNode.get("bodyCapture"));
    }

    @Test
    void shouldTruncateResponseBodyWhenExceedingMaxBytes() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();
        props.getController().setMaxResponseBodyBytes(5);

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            HttpServletResponse resp = (HttpServletResponse) s;
            resp.setContentType("text/plain");
            resp.setStatus(200);
            resp.getWriter().write("abcdefghij");
            resp.getWriter().flush();
        });

        Map<?, ?> resNode = (Map<?, ?>) payload(1).get("response");
        assertEquals("abcde", resNode.get("body"));
        assertEquals(true, resNode.get("bodyTruncated"));
        assertEquals("TEXT_TRUNCATED", resNode.get("bodyFormat"));
    }

    @Test
    void shouldMarkTextInvalidJsonWhenBodyIsNotValidJson() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {
            HttpServletResponse resp = (HttpServletResponse) s;
            resp.setContentType("application/json");
            resp.setStatus(200);
            resp.getWriter().write("not-json-at-all");
            resp.getWriter().flush();
        });

        Map<?, ?> resNode = (Map<?, ?>) payload(1).get("response");
        assertEquals("not-json-at-all", resNode.get("body"));
        assertEquals("TEXT_INVALID_JSON", resNode.get("bodyFormat"));
    }

    @Test
    void shouldSkipRequestBodyBlockEntirelyWhenNoBodyExpectedAndNoQuery() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (r, s) -> ((HttpServletResponse) s).setStatus(200));

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        assertEquals("NONE", reqNode.get("inputType"));
        assertFalse(reqNode.containsKey("bodyCapture"));
    }

    @Test
    void shouldResolveInputTypeQueryAndBody() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = withBodyProps();

        ControllerBodyAndOutLoggingFilter filter = filter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
        req.setQueryString("site_id=MCO");
        req.addParameter("site_id", "MCO");
        req.setContentType("application/json");
        req.setContent("{\"id\":10}".getBytes(StandardCharsets.UTF_8));

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            readFully((HttpServletRequest) r);
            ((HttpServletResponse) s).setStatus(200);
        });

        Map<?, ?> reqNode = (Map<?, ?>) payload(0).get("request");
        assertEquals("QUERY_AND_BODY", reqNode.get("inputType"));
    }

    // ---------------- helpers ----------------

    private static void readFully(HttpServletRequest req) {
        try {
            req.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static StdlogProperties noBodyProps() {
        StdlogProperties props = new StdlogProperties();
        props.getController().setEnabled(true);
        props.getController().setLogRequestBody(false);
        props.getController().setLogResponseBody(false);
        return props;
    }

    private static StdlogProperties withBodyProps() {
        StdlogProperties props = new StdlogProperties();
        props.getController().setEnabled(true);
        props.getController().setLogRequestBody(true);
        props.getController().setLogResponseBody(true);
        return props;
    }

    private static ControllerBodyAndOutLoggingFilter filter(StdlogProperties props) {
        return new ControllerBodyAndOutLoggingFilter(props, new ObjectMapper());
    }

    private Map<String, Object> payload(int index) {
        return StdlogTestSupport.stdlogPayload(appender.list.get(index));
    }
}
