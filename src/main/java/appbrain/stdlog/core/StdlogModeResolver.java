package appbrain.stdlog.core;

import appbrain.stdlog.config.StdlogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resuelve si la aplicación está ejecutándose en modo productivo (`ADR-0013`).
 *
 * <h2>Cadena de resolución</h2>
 * <p>Se evalúa en orden y la primera regla que responde gana:</p>
 * <ol>
 *   <li>{@code stdlog.mode} con valor explícito ({@code PROD} / {@code NON_PROD}).</li>
 *   <li>Variable de entorno {@code STDLOG_MODE} ({@code PROD}, {@code NON_PROD}, {@code NONPROD}).</li>
 *   <li>Perfiles activos de Spring que coincidan con {@code stdlog.prod-profiles} → productivo.</li>
 *   <li>Hay perfiles activos y ninguno coincide → no productivo ({@code dev}, {@code local}…).</li>
 *   <li>Ninguna señal → <b>productivo</b>.</li>
 * </ol>
 *
 * <h2>Por qué la regla 5 falla hacia productivo</h2>
 * <p>El error tiene coste asimétrico. Equivocarse hacia «no productivo» en producción significa
 * bodies completos, volumen máximo y más superficie de datos sensibles, y <b>no se nota</b>.
 * Equivocarse hacia «productivo» en local significa ver menos logs, se nota enseguida y se
 * corrige con una property. Cuando un default puede fallar en dos direcciones, se elige la que
 * avisa. Antes de {@code ADR-0013} el default era el contrario y era silencioso: ese era el
 * hallazgo F-10.</p>
 *
 * <h2>Se resuelve una sola vez</h2>
 * <p>El modo no cambia durante la vida del proceso. {@code StdlogModeAutoConfiguration} lo
 * resuelve al arrancar y lo instala aquí, con el mismo patrón que {@code StdlogMasker} y
 * {@code StdlogFailsafe}. Antes se hacía un {@code System.getenv(...)} <b>por evento</b> en los
 * cuatro puntos que consultan el modo.</p>
 */
public final class StdlogModeResolver {

    private static final String ENV_STDLOG_MODE = "STDLOG_MODE";

    /** Perfiles que se consideran productivos si el consumidor no configura otros. */
    public static final Set<String> DEFAULT_PROD_PROFILES =
            Set.of("prod", "production", "prd", "pro");

    private static final Logger INTERNAL = LoggerFactory.getLogger("appbrain.stdlog.internal");

    /** {@code null} hasta que la autoconfiguración instala el modo resuelto. */
    private static volatile Resolved resolved;

    private StdlogModeResolver() {}

    /**
     * Determina si el entorno actual es productivo.
     *
     * @param props configuración del starter; {@code stdlog.mode} explícito manda sobre todo lo demás
     * @return {@code true} si el entorno es productivo
     */
    public static boolean isProd(StdlogProperties props) {
        if (props != null && props.getMode() != null) {
            switch (props.getMode()) {
                case PROD: return true;
                case NON_PROD: return false;
                case AUTO:
                default: break;
            }
        }

        Resolved current = resolved;
        if (current != null) return current.prod();

        // Todavía no se instaló el modo: estamos en pleno arranque. Se usa la señal que existe
        // sin depender del contexto de Spring, para no dejar un estado indefinido.
        Boolean fromEnv = fromEnvironmentVariable();
        return fromEnv != null ? fromEnv : true;
    }

    /**
     * Resuelve la cadena completa e instala el resultado. La llama la autoconfiguración al
     * arrancar; no es API para el código de negocio.
     *
     * @param mode         valor de {@code stdlog.mode}
     * @param activeProfiles perfiles activos de Spring; puede ser vacío
     * @param prodProfiles perfiles considerados productivos
     */
    public static void configure(StdlogProperties.Mode mode,
            Collection<String> activeProfiles,
            Collection<String> prodProfiles) {

        Resolved result = resolve(mode, activeProfiles, prodProfiles);
        resolved = result;

        // Un default que decide por ti no puede ser ademas invisible: la regla 5 solo es
        // aceptable si se ve. Ver ADR-0013.
        INTERNAL.info("stdlog: modo {} ({}).", result.prod() ? "PROD" : "NON_PROD", result.reason());
    }

    /** Reinicia el modo instalado. Uso previsto: aislamiento entre tests. */
    public static void reset() {
        resolved = null;
    }

    static Resolved resolve(StdlogProperties.Mode mode,
            Collection<String> activeProfiles,
            Collection<String> prodProfiles) {

        // 1. stdlog.mode explícito
        if (mode == StdlogProperties.Mode.PROD) return new Resolved(true, "stdlog.mode=PROD");
        if (mode == StdlogProperties.Mode.NON_PROD) return new Resolved(false, "stdlog.mode=NON_PROD");

        // 2. variable de entorno
        Boolean fromEnv = fromEnvironmentVariable();
        if (fromEnv != null) {
            return new Resolved(fromEnv, "variable de entorno STDLOG_MODE");
        }

        Set<String> active = normalize(activeProfiles);
        Set<String> prod = normalize(prodProfiles);
        if (prod.isEmpty()) prod = DEFAULT_PROD_PROFILES;

        // 3. algún perfil activo es productivo
        for (String profile : active) {
            if (prod.contains(profile)) {
                return new Resolved(true, "perfil activo '" + profile + "' esta en stdlog.prod-profiles");
            }
        }

        // 4. hay perfiles y ninguno es productivo
        if (!active.isEmpty()) {
            return new Resolved(false, "perfiles activos " + active + " y ninguno esta en stdlog.prod-profiles");
        }

        // 5. ninguna señal
        return new Resolved(true, "sin stdlog.mode, sin STDLOG_MODE y sin perfiles activos: "
                + "se asume productivo por seguridad; ver ADR-0013");
    }

    /** {@code null} si la variable no está definida o trae un valor que no se reconoce. */
    private static Boolean fromEnvironmentVariable() {
        String forced = System.getenv(ENV_STDLOG_MODE);
        if (forced == null || forced.isBlank()) return null;

        String value = forced.trim().toUpperCase(Locale.ROOT);
        if ("PROD".equals(value)) return true;
        if ("NON_PROD".equals(value) || "NONPROD".equals(value)) return false;
        return null;
    }

    private static Set<String> normalize(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Modo resuelto y el motivo por el que se resolvió así, para poder anunciarlo. */
    record Resolved(boolean prod, String reason) {}
}
