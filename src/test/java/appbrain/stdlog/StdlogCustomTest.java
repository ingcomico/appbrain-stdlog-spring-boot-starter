package appbrain.stdlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogCustomTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void infoShouldEmitEventWithoutOutcome() {
        StdlogCustom.info("TAG_CREATED", Map.of("id", 10));

        Map<String, Object> payload = onlyPayload();
        assertEquals("TAG_CREATED", payload.get("event"));
        assertFalse(payload.containsKey("outcome"));
        assertEquals(Map.of("id", 10), payload.get("custom"));
    }

    @Test
    void warnShouldEmitEventWithoutOutcome() {
        StdlogCustom.warn("RETRY_ATTEMPT", Map.of("attempt", 2));

        Map<String, Object> payload = onlyPayload();
        assertEquals("RETRY_ATTEMPT", payload.get("event"));
        assertFalse(payload.containsKey("outcome"));
    }

    @Test
    void debugShouldEmitEventWithoutOutcome() {
        StdlogCustom.debug("TRACE_STEP", Map.of("step", "validate"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("TRACE_STEP", payload.get("event"));
        assertFalse(payload.containsKey("outcome"));
    }

    @Test
    void successShouldEmitOutcomeSuccess() {
        StdlogCustom.success("OP_OK", Map.of("result", "ok"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("SUCCESS", payload.get("outcome"));
    }

    @Test
    void failureShouldEmitOutcomeFailureWithThrowable() {
        RuntimeException ex = new RuntimeException("db down");
        StdlogCustom.failure("OP_FAIL", Map.of("result", "error"), ex);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        Map<String, Object> payload = StdlogTestSupport.stdlogPayload(event);
        assertEquals("FAILURE", payload.get("outcome"));
        assertNotNull(event.getThrowableProxy());
    }

    @Test
    void errorShouldEmitCustomOutcome() {
        RuntimeException ex = new RuntimeException("timeout");
        StdlogCustom.error("UPSTREAM_CALL", "TIMEOUT", Map.of("peer", "users"), ex);

        Map<String, Object> payload = onlyPayload();
        assertEquals("TIMEOUT", payload.get("outcome"));
        assertEquals("UPSTREAM_CALL", payload.get("event"));
    }

    @Test
    void shouldIncludeOperationAndRequestIdFromMdc() {
        MDC.put("operation", "TagsController#searchTags");
        MDC.put("request_id", "uuid-123");

        StdlogCustom.info("TAG_CREATED", Map.of("id", 10));

        Map<String, Object> payload = onlyPayload();
        assertEquals("TagsController#searchTags", payload.get("operation"));
        assertEquals("uuid-123", payload.get("request_id"));
    }

    @Test
    void shouldIncludeTraceAndSpanIdsFromMdc() {
        MDC.put("traceId", "trace-custom");
        MDC.put("spanId", "span-custom");

        StdlogCustom.info("TAG_CREATED", Map.of("id", 10));

        Map<String, Object> payload = onlyPayload();
        assertEquals("trace-custom", payload.get("trace_id"));
        assertEquals("span-custom", payload.get("span_id"));
    }

    @Test
    void shouldOmitOperationAndRequestIdWhenMdcIsEmpty() {
        StdlogCustom.info("TAG_CREATED", Map.of("id", 10));

        Map<String, Object> payload = onlyPayload();
        assertFalse(payload.containsKey("operation"));
        assertFalse(payload.containsKey("request_id"));
    }

    @Test
    void shouldOmitCustomKeyWhenPayloadIsEmpty() {
        StdlogCustom.info("NO_PAYLOAD", Map.of());

        Map<String, Object> payload = onlyPayload();
        assertFalse(payload.containsKey("custom"));
    }

    @Test
    void shouldOmitCustomKeyWhenPayloadIsNull() {
        StdlogCustom.info("NO_PAYLOAD", null);

        Map<String, Object> payload = onlyPayload();
        assertFalse(payload.containsKey("custom"));
    }

    @Test
    void shouldNotEmitWhenEventIsBlank() {
        StdlogCustom.info("   ", Map.of("id", 1));
        StdlogCustom.info(null, Map.of("id", 1));

        assertTrue(appender.list.isEmpty());
    }

    private Map<String, Object> onlyPayload() {
        assertEquals(1, appender.list.size());
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }
}
