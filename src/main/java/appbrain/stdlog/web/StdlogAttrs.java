package appbrain.stdlog.web;

/**
 * Constantes de los atributos de request ({@code HttpServletRequest.setAttribute})
 * usados internamente por los componentes de stdlog para compartir contexto
 * entre filtros e interceptors dentro del mismo thread de request.
 *
 * <p>Todas las keys tienen el prefijo {@code "stdlog."} para evitar colisiones
 * con atributos de la aplicación consumidora.</p>
 */
public final class StdlogAttrs {

    /** Nombre de la operación en curso. Ej: {@code "TagsController#searchTags"}. Escrito por {@code StdlogMvcOperationInterceptor}. */
    public static final String OPERATION    = "stdlog.operation";

    /** Patrón de ruta MVC. Ej: {@code "/configcases/v1/tags/{id}"}. Escrito por {@code StdlogMvcOperationInterceptor}. */
    public static final String PATH_PATTERN = "stdlog.pathPattern";

    /** Ruta completa con método HTTP. Ej: {@code "GET /configcases/v1/tags/{id}"}. Escrito por {@code StdlogMvcOperationInterceptor}. */
    public static final String ROUTE        = "stdlog.route";

    /** Tiempo de inicio del request en nanosegundos ({@code System.nanoTime()}). Usado para calcular {@code elapsedMs}. */
    public static final String START_NANO   = "stdlog.startNano";

    /** Excepción capturada por {@code StdlogExceptionResolver}. Leída por {@code ControllerBodyAndOutLoggingFilter} para emitir el evento WARN/ERROR. */
    public static final String ERROR        = "stdlog.error";

    /** Trace id OpenTelemetry/Micrometer capturado durante el request. */
    public static final String TRACE_ID     = "stdlog.traceId";

    /** Span id OpenTelemetry/Micrometer capturado durante el request. */
    public static final String SPAN_ID      = "stdlog.spanId";

    private StdlogAttrs() {}
}
