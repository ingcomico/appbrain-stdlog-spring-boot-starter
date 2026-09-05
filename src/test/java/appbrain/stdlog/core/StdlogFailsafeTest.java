package appbrain.stdlog.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StdlogFailsafeTest {

    private Logger internal;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        StdlogFailsafe.resetFailureCount();
        internal = (Logger) LoggerFactory.getLogger("appbrain.stdlog.internal");
        appender = new ListAppender<>();
        appender.start();
        internal.addAppender(appender);
        internal.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        internal.detachAppender(appender);
        StdlogFailsafe.resetFailureCount();
    }

    // ---------- la invariante ----------

    @Test
    void shouldSwallowRuntimeExceptionSoTheOperationContinues() {
        assertDoesNotThrow(() -> StdlogFailsafe.run(() -> {
            throw new IllegalStateException("fallo construyendo el payload");
        }));
    }

    @Test
    void shouldSwallowLinkageError() {
        assertDoesNotThrow(() -> StdlogFailsafe.run(() -> {
            throw new NoClassDefFoundError("classpath incompleto");
        }));
    }

    /**
     * Tragarse un {@code OutOfMemoryError} para salvar una línea de log sería peor que el fallo
     * que se intenta evitar: los {@code Error} no recuperables deben propagarse.
     */
    @Test
    void shouldNotSwallowUnrecoverableErrors() {
        assertThrows(OutOfMemoryError.class, () -> StdlogFailsafe.run(() -> {
            throw new OutOfMemoryError("sin heap");
        }));
        assertThrows(StackOverflowError.class, () -> StdlogFailsafe.run(() -> {
            throw new StackOverflowError();
        }));
        assertEquals(0, StdlogFailsafe.failureCount(), "un Error no recuperable no cuenta como fallo capturado");
    }

    @Test
    void shouldReturnTheFallbackWhenTheEmissionFails() {
        String value = StdlogFailsafe.call(() -> { throw new IllegalStateException("boom"); }, "respuesta-original");
        assertEquals("respuesta-original", value);
    }

    @Test
    void shouldReturnTheValueWhenTheEmissionSucceeds() {
        assertEquals("ok", StdlogFailsafe.call(() -> "ok", "fallback"));
    }

    @Test
    void shouldRunTheEmissionExactlyOnceWhenItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        StdlogFailsafe.run(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    // ---------- nunca en silencio, pero con freno ----------

    @Test
    void shouldReportTheFirstFailure() {
        StdlogFailsafe.run(() -> { throw new IllegalStateException("boom"); });

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNotNull(event.getThrowableProxy(), "la excepción debe viajar con el aviso");
        assertEquals("java.lang.IllegalStateException", event.getThrowableProxy().getClassName());
    }

    @Test
    void shouldNotUseTheStdlogLoggerToAvoidRecursion() {
        ListAppender<ILoggingEvent> stdlogAppender = new ListAppender<>();
        stdlogAppender.start();
        Logger stdlog = (Logger) LoggerFactory.getLogger("stdlog");
        stdlog.addAppender(stdlogAppender);
        try {
            StdlogFailsafe.run(() -> { throw new IllegalStateException("boom"); });
            assertTrue(stdlogAppender.list.isEmpty(),
                    "el aviso no puede ir al logger stdlog: si el fallo está en la propia ruta de emisión, recursaría");
        } finally {
            stdlog.detachAppender(stdlogAppender);
        }
    }

    /** Un fallo sistemático no puede convertir el aviso en la inundación que se quería evitar. */
    @Test
    void shouldThrottleRepeatedFailuresToPowersOfTen() {
        for (int i = 0; i < 1000; i++) {
            StdlogFailsafe.run(() -> { throw new IllegalStateException("boom"); });
        }

        assertEquals(1000, StdlogFailsafe.failureCount(), "se cuentan todos");
        assertEquals(4, appender.list.size(), "pero sólo se avisa en el 1, 10, 100 y 1000");
    }

    @Test
    void shouldIncludeTheAccumulatedTotalInTheWarning() {
        for (int i = 0; i < 10; i++) {
            StdlogFailsafe.run(() -> { throw new IllegalStateException("boom"); });
        }

        assertEquals(2, appender.list.size());
        assertTrue(appender.list.get(1).getFormattedMessage().contains("10"),
                "el aviso debe decir cuántos fallos van acumulados: " + appender.list.get(1).getFormattedMessage());
    }

    @Test
    void shouldIgnoreANullFailure() {
        StdlogFailsafe.report(null);
        assertEquals(0, StdlogFailsafe.failureCount());
        assertTrue(appender.list.isEmpty());
    }
}
