package appbrain.stdlog.webflux;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogWebFilterTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    private WebTestClient client(StdlogProperties props) {
        return WebTestClient.bindToController(new TestController())
                .webFilter(new StdlogWebFilter(props))
                .build();
    }

    @Test
    void shouldNotLogWhenWebfluxDisabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getController().getWebflux().setEnabled(false);

        client(props).get().uri("/ok").exchange().expectStatus().isOk();

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldEmitControllerHttpInAndOutForGet() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);

        client(props()).get().uri("/ok?q=1").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id");

        Map<String, Object> in = payloadOf("IN");
        assertEquals("CONTROLLER_HTTP", in.get("event"));
        assertEquals("GET", ((Map<?, ?>) in.get("http")).get("method"));
        assertEquals("/ok?q=1", ((Map<?, ?>) in.get("http")).get("fullPath"));
        assertEquals("TestController#ok", in.get("operation"));
        assertEquals("GET /ok", in.get("route"));
        assertNotNull(in.get("request_id"));
        assertEquals("1", ((Map<?, ?>) ((Map<?, ?>) in.get("request")).get("queryParams")).get("q"));

        Map<String, Object> out = payloadOf("OUT");
        assertEquals("SUCCESS", out.get("outcome"));
        assertEquals(200, ((Map<?, ?>) out.get("http")).get("status"));
        assertNotNull(out.get("elapsedMs"));
        assertEquals("TestController#ok", out.get("operation"));
    }

    @Test
    void shouldReuseIncomingRequestIdHeader() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);

        client(props()).get().uri("/ok")
                .header("x-request-id", "req-abc")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", "req-abc");

        assertEquals("req-abc", payloadOf("IN").get("request_id"));
    }

    @Test
    void shouldCaptureRequestAndResponseBodyInDebug() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);

        client(props()).post().uri("/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}")
                .exchange()
                .expectStatus().isOk();

        Map<?, ?> reqNode = (Map<?, ?>) payloadOf("IN").get("request");
        assertEquals("{\"a\":1}", reqNode.get("body"));
        assertEquals("JSON", reqNode.get("bodyFormat"));

        Map<?, ?> resNode = (Map<?, ?>) payloadOf("OUT").get("response");
        assertEquals("{\"a\":1}", resNode.get("body"));
    }

    @Test
    void shouldEmitErrorEventFor500() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        client(props()).get().uri("/boom").exchange().expectStatus().is5xxServerError();

        Map<String, Object> err = payloadOf("ERROR");
        assertEquals(500, ((Map<?, ?>) err.get("http")).get("status"));
        Map<?, ?> errNode = (Map<?, ?>) err.get("error");
        assertEquals("java.lang.IllegalStateException", errNode.get("type"));
        assertEquals("kaboom", errNode.get("message"));

        ILoggingEvent errEvent = appender.list.stream()
                .filter(e -> "ERROR".equals(StdlogTestSupport.stdlogPayload(e).get("event")))
                .findFirst().orElseThrow();
        assertEquals(Level.ERROR, errEvent.getLevel());
        assertNotNull(errEvent.getThrowableProxy());
    }

    @Test
    void shouldEmitWarnEventFor404() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        client(props()).get().uri("/missing").exchange().expectStatus().isNotFound();

        Map<String, Object> warn = payloadOf("WARN");
        assertEquals(404, ((Map<?, ?>) warn.get("http")).get("status"));
    }

    @Test
    void shouldSuppressInfoEventsForExcludedPathButKeepErrors() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getController().setExcludedPathPatterns(List.of("/health/**"));

        client(props).get().uri("/health/live").exchange();

        assertTrue(appender.list.stream()
                .map(StdlogTestSupport::stdlogPayload)
                .noneMatch(p -> "CONTROLLER_HTTP".equals(p.get("event"))),
                "los CONTROLLER_HTTP (INFO) deben suprimirse en un path excluido");
    }

    // ---- helpers ----

    private static StdlogProperties props() {
        StdlogProperties p = new StdlogProperties();
        p.getController().setEnabled(true);
        return p;
    }

    private Map<String, Object> payloadOf(String eventOrDirection) {
        return appender.list.stream()
                .map(StdlogTestSupport::stdlogPayload)
                .filter(p -> eventOrDirection.equals(p.get("direction")) || eventOrDirection.equals(p.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no se encontró evento/direction " + eventOrDirection
                        + " en " + appender.list.stream().map(StdlogTestSupport::stdlogPayload).toList()));
    }

    @RestController
    static class TestController {
        @GetMapping("/ok")
        Mono<String> ok() {
            return Mono.just("ok");
        }

        @PostMapping("/echo")
        Mono<String> echo(@RequestBody String body) {
            return Mono.just(body);
        }

        @GetMapping("/boom")
        Mono<String> boom() {
            throw new IllegalStateException("kaboom");
        }

        @GetMapping("/health/live")
        Mono<String> health() {
            return Mono.just("UP");
        }
    }
}
