package appbrain.stdlog.core.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detecta el backend de logging enlazado y elige el escritor correspondiente (`ADR-0014`).
 *
 * <p>La detección se hace por la fábrica que SLF4J tiene enlazada, que identifica el backend sin
 * ambigüedad incluso si hay varios en el classpath —gana el que SLF4J enlazó, que es
 * exactamente el que va a recibir los eventos—:</p>
 *
 * <table>
 *   <tr><th>Backend</th><th>{@code LoggerFactory.getILoggerFactory()}</th></tr>
 *   <tr><td>Logback</td><td>{@code ch.qos.logback.classic.LoggerContext}</td></tr>
 *   <tr><td>Log4j2</td><td>{@code org.apache.logging.slf4j.Log4jLoggerFactory}</td></tr>
 * </table>
 *
 * <p>Se resuelve una sola vez: el backend no cambia durante la vida del proceso.</p>
 */
public final class StdlogBackend {

    private static final Logger INTERNAL = LoggerFactory.getLogger("appbrain.stdlog.internal");

    private static final String LOGBACK_FACTORY = "ch.qos.logback.classic.LoggerContext";
    private static final String LOG4J2_FACTORY = "org.apache.logging.slf4j.Log4jLoggerFactory";

    private static final String LOGSTASH_MARKERS = "net.logstash.logback.marker.Markers";
    private static final String LOG4J2_LOG_MANAGER = "org.apache.logging.log4j.LogManager";

    private static volatile StdlogEventWriter writer;

    private StdlogBackend() {}

    /** Escritor en uso; se detecta en la primera llamada si nadie lo instaló antes. */
    public static StdlogEventWriter writer() {
        StdlogEventWriter current = writer;
        if (current == null) {
            synchronized (StdlogBackend.class) {
                current = writer;
                if (current == null) {
                    current = detect();
                    writer = current;
                }
            }
        }
        return current;
    }

    /**
     * Detecta el backend, lo instala y lo anuncia. La llama la autoconfiguración al arrancar
     * para que el anuncio ocurra ahí y no en el primer evento.
     */
    public static void detectAndAnnounce() {
        StdlogEventWriter current = writer();
        if (current instanceof FallbackEventWriter) {
            // No se rompe nada (ADR-0011 no se negocia), pero callarlo seria repetir el problema
            // que este ADR arregla: alguien puede tardar semanas en notar que sus logs
            // estructurados no existen.
            INTERNAL.warn("stdlog: backend de logging no soportado. Los eventos se emiten como texto plano, "
                    + "no como JSON estructurado. Backend detectado: {}. "
                    + "Soportados: Logback con logstash-logback-encoder, o Log4j2 con log4j-api. "
                    + "Ver ADR-0014.", loggerFactoryName());
        } else {
            INTERNAL.info("stdlog: backend {}.", current.describe());
        }
    }

    /** Reinicia el escritor detectado. Uso previsto: aislamiento entre tests. */
    public static void reset() {
        writer = null;
    }

    /** Instala un escritor concreto. Uso previsto: tests. */
    static void install(StdlogEventWriter custom) {
        writer = custom;
    }

    static StdlogEventWriter detect() {
        String factory = loggerFactoryName();

        if (LOGBACK_FACTORY.equals(factory) && classPresent(LOGSTASH_MARKERS)) {
            return new LogbackEventWriter();
        }
        if (LOG4J2_FACTORY.equals(factory) && classPresent(LOG4J2_LOG_MANAGER)) {
            return new Log4j2EventWriter();
        }
        return new FallbackEventWriter(factory);
    }

    private static String loggerFactoryName() {
        try {
            return LoggerFactory.getILoggerFactory().getClass().getName();
        } catch (RuntimeException | LinkageError ignored) {
            return "desconocido";
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, StdlogBackend.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
