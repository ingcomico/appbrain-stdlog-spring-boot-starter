package appbrain.stdlog.core.backend;

import appbrain.stdlog.config.StdlogLevel;
import org.slf4j.Logger;

import java.util.Map;

import static net.logstash.logback.marker.Markers.append;

/**
 * Escritor para Logback + {@code logstash-logback-encoder} (`ADR-0003`), el camino por defecto.
 *
 * <p>El payload viaja en un {@code Marker} que el encoder expande como objeto JSON anidado bajo
 * la clave {@code stdlog}. El mensaje es la cadena literal {@code "stdlog"}: no lleva
 * información, y por eso este payload es invisible para un backend que ignore los markers.</p>
 */
public final class LogbackEventWriter implements StdlogEventWriter {

    @Override
    public void write(Logger logger, StdlogLevel level, Map<String, Object> payload, Throwable t) {
        if (t != null) {
            switch (level) {
                case TRACE -> logger.trace(append("stdlog", payload), "stdlog", t);
                case DEBUG -> logger.debug(append("stdlog", payload), "stdlog", t);
                case INFO  -> logger.info (append("stdlog", payload), "stdlog", t);
                case WARN  -> logger.warn (append("stdlog", payload), "stdlog", t);
                case ERROR -> logger.error(append("stdlog", payload), "stdlog", t);
            }
            return;
        }
        switch (level) {
            case TRACE -> logger.trace(append("stdlog", payload), "stdlog");
            case DEBUG -> logger.debug(append("stdlog", payload), "stdlog");
            case INFO  -> logger.info (append("stdlog", payload), "stdlog");
            case WARN  -> logger.warn (append("stdlog", payload), "stdlog");
            case ERROR -> logger.error(append("stdlog", payload), "stdlog");
        }
    }

    @Override
    public String describe() {
        return "Logback + logstash-logback-encoder";
    }
}
