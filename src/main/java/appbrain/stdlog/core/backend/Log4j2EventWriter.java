package appbrain.stdlog.core.backend;

import appbrain.stdlog.config.StdlogLevel;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ObjectMessage;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Escritor para Log4j2 (`ADR-0014`).
 *
 * <p>Usa la API de Log4j2 <b>directamente</b> y no SLF4J, porque un {@code ObjectMessage} no se
 * puede pasar por la API de SLF4J. Ése es el motivo de que este ADR sea un cambio estructural y
 * no una línea.</p>
 *
 * <p>Se emite {@link ObjectMessage} y no {@code MapMessage}: los dos producen JSON anidado con
 * {@code JsonTemplateLayout}, pero {@code MapMessage} <b>reordena las claves alfabéticamente</b>
 * y el orden del esquema es parte de lo que se lee. Verificado en el spike de `ADR-0014`.</p>
 */
public final class Log4j2EventWriter implements StdlogEventWriter {

    @Override
    public void write(Logger logger, StdlogLevel level, Map<String, Object> payload, Throwable t) {
        LogManager.getLogger(logger.getName()).log(toLog4jLevel(level), toMessage(payload), t);
    }

    /** Aislado para que el test de equivalencia pueda comparar lo que recibe el backend. */
    static Message toMessage(Map<String, Object> payload) {
        return new ObjectMessage(payload);
    }

    static Level toLog4jLevel(StdlogLevel level) {
        return switch (level) {
            case TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }

    @Override
    public String describe() {
        return "Log4j2 + JsonTemplateLayout";
    }
}
