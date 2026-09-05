package appbrain.stdlog.core.backend;

import appbrain.stdlog.config.StdlogLevel;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Último recurso cuando el backend enlazado no es ninguno de los soportados (`ADR-0014`).
 *
 * <p>No puede producir JSON estructurado, pero <b>no pierde el evento</b>: lo escribe como texto
 * en el mensaje, que es greppable y legible. Es peor que la salida nativa y mejor que el
 * comportamiento anterior, en el que el payload se descartaba entero y en silencio.</p>
 *
 * <p>Deliberadamente no usa Jackson: el paquete del {@code ObjectMapper} difiere entre las dos
 * líneas de `ADR-0005` y este camino no merece introducir una divergencia más. El aviso que
 * emite {@link StdlogBackend} al arrancar dice que se está usando este respaldo y por qué.</p>
 */
public final class FallbackEventWriter implements StdlogEventWriter {

    private final String backendDescription;

    public FallbackEventWriter(String backendDescription) {
        this.backendDescription = backendDescription;
    }

    @Override
    public void write(Logger logger, StdlogLevel level, Map<String, Object> payload, Throwable t) {
        String message = "stdlog " + payload;
        if (t != null) {
            switch (level) {
                case TRACE -> logger.trace(message, t);
                case DEBUG -> logger.debug(message, t);
                case INFO  -> logger.info(message, t);
                case WARN  -> logger.warn(message, t);
                case ERROR -> logger.error(message, t);
            }
            return;
        }
        switch (level) {
            case TRACE -> logger.trace(message);
            case DEBUG -> logger.debug(message);
            case INFO  -> logger.info(message);
            case WARN  -> logger.warn(message);
            case ERROR -> logger.error(message);
        }
    }

    @Override
    public String describe() {
        return "respaldo de texto plano (backend no soportado: " + backendDescription + ")";
    }
}
