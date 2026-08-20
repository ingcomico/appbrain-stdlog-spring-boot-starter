package appbrain.stdlog.core;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogEmitterTest {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(StdlogLevel.class)
    void shouldEmitAtEveryLevelWhenLoggerAllowsIt(StdlogLevel level) {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        StdlogEmitter.emit(STDLOG, level, Map.of("event", "TEST_" + level));

        assertEquals(1, appender.list.size());
        Map<String, Object> payload = StdlogTestSupport.stdlogPayload(appender.list.get(0));
        assertEquals("TEST_" + level, payload.get("event"));
    }

    @Test
    void shouldNotEmitWhenLoggerLevelIsAboveEventLevel() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.ERROR);

        StdlogEmitter.emit(STDLOG, StdlogLevel.INFO, Map.of("event", "SHOULD_NOT_APPEAR"));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldEmitWithThrowableAttached() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        RuntimeException ex = new RuntimeException("boom");

        StdlogEmitter.emit(STDLOG, StdlogLevel.ERROR, Map.of("event", "FAILED"), ex);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertNotNull(event.getThrowableProxy());
        assertEquals("boom", event.getThrowableProxy().getMessage());
    }

    @Test
    void shouldNoOpWhenLoggerIsNull() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        assertDoesNotThrow(() -> StdlogEmitter.emit(null, StdlogLevel.INFO, Map.of("event", "X")));
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldNoOpWhenLevelIsNull() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        assertDoesNotThrow(() -> StdlogEmitter.emit(STDLOG, null, Map.of("event", "X")));
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldNoOpWhenPayloadIsNull() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        assertDoesNotThrow(() -> StdlogEmitter.emit(STDLOG, StdlogLevel.INFO, null));
        assertTrue(appender.list.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = StdlogLevel.class, names = {"TRACE", "DEBUG", "INFO"})
    void shouldSuppressTraceDebugAndInfoWhenMdcExclusionFlagIsSet(StdlogLevel level) {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");

        StdlogEmitter.emit(STDLOG, level, Map.of("event", "SHOULD_BE_SUPPRESSED"));

        assertTrue(appender.list.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = StdlogLevel.class, names = {"WARN", "ERROR"})
    void shouldNeverSuppressWarnOrErrorEvenWithMdcExclusionFlagSet(StdlogLevel level) {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");

        StdlogEmitter.emit(STDLOG, level, Map.of("event", "SHOULD_STILL_APPEAR"));

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldSuppressInfoWithThrowableOverloadWhenMdcExclusionFlagIsSet() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");

        StdlogEmitter.emit(STDLOG, StdlogLevel.INFO, Map.of("event", "X"), new RuntimeException("irrelevant"));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldNotSuppressErrorWithThrowableOverloadWhenMdcExclusionFlagIsSet() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");

        StdlogEmitter.emit(STDLOG, StdlogLevel.ERROR, Map.of("event", "X"), new RuntimeException("boom"));

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldEmitNormallyWhenMdcExclusionFlagIsNotSet() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        StdlogEmitter.emit(STDLOG, StdlogLevel.INFO, Map.of("event", "X"));

        assertEquals(1, appender.list.size());
    }
}
