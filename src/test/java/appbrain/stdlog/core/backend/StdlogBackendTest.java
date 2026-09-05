package appbrain.stdlog.core.backend;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogBackendTest {

    private Logger internal;
    private ListAppender<ILoggingEvent> internalAppender;

    @BeforeEach
    void setUp() {
        StdlogBackend.reset();
        internal = (Logger) LoggerFactory.getLogger("appbrain.stdlog.internal");
        internalAppender = new ListAppender<>();
        internalAppender.start();
        internal.addAppender(internalAppender);
        internal.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        internal.detachAppender(internalAppender);
        StdlogBackend.reset();
    }

    /** En la suite, SLF4J está enlazado a Logback y el encoder está presente. */
    @Test
    void shouldDetectLogbackInThisSuite() {
        assertInstanceOf(LogbackEventWriter.class, StdlogBackend.detect());
    }

    @Test
    void shouldResolveTheWriterOnlyOnceAndReuseIt() {
        assertSame(StdlogBackend.writer(), StdlogBackend.writer());
    }

    @Test
    void shouldAnnounceTheDetectedBackendOnStartup() {
        StdlogBackend.detectAndAnnounce();

        assertEquals(1, internalAppender.list.size());
        ILoggingEvent event = internalAppender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("Logback"), event.getFormattedMessage());
    }

    /**
     * El fallo que este ADR viene a cubrir: con un backend no soportado no se rompe nada
     * —ADR-0011 no se negocia— pero tampoco se calla, porque callarlo es cómo se llega a
     * descubrir semanas tarde que los logs estructurados no existían.
     */
    @Test
    void shouldWarnLoudlyWhenTheBackendIsNotSupported() {
        StdlogBackend.install(new FallbackEventWriter("org.example.UnknownLoggerFactory"));

        StdlogBackend.detectAndAnnounce();

        assertEquals(1, internalAppender.list.size());
        ILoggingEvent event = internalAppender.list.get(0);
        assertEquals(Level.WARN, event.getLevel(), "un backend no soportado debe avisar, no informar");
        String message = event.getFormattedMessage();
        assertTrue(message.contains("no soportado"), message);
        assertTrue(message.contains("ADR-0014"), "debe decir dónde leer la decisión: " + message);
    }

    /** Y sobre todo: el respaldo NO pierde el evento, que es lo que pasaba antes. */
    @Test
    void theFallbackMustNotLoseTheEvent() {
        ListAppender<ILoggingEvent> stdlogAppender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "CONTROLLER_HTTP");
            payload.put("request_id", "abc-123");

            new FallbackEventWriter("desconocido")
                    .write(LoggerFactory.getLogger("stdlog"), StdlogLevel.INFO, payload, null);

            assertEquals(1, stdlogAppender.list.size());
            String message = stdlogAppender.list.get(0).getFormattedMessage();
            assertTrue(message.contains("CONTROLLER_HTTP"), message);
            assertTrue(message.contains("abc-123"),
                    "el respaldo degrada el formato, pero no puede perder el contenido: " + message);
        } finally {
            StdlogTestSupport.detach(stdlogAppender);
        }
    }

    @Test
    void theFallbackMustCarryTheThrowable() {
        ListAppender<ILoggingEvent> stdlogAppender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            new FallbackEventWriter("desconocido").write(
                    LoggerFactory.getLogger("stdlog"), StdlogLevel.ERROR,
                    new LinkedHashMap<>(Map.of("event", "X")), new IllegalStateException("boom"));

            assertNotNull(stdlogAppender.list.get(0).getThrowableProxy());
        } finally {
            StdlogTestSupport.detach(stdlogAppender);
        }
    }

    @Test
    void describeShouldIdentifyEachWriter() {
        assertTrue(new LogbackEventWriter().describe().contains("Logback"));
        assertTrue(new Log4j2EventWriter().describe().contains("Log4j2"));
        assertTrue(new FallbackEventWriter("x").describe().contains("respaldo"));
    }
}
