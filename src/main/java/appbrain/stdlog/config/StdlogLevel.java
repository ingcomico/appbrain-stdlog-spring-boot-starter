package appbrain.stdlog.config;

/**
 * Niveles de severidad disponibles para los eventos emitidos por stdlog.
 *
 * <p>Controlan tanto la severidad del evento en el JSON ({@code stdlog.level})
 * como el nivel real con que se invoca el logger subyacente (SLF4J).
 * El filtrado final de qué se imprime queda sujeto al nivel configurado en
 * {@code logging.level.stdlog} en el {@code application.yml} del consumidor.</p>
 *
 * <p>Uso típico en propiedades:</p>
 * <pre>{@code
 * stdlog:
 *   controller:
 *     inLevel: INFO
 *     outLevelFailure5xx: ERROR
 * }</pre>
 */
public enum StdlogLevel {

    /** Nivel de trazado detallado. Útil solo en desarrollo local. */
    TRACE,

    /**
     * Nivel de depuración. Habilita la captura de bodies en restclient
     * ({@code logging.level.stdlog=DEBUG} necesario).
     */
    DEBUG,

    /** Nivel informativo estándar. Default para eventos de éxito. */
    INFO,

    /** Advertencia. Default para respuestas 4xx y llamadas externas fallidas. */
    WARN,

    /** Error. Default para respuestas 5xx, excepciones y queries fallidas. */
    ERROR
}