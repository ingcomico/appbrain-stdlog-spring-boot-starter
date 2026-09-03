package appbrain.stdlog.webflux;

import appbrain.stdlog.StdlogTestSupport;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogCustomReactiveTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void shouldEmitCustomEventWithCorrelationFromReactorContext() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        assertNull(MDC.get("request_id"));

        String result = Mono.just("x")
                .flatMap(x -> StdlogCustomReactive.success("PAGO_OK", Map.of("id", 7)).thenReturn(x))
                .contextWrite(ctx -> ctx.put("request_id", "req-77").put("operation", "PagosCtrl#pagar"))
                .block();

        assertEquals("x", result);
        Map<String, Object> p = onlyPayload();
        assertEquals("PAGO_OK", p.get("event"));
        assertEquals("SUCCESS", p.get("outcome"));
        assertEquals("req-77", p.get("request_id"));
        assertEquals("PagosCtrl#pagar", p.get("operation"));
        assertEquals(7, ((Map<?, ?>) p.get("custom")).get("id"));
        assertNull(MDC.get("request_id"), "no deja el MDC sucio");
    }

    @Test
    void shouldEmitFailureWithThrowable() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        RuntimeException boom = new RuntimeException("nope");

        StdlogCustomReactive.failure("PAGO_FAIL", Map.of("id", 1), boom)
                .contextWrite(ctx -> ctx.put("request_id", "r1"))
                .block();

        Map<String, Object> p = onlyPayload();
        assertEquals("FAILURE", p.get("outcome"));
        assertEquals("r1", p.get("request_id"));
        assertNotNull(appender.list.get(0).getThrowableProxy());
    }

    @Test
    void shouldEmitEvenWithoutAnyContext() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        StdlogCustomReactive.info("PLAIN", Map.of("k", "v")).block();

        Map<String, Object> p = onlyPayload();
        assertEquals("PLAIN", p.get("event"));
        assertFalse(p.containsKey("request_id"));
    }

    private Map<String, Object> onlyPayload() {
        assertEquals(1, appender.list.size());
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }
}
