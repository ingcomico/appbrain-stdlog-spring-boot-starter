package appbrain.stdlog.core;

import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La cadena de resolución de {@code ADR-0013}. Cada regla, su precedencia, y el anuncio.
 *
 * <p>Las reglas 1, 3, 4 y 5 se ejercitan a través de {@link StdlogModeResolver#resolve} para no
 * depender de la variable de entorno {@code STDLOG_MODE}, que un test no puede fijar de forma
 * portable. La regla 2 se cubre observando que el resto de reglas sólo deciden cuando la
 * variable no responde.</p>
 */
class StdlogModeResolverTest {

    private Logger internal;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        StdlogModeResolver.reset();
        internal = (Logger) LoggerFactory.getLogger("appbrain.stdlog.internal");
        appender = new ListAppender<>();
        appender.start();
        internal.addAppender(appender);
        internal.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        internal.detachAppender(appender);
        StdlogModeResolver.reset();
    }

    private static StdlogProperties props(StdlogProperties.Mode mode) {
        StdlogProperties p = new StdlogProperties();
        p.setMode(mode);
        return p;
    }

    // ---------- regla 1: stdlog.mode explícito ----------

    @Test
    void explicitModeWins() {
        assertTrue(StdlogModeResolver.isProd(props(StdlogProperties.Mode.PROD)));
        assertFalse(StdlogModeResolver.isProd(props(StdlogProperties.Mode.NON_PROD)));
    }

    /** Aunque los perfiles digan otra cosa: la decisión del consumidor manda. */
    @Test
    void explicitModeWinsOverProfiles() {
        StdlogModeResolver.configure(StdlogProperties.Mode.NON_PROD, List.of("prod"), Set.of());
        assertFalse(StdlogModeResolver.isProd(props(StdlogProperties.Mode.NON_PROD)));

        assertTrue(StdlogModeResolver.resolve(StdlogProperties.Mode.PROD, List.of("dev"), Set.of()).prod());
    }

    // ---------- regla 3: un perfil activo es productivo ----------

    @Test
    void anActiveProdProfileResolvesToProd() {
        for (String profile : List.of("prod", "production", "prd", "pro")) {
            var resolved = StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of(profile), Set.of());
            assertTrue(resolved.prod(), "perfil " + profile + " debe resolver productivo");
            assertTrue(resolved.reason().contains(profile), resolved.reason());
        }
    }

    @Test
    void profileComparisonIsCaseInsensitive() {
        assertTrue(StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of("PROD"), Set.of()).prod());
        assertTrue(StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of("Production"), Set.of()).prod());
    }

    @Test
    void shouldHonourACustomProdProfileList() {
        var resolved = StdlogModeResolver.resolve(
                StdlogProperties.Mode.AUTO, List.of("produccion"), List.of("produccion", "live"));
        assertTrue(resolved.prod());
    }

    @Test
    void shouldDetectTheProdProfileAmongSeveralActiveOnes() {
        assertTrue(StdlogModeResolver.resolve(
                StdlogProperties.Mode.AUTO, List.of("metrics", "prod", "eu"), Set.of()).prod());
    }

    // ---------- regla 4: hay perfiles y ninguno es productivo ----------

    @Test
    void activeNonProdProfilesResolveToNonProd() {
        for (String profile : List.of("dev", "local", "test", "qa")) {
            var resolved = StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of(profile), Set.of());
            assertFalse(resolved.prod(), "perfil " + profile + " debe resolver no productivo");
        }
    }

    /** Es lo que evita que la regla 5 arruine el arranque local: casi siempre hay un perfil. */
    @Test
    void nonProdProfilesShouldExplainWhy() {
        var resolved = StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of("dev"), Set.of());
        assertTrue(resolved.reason().contains("dev"), resolved.reason());
    }

    // ---------- regla 5: ninguna señal -> productivo ----------

    /**
     * El cambio de default que cierra F-10. Antes de ADR-0013 esto resolvía NO productivo y en
     * silencio, así que un despliegue sin configurar corría con bodies completos y volumen
     * máximo sin que nadie se enterara.
     */
    @Test
    void noSignalAtAllResolvesToProd() {
        var resolved = StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of(), Set.of());
        assertTrue(resolved.prod(), "sin ninguna señal se asume productivo, que es el fallo seguro");
        assertTrue(resolved.reason().contains("seguridad"), resolved.reason());
    }

    @Test
    void nullProfilesAreTreatedAsNoSignal() {
        assertTrue(StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, null, null).prod());
    }

    @Test
    void blankProfilesAreIgnored() {
        assertTrue(StdlogModeResolver.resolve(StdlogProperties.Mode.AUTO, List.of("  ", ""), Set.of()).prod());
    }

    // ---------- el modo se anuncia ----------

    @Test
    void shouldAnnounceTheResolvedModeOnStartup() {
        StdlogModeResolver.configure(StdlogProperties.Mode.AUTO, List.of("prod"), Set.of());

        assertEquals(1, appender.list.size());
        String message = appender.list.get(0).getFormattedMessage();
        assertTrue(message.contains("PROD"), message);
        assertTrue(message.contains("prod"), "debe decir por qué: " + message);
    }

    @Test
    void shouldAnnounceNonProdToo() {
        StdlogModeResolver.configure(StdlogProperties.Mode.AUTO, List.of("dev"), Set.of());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("NON_PROD"),
                appender.list.get(0).getFormattedMessage());
    }

    // ---------- el modo instalado se usa ----------

    @Test
    void shouldUseTheInstalledModeForAutoProperties() {
        StdlogModeResolver.configure(StdlogProperties.Mode.AUTO, List.of("prod"), Set.of());
        assertTrue(StdlogModeResolver.isProd(props(StdlogProperties.Mode.AUTO)));

        StdlogModeResolver.reset();
        StdlogModeResolver.configure(StdlogProperties.Mode.AUTO, List.of("dev"), Set.of());
        assertFalse(StdlogModeResolver.isProd(props(StdlogProperties.Mode.AUTO)));
    }

    /** Durante el arranque, antes de que la autoconfiguración corra, no puede haber indefinición. */
    @Test
    void shouldNotBeUndefinedBeforeTheAutoConfigurationRuns() {
        StdlogModeResolver.reset();
        assertDoesNotThrow(() -> StdlogModeResolver.isProd(props(StdlogProperties.Mode.AUTO)));
        assertDoesNotThrow(() -> StdlogModeResolver.isProd(null));
    }
}
