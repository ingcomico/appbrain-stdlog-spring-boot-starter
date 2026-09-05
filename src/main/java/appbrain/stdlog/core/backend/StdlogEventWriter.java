package appbrain.stdlog.core.backend;

import appbrain.stdlog.config.StdlogLevel;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Escribe un evento ya construido en el backend de logging (`ADR-0014`).
 *
 * <p>Existe porque el payload no viaja en el mensaje sino en una estructura que cada backend
 * entiende a su manera: un {@code Marker} de logstash en Logback, un {@code ObjectMessage} en
 * Log4j2. Antes de este ADR sólo existía la primera, así que bajo cualquier otro backend el
 * evento se perdía entero y en silencio.</p>
 *
 * <p>La implementación se elige una sola vez al arrancar, en {@link StdlogBackend}.</p>
 */
public interface StdlogEventWriter {

    /**
     * @param logger  logger SLF4J destino; las implementaciones que usan otra API se apoyan en su nombre
     * @param level   severidad del evento
     * @param payload payload del evento, ya enmascarado y enriquecido
     * @param t       excepción asociada, o {@code null}
     */
    void write(Logger logger, StdlogLevel level, Map<String, Object> payload, Throwable t);

    /** Descripción legible del backend, para anunciarlo al arrancar. */
    String describe();
}
