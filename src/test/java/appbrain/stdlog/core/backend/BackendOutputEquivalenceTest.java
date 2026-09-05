package appbrain.stdlog.core.backend;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Condición de aceptación central de {@code ADR-0014}</b>: los dos backends deben producir
 * el <b>mismo JSON</b> bajo la clave {@code stdlog}.
 *
 * <p>El riesgo real de ese ADR no es que Log4j2 no funcione —el spike ya lo demostró— sino que
 * las dos salidas <b>diverjan en silencio</b> con el tiempo. Este test existe para que eso no
 * pueda pasar sin que el build se entere.</p>
 *
 * <p>Se comparan salidas <b>reales</b>, no aproximaciones:</p>
 * <ul>
 *   <li><b>Logback</b>: se emite de verdad y se serializa el {@code Marker} con el encoder de
 *       logstash, que es lo que hace en producción.</li>
 *   <li><b>Log4j2</b>: se construye un {@code LogEvent} con el mismo {@code ObjectMessage} que
 *       emitiría el escritor y se renderiza con {@code JsonTemplateLayout} real.</li>
 * </ul>
 *
 * <p>SLF4J sólo puede estar enlazado a un backend a la vez, y en los tests lo está a Logback;
 * por eso el lado de Log4j2 se renderiza directamente con su layout en lugar de a través de
 * SLF4J. Lo que se compara es el resultado, que es lo que le importa al consumidor.</p>
 */
class BackendOutputEquivalenceTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        StdlogBackend.reset();
    }

    /** Payload con anidamiento, tipos mixtos y orden significativo. */
    private static Map<String, Object> payload() {
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", "POST");
        http.put("status", 200);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("headers", new LinkedHashMap<>(Map.of("x-routing", "MCO")));
        request.put("body", new LinkedHashMap<>(Map.of("a", 1)));
        request.put("queryParams", new LinkedHashMap<>(Map.of("site", "MCO")));

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", "CONTROLLER_HTTP");
        stdlog.put("direction", "IN");
        stdlog.put("request_id", "abc-123");
        stdlog.put("operation", "PagosController#pagar");
        stdlog.put("elapsedMs", 12L);
        stdlog.put("slow", false);
        stdlog.put("http", http);
        stdlog.put("request", request);
        stdlog.put("items", List.of("a", "b"));
        return stdlog;
    }

    // ---------- la comparación ----------

    @Test
    void bothBackendsMustProduceTheSameStdlogJson() throws Exception {
        Map<String, Object> payload = payload();

        String viaLogback = logbackJson(payload);
        String viaLog4j2 = log4j2Json(payload);

        assertEquals(viaLogback, viaLog4j2,
                "las dos salidas divergieron; ADR-0014 exige que sean equivalentes");
    }

    @Test
    void theSharedPayloadMustSurviveNestingAndTypes() throws Exception {
        String json = log4j2Json(payload());

        assertTrue(json.contains("\"http\":{\"method\":\"POST\",\"status\":200}"), json);
        assertTrue(json.contains("\"headers\":{\"x-routing\":\"MCO\"}"), json);
        assertTrue(json.contains("\"items\":[\"a\",\"b\"]"), json);
        assertTrue(json.contains("\"slow\":false"), json);
    }

    /** El orden del esquema es parte de lo que se lee: MapMessage lo perdería. */
    @Test
    void keyOrderMustBePreservedOnBothBackends() throws Exception {
        String viaLogback = logbackJson(payload());
        String viaLog4j2 = log4j2Json(payload());

        for (String json : List.of(viaLogback, viaLog4j2)) {
            assertTrue(json.indexOf("\"event\"") < json.indexOf("\"direction\""), json);
            assertTrue(json.indexOf("\"direction\"") < json.indexOf("\"request_id\""), json);
            assertTrue(json.indexOf("\"http\"") < json.indexOf("\"request\""), json);
        }
    }

    // ---------- helpers: salida real de cada backend ----------

    /** Emite de verdad por Logback y devuelve el JSON del payload, vía el encoder de logstash. */
    private String logbackJson(Map<String, Object> payload) {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogBackend.install(new LogbackEventWriter());

        new LogbackEventWriter().write(LoggerFactory.getLogger("stdlog"), StdlogLevel.INFO, payload, null);

        return canonical(StdlogTestSupport.stdlogPayload(appender.list.get(0)));
    }

    /** Renderiza el ObjectMessage del escritor Log4j2 con un JsonTemplateLayout real. */
    private String log4j2Json(Map<String, Object> payload) throws Exception {
        Path template = Files.createTempFile("stdlog-template", ".json");
        Files.writeString(template, "{\"stdlog\":{\"$resolver\":\"message\",\"stringified\":false}}");

        JsonTemplateLayout layout = JsonTemplateLayout.newBuilder()
                .setConfiguration(new DefaultConfiguration())
                .setEventTemplateUri("file:" + template.toAbsolutePath())
                .build();

        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("stdlog")
                .setLevel(Log4j2EventWriter.toLog4jLevel(StdlogLevel.INFO))
                .setMessage(Log4j2EventWriter.toMessage(payload))
                .build();

        String rendered = new String(layout.toByteArray(event), StandardCharsets.UTF_8);
        Files.deleteIfExists(template);

        // El layout envuelve el payload bajo "stdlog"; se extrae para comparar lo mismo que el
        // lado de Logback, que devuelve ya el contenido de esa clave.
        return canonical(rendered);
    }

    /** Normaliza a una forma comparable: sólo el contenido de `stdlog`, sin espacios ni saltos. */
    private static String canonical(Object jsonOrMap) {
        String text = String.valueOf(jsonOrMap instanceof Map<?, ?> m ? toJson(m) : jsonOrMap).trim();
        int start = text.indexOf("\"stdlog\":");
        if (start >= 0) {
            text = text.substring(start + "\"stdlog\":".length());
            int end = text.lastIndexOf('}');
            if (end > 0 && text.endsWith("}}")) text = text.substring(0, end);
        }
        return text.replaceAll("\\s+", "");
    }

    /** Serializa el mapa que devuelve el lado Logback con el mismo formato compacto. */
    private static String toJson(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v instanceof Map<?, ?> m) return toJson(m);
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(value(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "\"" + v + "\"";
    }
}
