package appbrain.stdlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Propiedades de configuración del starter {@code appbrain-stdlog-spring-boot-starter}.
 *
 * <p>Se configura con el prefijo {@code stdlog} en {@code application.yml}.
 * Ejemplo mínimo:</p>
 * <pre>{@code
 * stdlog:
 *   mode: AUTO
 *   consumerBasePackage: com.example.myapp
 *   controller:
 *     enabled: true
 *     logRequestBody: true
 *   restclient:
 *     enabled: true
 *   jdbc:
 *     enabled: true
 * }</pre>
 *
 * <p>Cada módulo (controller, restclient, jdbc, error) se puede activar o desactivar
 * de forma independiente y ajustar niveles de log, thresholds y políticas de captura.</p>
 *
 * @see StdlogLevel
 */
@ConfigurationProperties(prefix = "stdlog")
public class StdlogProperties {

    /**
     * Controla las políticas de filtrado de logs según el entorno de ejecución.
     *
     * <ul>
     *   <li>{@code AUTO} — detecta el entorno via la variable de entorno {@code STDLOG_MODE}.</li>
     *   <li>{@code PROD} — fuerza comportamiento productivo (anti-ruido habilitado).</li>
     *   <li>{@code NON_PROD} — fuerza comportamiento no productivo (loguea todo).</li>
     * </ul>
     */
    public enum Mode { AUTO, PROD, NON_PROD }

    /**
     * Modo de operación. Default: {@code AUTO}.
     * También puede forzarse con la variable de entorno {@code STDLOG_MODE=PROD|NON_PROD}.
     */
    private Mode mode = Mode.AUTO;

    /**
     * Paquete base de la aplicación consumidora.
     * Se usa para filtrar el stack trace en {@code error.app_trace} y para
     * resolver el caller en {@code restclient.captureSource=true}.
     * Ejemplo: {@code com.example.myapp}
     */
    private String consumerBasePackage;

    private final Controller controller = new Controller();
    private final Restclient restclient = new Restclient();
    private final Jdbc jdbc = new Jdbc();
    private final Error error = new Error();

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String getConsumerBasePackage() { return consumerBasePackage; }
    public void setConsumerBasePackage(String consumerBasePackage) { this.consumerBasePackage = consumerBasePackage; }

    public Controller getController() { return controller; }
    public Restclient getRestclient() { return restclient; }
    public Jdbc getJdbc() { return jdbc; }
    public Error getError() { return error; }

    /**
     * Configuración del módulo de logging de requests/responses HTTP entrantes
     * ({@code CONTROLLER_HTTP direction=IN/OUT}).
     *
     * <p>Emite dos eventos por request: uno al recibir ({@code direction=IN})
     * y otro al responder ({@code direction=OUT}).</p>
     */
    public static class Controller {

        /** Si {@code false}, deshabilita completamente el módulo. Default: {@code true}. */
        private boolean enabled = true;

        /** Nivel del evento {@code direction=IN}. Default: {@code INFO}. */
        private StdlogLevel inLevel = StdlogLevel.INFO;

        /** Nivel del evento {@code direction=OUT} cuando {@code status < 400}. Default: {@code INFO}. */
        private StdlogLevel outLevelSuccess = StdlogLevel.INFO;

        /** Nivel del evento {@code direction=OUT} cuando {@code 400 <= status < 500}. Default: {@code WARN}. */
        private StdlogLevel outLevelFailure4xx = StdlogLevel.WARN;

        /** Nivel del evento {@code direction=OUT} cuando {@code status >= 500}. Default: {@code ERROR}. */
        private StdlogLevel outLevelFailure5xx = StdlogLevel.ERROR;

        /**
         * Si {@code true}, intenta capturar el body del request.
         * Solo se captura si el content-type está en {@code allowedContentTypes}.
         * Default: {@code true}.
         */
        private boolean logRequestBody = true;

        /**
         * Si {@code true}, intenta capturar el body de la respuesta.
         * Solo se captura si el content-type está en {@code allowedContentTypes}.
         * Default: {@code true}.
         */
        private boolean logResponseBody = true;

        /**
         * Máximo de bytes a incluir en el log del body del request.
         * Si el body supera este límite se trunca y se marca {@code bodyTruncated=true}.
         * Default: {@code 4096}.
         */
        private int maxRequestBodyBytes = 4096;

        /**
         * Máximo de bytes a incluir en el log del body de la respuesta.
         * Si el body supera este límite se trunca y se marca {@code bodyTruncated=true}.
         * Default: {@code 4096}.
         */
        private int maxResponseBodyBytes = 4096;

        /**
         * Lista de headers del request a incluir en el log.
         * Si está vacía, no se loguea ningún header. Case-insensitive.
         * Ejemplo: {@code [x-routing, x-request-id]}.
         */
        private List<String> allowedHeaders = new ArrayList<>();

        /**
         * Content-types para los cuales se captura el body.
         * Evitar binarios y multipart para prevenir presión en heap.
         * Default: {@code [application/json, text/plain, application/*+json]}.
         */
        private List<String> allowedContentTypes = List.of("application/json", "text/plain", "application/*+json");

        /**
         * Patrones Ant de paths cuyo request completo (controller, JDBC, restclient,
         * custom) silencia eventos {@code TRACE}/{@code DEBUG}/{@code INFO}; {@code WARN}
         * y {@code ERROR} nunca se suprimen (ver {@code appbrain.stdlog.core.StdlogEmitter}).
         * La comparación se realiza contra el path del request sin el context path,
         * por ejemplo {@code /actuator/health}. Una lista vacía no excluye ningún path.
         * Ejemplos: {@code [/actuator/**, /health]}.
         */
        private List<String> excludedPathPatterns = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public StdlogLevel getInLevel() { return inLevel; }
        public void setInLevel(StdlogLevel inLevel) { this.inLevel = inLevel; }

        public StdlogLevel getOutLevelSuccess() { return outLevelSuccess; }
        public void setOutLevelSuccess(StdlogLevel outLevelSuccess) { this.outLevelSuccess = outLevelSuccess; }

        public StdlogLevel getOutLevelFailure4xx() { return outLevelFailure4xx; }
        public void setOutLevelFailure4xx(StdlogLevel outLevelFailure4xx) { this.outLevelFailure4xx = outLevelFailure4xx; }

        public StdlogLevel getOutLevelFailure5xx() { return outLevelFailure5xx; }
        public void setOutLevelFailure5xx(StdlogLevel outLevelFailure5xx) { this.outLevelFailure5xx = outLevelFailure5xx; }

        public boolean isLogRequestBody() { return logRequestBody; }
        public void setLogRequestBody(boolean logRequestBody) { this.logRequestBody = logRequestBody; }

        public boolean isLogResponseBody() { return logResponseBody; }
        public void setLogResponseBody(boolean logResponseBody) { this.logResponseBody = logResponseBody; }

        public int getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
        public void setMaxRequestBodyBytes(int maxRequestBodyBytes) { this.maxRequestBodyBytes = maxRequestBodyBytes; }

        public int getMaxResponseBodyBytes() { return maxResponseBodyBytes; }
        public void setMaxResponseBodyBytes(int maxResponseBodyBytes) { this.maxResponseBodyBytes = maxResponseBodyBytes; }

        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> allowedHeaders) { this.allowedHeaders = allowedHeaders; }

        public List<String> getAllowedContentTypes() { return allowedContentTypes; }
        public void setAllowedContentTypes(List<String> allowedContentTypes) { this.allowedContentTypes = allowedContentTypes; }

        public List<String> getExcludedPathPatterns() { return excludedPathPatterns; }
        public void setExcludedPathPatterns(List<String> excludedPathPatterns) {
            this.excludedPathPatterns = excludedPathPatterns == null ? new ArrayList<>() : excludedPathPatterns;
        }
    }

    /**
     * Configuración del módulo de logging de llamadas HTTP salientes via {@code RestTemplate}
     * ({@code CLIENT_HTTP direction=IN}).
     *
     * <p>Estrategia single-log: se emite un único evento por llamada saliente,
     * después de recibir la respuesta (o la excepción), con request y response combinados.</p>
     */
    public static class Restclient {

        /** Si {@code false}, deshabilita completamente el módulo. Default: {@code true}. */
        private boolean enabled = true;

        /** Nivel del evento para respuestas {@code status < 400}. Default: {@code INFO}. */
        private StdlogLevel outLevelSuccess = StdlogLevel.INFO;

        /** Nivel del evento para respuestas {@code 400 <= status < 500}. Default: {@code WARN}. */
        private StdlogLevel outLevelFailure4xx = StdlogLevel.WARN;

        /** Nivel del evento para respuestas {@code status >= 500} o excepción. Default: {@code ERROR}. */
        private StdlogLevel outLevelFailure5xx = StdlogLevel.ERROR;

        /** @see #outLevelSuccess */
        private StdlogLevel inLevelSuccess = StdlogLevel.INFO;

        /** @see #outLevelFailure4xx */
        private StdlogLevel inLevelFailure4xx = StdlogLevel.WARN;

        /** @see #outLevelFailure5xx */
        private StdlogLevel inLevelFailure5xx = StdlogLevel.ERROR;

        /**
         * Política anti-ruido en producción.
         * <ul>
         *   <li>{@code true} + {@code mode=PROD}: solo loguea cuando {@code status >= 400} o excepción.</li>
         *   <li>{@code true} + {@code mode=NON_PROD}: loguea todas las llamadas.</li>
         *   <li>{@code false}: loguea todas las llamadas en cualquier modo.</li>
         * </ul>
         * Default: {@code true}.
         */
        private boolean logOnlyOnFailureInProd = true;

        /**
         * Máximo de caracteres del body a incluir en el log.
         * El body solo se loguea cuando {@code logging.level.stdlog=DEBUG}.
         * {@code 0} = sin límite (usar con precaución en producción).
         * Default: {@code 0}.
         */
        private int maxBodyChars = 0;

        /**
         * Lista de headers del request a incluir en el log.
         * Solo aplica cuando {@code logAllRequestHeaders=false}.
         * Default: vacío (no se loguea ningún header de request).
         */
        private List<String> requestHeadersAllowlist = List.of();

        /**
         * Lista de headers de respuesta a incluir en el log.
         * Solo aplica cuando {@code logAllResponseHeaders=false}.
         * Default: vacío (no se loguea ningún header de respuesta).
         */
        private List<String> responseHeadersAllowlist = List.of();

        /**
         * Si {@code true}, loguea todos los headers del request ignorando {@code requestHeadersAllowlist}.
         * Default: {@code false}.
         */
        private boolean logAllRequestHeaders = false;

        /**
         * Si {@code true}, loguea todos los headers de respuesta ignorando {@code responseHeadersAllowlist}.
         * Default: {@code false}.
         */
        private boolean logAllResponseHeaders = false;

        /**
         * Si {@code true}, captura el caller (clase, método, línea) de la llamada HTTP
         * mediante un stacktrace-walk. Tiene costo de CPU por llamada.
         * Default: {@code false}.
         */
        private boolean captureSource = false;

        /**
         * Si {@code true}, genera un {@code call_id} UUID único por cada llamada HTTP saliente.
         * Útil para correlacionar logs de una misma llamada en sistemas distribuidos.
         * Default: {@code true}.
         */
        private boolean captureCallId = true;

        /**
         * Paquete base del consumidor para resolver el caller cuando {@code captureSource=true}.
         * Si no se configura, hereda {@code stdlog.consumerBasePackage}.
         */
        private String consumerBasePackage;

        /** Configuración específica de la vía {@code WebClient} (cliente saliente reactivo). Ver ADR-0006. */
        private final Webclient webclient = new Webclient();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public StdlogLevel getOutLevelSuccess() { return outLevelSuccess; }
        public void setOutLevelSuccess(StdlogLevel outLevelSuccess) { this.outLevelSuccess = outLevelSuccess; }

        public StdlogLevel getOutLevelFailure4xx() { return outLevelFailure4xx; }
        public void setOutLevelFailure4xx(StdlogLevel outLevelFailure4xx) { this.outLevelFailure4xx = outLevelFailure4xx; }

        public StdlogLevel getOutLevelFailure5xx() { return outLevelFailure5xx; }
        public void setOutLevelFailure5xx(StdlogLevel outLevelFailure5xx) { this.outLevelFailure5xx = outLevelFailure5xx; }

        public StdlogLevel getInLevelSuccess() { return inLevelSuccess; }
        public void setInLevelSuccess(StdlogLevel inLevelSuccess) { this.inLevelSuccess = inLevelSuccess; }

        public StdlogLevel getInLevelFailure4xx() { return inLevelFailure4xx; }
        public void setInLevelFailure4xx(StdlogLevel inLevelFailure4xx) { this.inLevelFailure4xx = inLevelFailure4xx; }

        public StdlogLevel getInLevelFailure5xx() { return inLevelFailure5xx; }
        public void setInLevelFailure5xx(StdlogLevel inLevelFailure5xx) { this.inLevelFailure5xx = inLevelFailure5xx; }

        public boolean isLogOnlyOnFailureInProd() { return logOnlyOnFailureInProd; }
        public void setLogOnlyOnFailureInProd(boolean logOnlyOnFailureInProd) { this.logOnlyOnFailureInProd = logOnlyOnFailureInProd; }

        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }

        public List<String> getRequestHeadersAllowlist() { return requestHeadersAllowlist; }
        public void setRequestHeadersAllowlist(List<String> requestHeadersAllowlist) { this.requestHeadersAllowlist = requestHeadersAllowlist; }

        public List<String> getResponseHeadersAllowlist() { return responseHeadersAllowlist; }
        public void setResponseHeadersAllowlist(List<String> responseHeadersAllowlist) { this.responseHeadersAllowlist = responseHeadersAllowlist; }

        public boolean isLogAllRequestHeaders() { return logAllRequestHeaders; }
        public void setLogAllRequestHeaders(boolean logAllRequestHeaders) { this.logAllRequestHeaders = logAllRequestHeaders; }

        public boolean isLogAllResponseHeaders() { return logAllResponseHeaders; }
        public void setLogAllResponseHeaders(boolean logAllResponseHeaders) { this.logAllResponseHeaders = logAllResponseHeaders; }

        public boolean isCaptureSource() { return captureSource; }
        public void setCaptureSource(boolean captureSource) { this.captureSource = captureSource; }

        public boolean isCaptureCallId() { return captureCallId; }
        public void setCaptureCallId(boolean captureCallId) { this.captureCallId = captureCallId; }

        public String getConsumerBasePackage() { return consumerBasePackage; }
        public void setConsumerBasePackage(String consumerBasePackage) { this.consumerBasePackage = consumerBasePackage; }

        public Webclient getWebclient() { return webclient; }

        /**
         * Ajustes de la instrumentación de {@code WebClient}. Comparte el resto de la
         * configuración de {@code stdlog.restclient} (niveles, {@code maxBodyChars},
         * allowlists de headers, {@code logOnlyOnFailureInProd}, {@code captureCallId},
         * {@code captureSource}).
         */
        public static class Webclient {

            /**
             * Si {@code false}, no se instrumenta {@code WebClient} (pero {@code RestTemplate}
             * y {@code RestClient} siguen instrumentados). Default: {@code true}.
             */
            private boolean enabled = true;

            /**
             * Tope duro de bytes que se bufferizan por body (request o response) para poder
             * loguearlo. Protege la memoria ante respuestas grandes o streaming: al superarlo
             * se corta la captura y el body se marca como truncado. No afecta a la app, que
             * recibe el body completo. Default: {@code 262144} (256 KiB). {@code 0} = sin tope.
             */
            private int maxCaptureBytes = 256 * 1024;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public int getMaxCaptureBytes() { return maxCaptureBytes; }
            public void setMaxCaptureBytes(int maxCaptureBytes) { this.maxCaptureBytes = maxCaptureBytes; }
        }
    }

    /**
     * Configuración del módulo de logging de queries JDBC via datasource-proxy
     * ({@code CLIENT_DB direction=OUT}).
     *
     * <p>Emite un evento por query ejecutada, con SQL, parámetros, tiempo de ejecución
     * y resultado (filas afectadas o devueltas).</p>
     */
    public static class Jdbc {

        /** Si {@code false}, deshabilita el proxy del DataSource y el módulo completo. Default: {@code true}. */
        private boolean enabled = true;

        /** Nivel para queries exitosas y no lentas. Default: {@code INFO}. */
        private StdlogLevel levelSuccess = StdlogLevel.INFO;

        /** Nivel para queries que arrojan excepción. Default: {@code ERROR}. */
        private StdlogLevel levelFailure = StdlogLevel.ERROR;

        /**
         * Política anti-ruido en producción.
         * <ul>
         *   <li>{@code true} + {@code mode=PROD}: loguea solo cuando {@code slow=true} o {@code outcome=FAILURE}.</li>
         *   <li>{@code true} + {@code mode=NON_PROD}: loguea todas las queries.</li>
         *   <li>{@code false}: loguea todas las queries en cualquier modo.</li>
         * </ul>
         * Default: {@code true}.
         */
        private boolean logOnlySlowOrFailureInProd = true;

        /**
         * Nombre lógico del pool de conexiones. Aparece en {@code peer.pool} del log.
         * Default: {@code "db"}.
         */
        private String poolName = "db";

        /**
         * Umbral en milisegundos para marcar una query como lenta ({@code slow=true}).
         * {@code 0} deshabilita la detección de queries lentas.
         * Default: {@code 0}.
         */
        private long slowQueryThresholdMs = 0;

        /**
         * Máximo de caracteres del SQL a incluir en el log.
         * El SQL se trunca si supera este límite. Default: {@code 2000}.
         */
        private int maxSqlChars = 2000;

        /**
         * Si {@code true}, incluye los parámetros del PreparedStatement en el log
         * bajo {@code db.params}. Default: {@code false}.
         */
        private boolean logParams = false;

        /**
         * Máximo de caracteres por valor de parámetro string.
         * Solo aplica cuando {@code logParams=true}. Default: {@code 200}.
         */
        private int maxParamChars = 200;

        /**
         * Si {@code true}, incluye información del resultado bajo {@code db.response}:
         * tipo ({@code RESULT_SET} o {@code UPDATE_COUNT}) y, cuando está disponible,
         * filas devueltas o afectadas. Default: {@code false}.
         */
        private boolean logResponseInfo = false;

        /** Configuración específica de la vía R2DBC (base de datos reactiva). Ver ADR-0007. */
        private final R2dbc r2dbc = new R2dbc();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public StdlogLevel getLevelSuccess() { return levelSuccess; }
        public void setLevelSuccess(StdlogLevel levelSuccess) { this.levelSuccess = levelSuccess; }

        public StdlogLevel getLevelFailure() { return levelFailure; }
        public void setLevelFailure(StdlogLevel levelFailure) { this.levelFailure = levelFailure; }

        public boolean isLogOnlySlowOrFailureInProd() { return logOnlySlowOrFailureInProd; }
        public void setLogOnlySlowOrFailureInProd(boolean logOnlySlowOrFailureInProd) {
            this.logOnlySlowOrFailureInProd = logOnlySlowOrFailureInProd;
        }

        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }

        public long getSlowQueryThresholdMs() { return slowQueryThresholdMs; }
        public void setSlowQueryThresholdMs(long slowQueryThresholdMs) { this.slowQueryThresholdMs = slowQueryThresholdMs; }

        public int getMaxSqlChars() { return maxSqlChars; }
        public void setMaxSqlChars(int maxSqlChars) { this.maxSqlChars = maxSqlChars; }

        public boolean isLogParams() { return logParams; }
        public void setLogParams(boolean logParams) { this.logParams = logParams; }

        public int getMaxParamChars() { return maxParamChars; }
        public void setMaxParamChars(int maxParamChars) { this.maxParamChars = maxParamChars; }

        public boolean isLogResponseInfo() { return logResponseInfo; }
        public void setLogResponseInfo(boolean logResponseInfo) { this.logResponseInfo = logResponseInfo; }

        public R2dbc getR2dbc() { return r2dbc; }

        /**
         * Ajustes de la instrumentación de R2DBC. Comparte el resto de la configuración de
         * {@code stdlog.jdbc} (niveles, {@code slowQueryThresholdMs}, {@code logOnlySlowOrFailureInProd},
         * {@code maxSqlChars}, {@code logParams}, {@code maxParamChars}, {@code logResponseInfo}, {@code poolName}).
         */
        public static class R2dbc {

            /**
             * Si {@code false}, no se instrumenta R2DBC (pero el {@code DataSource} JDBC, si lo hay,
             * sigue instrumentado). Default: {@code true}.
             */
            private boolean enabled = true;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
    }

    /**
     * Configuración del módulo de logging de excepciones MVC.
     *
     * <p>Cuando se produce una excepción real en un handler MVC, emite un evento adicional
     * (además del {@code CONTROLLER_HTTP direction=OUT}):</p>
     * <ul>
     *   <li>Status {@code 4xx} → {@code event=WARN}, nivel {@code WARN}.</li>
     *   <li>Status {@code 5xx} → {@code event=ERROR}, nivel {@code ERROR}.</li>
     * </ul>
     * <p>Incluye {@code error.app_trace}, {@code error.type}, {@code error.message}
     * y el stack trace completo cliqueable en IDEs.</p>
     *
     * <p>Nota: {@code MethodArgumentNotValidException} (validación de beans)
     * no genera evento extra para evitar ruido en flujos normales de validación.</p>
     */
    public static class Error {

        /** Si {@code false}, deshabilita el módulo completo. Default: {@code true}. */
        private boolean enabled = true;


        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
