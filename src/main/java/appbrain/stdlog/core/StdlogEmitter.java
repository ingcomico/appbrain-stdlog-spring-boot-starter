package appbrain.stdlog.core;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.core.backend.StdlogBackend;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Punto central de emisión de eventos stdlog hacia SLF4J/Logback.
 *
 * <p>Envuelve el payload bajo la clave {@code "stdlog"} usando el marker
 * {@code logstash-logback-encoder}, lo que produce el campo raíz {@code stdlog}
 * en el JSON de salida. Verifica el nivel habilitado antes de loguear
 * para evitar serialización innecesaria.</p>
 *
 * <p>Todos los módulos (web, restclient, jdbc, custom) pasan por aquí
 * para garantizar un formato consistente. Antes de emitir, agrega
 * {@code trace_id}/{@code span_id} al payload cuando puede resolverlos desde
 * MDC u OpenTelemetry.</p>
 *
 * <p><b>Exclusión de request/handler ({@code @StdlogExcluded} o
 * {@code excluded-path-patterns}):</b> cuando el MDC contiene la key
 * {@link #MDC_EXCLUDED}, se suprimen los eventos de nivel {@code TRACE},
 * {@code DEBUG} o {@code INFO} — {@code WARN} y {@code ERROR} nunca se suprimen,
 * independientemente de la exclusión. Esto aplica de forma uniforme a todos los
 * módulos porque todos emiten a través de este punto central; ver
 * {@code ControllerBodyAndOutLoggingFilter} y {@code StdlogMvcOperationInterceptor}
 * para dónde se setea la key.</p>
 */
public final class StdlogEmitter {

    /**
     * Key del MDC que marca el request/handler actual como excluido del logging
     * "silencioso" (niveles TRACE/DEBUG/INFO). Setear a cualquier valor no nulo
     * para activarla; {@code WARN}/{@code ERROR} siempre se emiten igual.
     */
    public static final String MDC_EXCLUDED = "stdlog.excluded";

    private StdlogEmitter() {}

    /**
     * Emite un evento stdlog sin excepción asociada.
     *
     * @param logger  logger SLF4J destino (normalmente {@code LoggerFactory.getLogger("stdlog")})
     * @param level   nivel de severidad del evento
     * @param stdlog  payload del evento; se emite bajo la clave {@code "stdlog"} en el JSON
     */
    public static void emit(Logger logger, StdlogLevel level, Map<String, Object> stdlog) {
        if (logger == null || level == null || stdlog == null || isSuppressedByExclusion(level)
                || !isEnabled(logger, level)) return;

        // Red de seguridad de ADR-0011: cubre el enmascarado, el enriquecimiento y la escritura.
        // No alcanza la CONSTRUCCION del payload, que ocurre antes en cada modulo; por eso cada
        // punto de instrumentacion envuelve ademas su propio bloque.
        final Map<String, Object> payload = stdlog;
        StdlogFailsafe.run(() -> {
            // Orden deliberado: primero enmascarar, luego enriquecer. El enmascarado va después
            // del chequeo de nivel para no pagarlo en eventos que no se van a emitir (ADR-0010).
            Map<String, Object> out = StdlogTraceCorrelation.enrich(StdlogMasker.mask(payload));
            StdlogBackend.writer().write(logger, level, out, null);
        });
    }

    /**
     * Emite un evento stdlog con excepción asociada.
     * El {@link Throwable} se pasa directamente al encoder de Logback, lo que produce
     * un stack trace estándar cliqueable en IDEs junto al JSON del evento.
     *
     * @param logger  logger SLF4J destino
     * @param level   nivel de severidad del evento
     * @param stdlog  payload del evento bajo la clave {@code "stdlog"}
     * @param t       excepción asociada al evento; puede ser {@code null}
     */
    public static void emit(Logger logger, StdlogLevel level, Map<String, Object> stdlog, Throwable t) {
        if (logger == null || level == null || stdlog == null || isSuppressedByExclusion(level)
                || !isEnabled(logger, level)) return;

        // Red de seguridad de ADR-0011; ver la nota del método anterior.
        final Map<String, Object> payload = stdlog;
        StdlogFailsafe.run(() -> {
            Map<String, Object> out = StdlogTraceCorrelation.enrich(StdlogMasker.mask(payload));
            StdlogBackend.writer().write(logger, level, out, t);
        });
    }

    private static boolean isEnabled(Logger logger, StdlogLevel level) {
        return switch (level) {
            case TRACE -> logger.isTraceEnabled();
            case DEBUG -> logger.isDebugEnabled();
            case INFO -> logger.isInfoEnabled();
            case WARN -> logger.isWarnEnabled();
            case ERROR -> logger.isErrorEnabled();
        };
    }

    private static boolean isSuppressedByExclusion(StdlogLevel level) {
        if (level == StdlogLevel.WARN || level == StdlogLevel.ERROR) return false;
        return MDC.get(MDC_EXCLUDED) != null;
    }
}
