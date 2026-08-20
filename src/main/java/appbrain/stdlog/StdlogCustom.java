package appbrain.stdlog;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.core.StdlogEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API estática para emitir eventos de negocio bajo el schema {@code stdlog}.
 *
 * <p>Permite que cualquier capa de la aplicación (servicio, repositorio, handler)
 * emita eventos estructurados sin acoplarse a SLF4J ni a los detalles del encoder.
 * Si se invoca dentro del contexto de un request HTTP (MDC activo), los campos
 * {@code operation} y {@code request_id} se incluyen automáticamente.</p>
 *
 * <p>El JSON emitido sigue el esquema:</p>
 * <pre>{@code
 * {
 *   "stdlog": {
 *     "event":      "<nombre del evento>",
 *     "outcome":    "SUCCESS | FAILURE",   // solo en success() / failure() / error()
 *     "operation":  "<desde MDC>",         // presente si hay request activo
 *     "request_id": "<desde MDC>",         // presente si hay request activo
 *     "custom": { ... }                    // payload provisto por la aplicación
 *   }
 * }
 * }</pre>
 *
 * <p>Ejemplo de uso:</p>
 * <pre>{@code
 * StdlogCustom.info("TAG_CREATED", Map.of("id", tag.getId(), "site", tag.getSiteId()));
 * StdlogCustom.success("PAYMENT_PROCESSED", Map.of("amount", 1500, "currency", "ARS"));
 * StdlogCustom.failure("RULE_EVALUATION_FAILED", Map.of("ruleId", id), ex);
 * }</pre>
 */
public final class StdlogCustom {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");

    private StdlogCustom() {}

    /**
     * Emite un evento informativo sin {@code outcome}.
     *
     * @param event   nombre del evento de negocio (ej. {@code "TAG_CREATED"})
     * @param payload datos adicionales que se incluyen bajo la clave {@code custom}
     */
    public static void info(String event, Map<String, Object> payload) {
        emit(StdlogLevel.INFO, event, null, payload, null);
    }

    /**
     * Emite un evento de advertencia sin {@code outcome}.
     *
     * @param event   nombre del evento de negocio
     * @param payload datos adicionales bajo {@code custom}
     */
    public static void warn(String event, Map<String, Object> payload) {
        emit(StdlogLevel.WARN, event, null, payload, null);
    }

    /**
     * Emite un evento de depuración sin {@code outcome}.
     * Solo se imprime si {@code logging.level.stdlog=DEBUG}.
     *
     * @param event   nombre del evento de negocio
     * @param payload datos adicionales bajo {@code custom}
     */
    public static void debug(String event, Map<String, Object> payload) {
        emit(StdlogLevel.DEBUG, event, null, payload, null);
    }

    /**
     * Emite un evento de éxito con {@code outcome=SUCCESS} a nivel {@code INFO}.
     *
     * @param event   nombre del evento de negocio
     * @param payload datos adicionales bajo {@code custom}
     */
    public static void success(String event, Map<String, Object> payload) {
        emit(StdlogLevel.INFO, event, "SUCCESS", payload, null);
    }

    /**
     * Emite un evento de falla con {@code outcome=FAILURE} a nivel {@code ERROR}.
     * El {@link Throwable} se pasa al encoder para que el stack trace sea cliqueable en IDEs.
     *
     * @param event   nombre del evento de negocio
     * @param payload datos adicionales bajo {@code custom}
     * @param t       excepción que causó la falla; puede ser {@code null}
     */
    public static void failure(String event, Map<String, Object> payload, Throwable t) {
        emit(StdlogLevel.ERROR, event, "FAILURE", payload, t);
    }

    /**
     * Emite un evento de error con {@code outcome} personalizado a nivel {@code ERROR}.
     * Útil cuando se necesita un outcome distinto de {@code FAILURE} (ej. {@code "TIMEOUT"}).
     *
     * @param event   nombre del evento de negocio
     * @param outcome valor libre para {@code stdlog.outcome}
     * @param payload datos adicionales bajo {@code custom}
     * @param t       excepción asociada; puede ser {@code null}
     */
    public static void error(String event, String outcome, Map<String, Object> payload, Throwable t) {
        emit(StdlogLevel.ERROR, event, outcome, payload, t);
    }

    private static void emit(StdlogLevel level,
            String event,
            String outcome,
            Map<String, Object> payload,
            Throwable t) {

        if (event == null || event.isBlank()) return;

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", event);

        if (outcome != null && !outcome.isBlank()) {
            stdlog.put("outcome", outcome);
        }

        String operation = MDC.get("operation");
        if (operation != null && !operation.isBlank()) {
            stdlog.put("operation", operation);
        }

        String requestId = MDC.get("request_id");
        if (requestId != null && !requestId.isBlank()) {
            stdlog.put("request_id", requestId);
        }

        if (payload != null && !payload.isEmpty()) {
            stdlog.put("custom", payload);
        }

        if (t != null) StdlogEmitter.emit(STDLOG, level, stdlog, t);
        else StdlogEmitter.emit(STDLOG, level, stdlog);
    }
}