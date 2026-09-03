package appbrain.stdlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.argument.StructuredArgument;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;
import java.util.Map;

/**
 * Utilidades de test para capturar y decodificar los eventos emitidos por
 * {@code StdlogEmitter} hacia el logger {@code "stdlog"}, sin depender de un
 * appender JSON real.
 */
public final class StdlogTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StdlogTestSupport() {}

    /** Engancha un {@link ListAppender} al logger {@code "stdlog"} con el nivel dado y lo retorna. */
    public static ListAppender<ILoggingEvent> attachStdlogAppender(Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger("stdlog");
        logger.setLevel(level);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Desengancha el appender del logger {@code "stdlog"} y lo detiene. */
    public static void detach(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger("stdlog");
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Extrae el payload bajo la clave {@code "stdlog"} del marker logstash del evento,
     * serializándolo a JSON real (igual que haría el encoder en producción) y parseándolo de vuelta.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> stdlogPayload(ILoggingEvent event) {
        if (event.getMarkerList() == null || event.getMarkerList().isEmpty()) {
            throw new IllegalStateException("El evento no tiene marker asociado");
        }
        Marker marker = event.getMarkerList().get(0);
        if (!(marker instanceof StructuredArgument arg)) {
            throw new IllegalStateException("Se esperaba un StructuredArgument, se obtuvo: " + marker);
        }
        try {
            StringWriter sw = new StringWriter();
            JsonGenerator gen = MAPPER.createGenerator(sw);
            gen.writeStartObject();
            arg.writeTo(gen);
            gen.writeEndObject();
            gen.close();
            Map<String, Object> root = MAPPER.readValue(sw.toString(), Map.class);
            return (Map<String, Object>) root.get("stdlog");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
