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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogWebClientExchangeFilterTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void shouldNotLogWhenRestclientDisabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setEnabled(false);

        String body = client(props, req -> Mono.just(json(200, "{\"b\":2}")))
                .get().uri("https://api.example.com/orders").retrieve().bodyToMono(String.class).block();

        assertEquals("{\"b\":2}", body);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldNotLogWhenWebclientDisabledButKeepRestClientLogging() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().getWebclient().setEnabled(false);

        client(props, req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/x").retrieve().bodyToMono(String.class).block();

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldLogSuccessWithoutBodyWhenStdlogNotInDebug() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);

        client(props(), req -> Mono.just(json(200, "{\"b\":2}")))
                .get().uri("https://api.example.com/orders?id=1").retrieve().bodyToMono(String.class).block();

        Map<String, Object> payload = onlyPayload();
        assertEquals("CLIENT_HTTP", payload.get("event"));
        assertEquals("IN", payload.get("direction"));
        assertEquals("SUCCESS", payload.get("outcome"));

        Map<?, ?> http = (Map<?, ?>) payload.get("http");
        assertEquals("GET", http.get("method"));
        assertEquals(200, http.get("status"));
        assertEquals("https://api.example.com/orders?id=1", http.get("url"));
        assertEquals("api.example.com", ((Map<?, ?>) payload.get("peer")).get("host"));

        assertFalse(((Map<?, ?>) payload.get("request")).containsKey("body"));
        assertFalse(((Map<?, ?>) payload.get("response")).containsKey("body"));
    }

    @Test
    void shouldCaptureRequestAndResponseBodyInDebugAndKeepResponseReadable() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);

        String body = client(props(), echo(200, "{\"b\":2}"))
                .post().uri("https://api.example.com/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}")
                .retrieve().bodyToMono(String.class).block();

        assertEquals("{\"b\":2}", body, "la app debe seguir leyendo el body completo");

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> reqNode = (Map<?, ?>) payload.get("request");
        assertEquals("{\"a\":1}", reqNode.get("body"));
        assertEquals("JSON", reqNode.get("bodyFormat"));

        Map<?, ?> resNode = (Map<?, ?>) payload.get("response");
        assertEquals("{\"b\":2}", resNode.get("body"));
        assertEquals("JSON", resNode.get("bodyFormat"));
    }

    @Test
    void shouldTruncateCapturedBodyAtMaxCaptureBytesAndStillDeliverFullBodyToApp() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        StdlogProperties props = props();
        props.getRestclient().getWebclient().setMaxCaptureBytes(4);

        String big = "{\"data\":\"0123456789\"}";
        String body = client(props, req -> Mono.just(json(200, big)))
                .get().uri("https://api.example.com/big").retrieve().bodyToMono(String.class).block();

        assertEquals(big, body, "la app recibe el body completo aunque el log lo trunque");

        Map<?, ?> resNode = (Map<?, ?>) onlyPayload().get("response");
        assertEquals("{\"da...(truncated)", resNode.get("body"));
    }

    @Test
    void shouldUseFailure4xxLevelFor404() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setInLevelFailure4xx(StdlogLevel.WARN);

        assertThrows(WebClientResponseException.class, () ->
                client(props, req -> Mono.just(json(404, "")))
                        .get().uri("https://api.example.com/missing").retrieve().bodyToMono(String.class).block());

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        assertEquals("FAILURE", StdlogTestSupport.stdlogPayload(appender.list.get(0)).get("outcome"));
    }

    @Test
    void shouldUseFailure5xxLevelForServerError() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        assertThrows(WebClientResponseException.class, () ->
                client(props(), req -> Mono.just(json(500, "")))
                        .get().uri("https://api.example.com/boom").retrieve().bodyToMono(String.class).block());

        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void shouldLogAndRethrowOnConnectionError() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        RuntimeException boom = new RuntimeException("connection refused");

        RuntimeException caught = assertThrows(RuntimeException.class, () ->
                client(props(), req -> Mono.error(boom))
                        .get().uri("https://api.example.com/down").retrieve().bodyToMono(String.class).block());

        assertSame(boom, caught);
        Map<String, Object> payload = onlyPayload();
        assertEquals("FAILURE", payload.get("outcome"));
        assertEquals(500, ((Map<?, ?>) payload.get("http")).get("status"));
        assertNotNull(appender.list.get(0).getThrowableProxy());
    }

    @Test
    void shouldSkipSuccessInProdWhenLogOnlyOnFailureInProdIsEnabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getRestclient().setLogOnlyOnFailureInProd(true);

        client(props, req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok").retrieve().bodyToMono(String.class).block();

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldStillLogFailureInProd() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getRestclient().setLogOnlyOnFailureInProd(true);

        assertThrows(WebClientResponseException.class, () ->
                client(props, req -> Mono.just(json(503, "")))
                        .get().uri("https://api.example.com/fail").retrieve().bodyToMono(String.class).block());

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldIncludeRequestIdAndOperationFromMdcEvenAcrossReactiveThreadHop() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("request_id", "req-9");
        MDC.put("operation", "OrdersController#get");

        client(props(), req -> Mono.just(json(200, "{}")).publishOn(Schedulers.boundedElastic()))
                .get().uri("https://api.example.com/ok").retrieve().bodyToMono(String.class).block();

        Map<String, Object> payload = onlyPayload();
        assertEquals("req-9", payload.get("request_id"));
        assertEquals("OrdersController#get", payload.get("operation"));
    }

    @Test
    void shouldTakeRequestIdFromReactorContextWhenMdcIsEmpty() {
        // ADR-0008 Fase 2: en una app WebFlux no hay MDC; el request_id lo puebla
        // StdlogWebFilter en el Reactor Context.
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        assertNull(MDC.get("request_id"));

        client(props(), req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok")
                .retrieve().bodyToMono(String.class)
                .contextWrite(ctx -> ctx.put("request_id", "ctx-req-1").put("operation", "ReactiveCtrl#get"))
                .block();

        Map<String, Object> payload = onlyPayload();
        assertEquals("ctx-req-1", payload.get("request_id"));
        assertEquals("ReactiveCtrl#get", payload.get("operation"));
    }

    @Test
    void shouldResolveOperationFromServerWebExchangeInReactorContext() throws Exception {
        // ADR-0008 Fase 2b: StdlogWebFilter pone el ServerWebExchange en el Context;
        // el filtro resuelve operation de forma perezosa desde sus atributos HandlerMapping.
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        HandlerMethod hm = new HandlerMethod(new SampleController(), SampleController.class.getMethod("handle"));
        exchange.getAttributes().put(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, hm);

        client(props(), req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok").retrieve().bodyToMono(String.class)
                .contextWrite(ctx -> ctx.put("request_id", "r1")
                        .put(ServerWebExchangeContextFilter.EXCHANGE_CONTEXT_ATTRIBUTE, exchange))
                .block();

        Map<String, Object> p = onlyPayload();
        assertEquals("r1", p.get("request_id"));
        assertEquals("SampleController#handle", p.get("operation"));
    }

    static class SampleController {
        public String handle() {
            return "ok";
        }
    }

    @Test
    void shouldPreferMdcOverReactorContext() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("request_id", "from-mdc");

        client(props(), req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok")
                .retrieve().bodyToMono(String.class)
                .contextWrite(ctx -> ctx.put("request_id", "from-ctx"))
                .block();

        assertEquals("from-mdc", onlyPayload().get("request_id"));
    }

    @Test
    void shouldIncludeTraceAndSpanIdsFromMdc() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("traceId", "trace-wc");
        MDC.put("spanId", "span-wc");

        client(props(), req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok").retrieve().bodyToMono(String.class).block();

        Map<String, Object> payload = onlyPayload();
        assertEquals("trace-wc", payload.get("trace_id"));
        assertEquals("span-wc", payload.get("span_id"));
    }

    @Test
    void shouldOnlyIncludeAllowlistedRequestHeaders() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getRestclient().setLogAllRequestHeaders(false);
        props.getRestclient().setRequestHeadersAllowlist(List.of("x-admin-id"));

        client(props, req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok")
                .header("x-admin-id", "fraudMP")
                .header("authorization", "Bearer secret")
                .retrieve().bodyToMono(String.class).block();

        Map<?, ?> headers = (Map<?, ?>) ((Map<?, ?>) onlyPayload().get("request")).get("headers");
        assertEquals("fraudMP", headers.get("x-admin-id"));
        assertFalse(headers.containsKey("authorization"));
    }

    @Test
    void shouldGenerateCallIdWhenEnabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        client(props(), req -> Mono.just(json(200, "{}")))
                .get().uri("https://api.example.com/ok").retrieve().bodyToMono(String.class).block();

        assertNotNull(onlyPayload().get("call_id"));
    }

    // ---- helpers ----

    private static WebClient client(StdlogProperties props, ExchangeFunction exchange) {
        return WebClient.builder()
                .filter(new StdlogWebClientExchangeFilter(props))
                .exchangeFunction(exchange)
                .build();
    }

    /** Stub que además consume el body del request (como haría un connector real), disparando el tee. */
    private static ExchangeFunction echo(int status, String responseBody) {
        return request -> {
            MockClientHttpRequest sink = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(sink, ExchangeStrategies.withDefaults())
                    .then(Mono.just(json(status, responseBody)));
        };
    }

    private static ClientResponse json(int status, String body) {
        return ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
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
