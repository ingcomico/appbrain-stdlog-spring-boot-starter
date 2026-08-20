package appbrain.stdlog.web;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.error.AppTraceUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * Filtro principal de logging de requests y responses HTTP ({@code CONTROLLER_HTTP}).
 *
 * <p>Extiende {@link OncePerRequestFilter} para garantizar ejecución única por request.
 * Dependiendo de la configuración, opera en dos modos:</p>
 * <ul>
 *   <li><b>Sin body</b> ({@code logRequestBody=false} y {@code logResponseBody=false}):
 *       no wrappea el request/response, mínimo overhead.</li>
 *   <li><b>Con body</b>: usa {@link ContentCachingRequestWrapper} y
 *       {@link ContentCachingResponseWrapper} para capturar los bytes y permitir
 *       la lectura posterior. El body se trunca al loguear según
 *       {@code maxRequestBodyBytes} / {@code maxResponseBodyBytes}.</li>
 * </ul>
 *
 * <p>Emite tres tipos de eventos (en orden, siempre en bloque {@code finally}):</p>
 * <ol>
 *   <li>{@code CONTROLLER_HTTP direction=IN} — al procesar el request.</li>
 *   <li>{@code CONTROLLER_HTTP direction=OUT} — al finalizar la respuesta.</li>
 *   <li>{@code WARN} o {@code ERROR} — si {@link StdlogExceptionResolver} capturó
 *       una excepción durante el procesamiento.</li>
 * </ol>
 *
 * <p>Los campos {@code operation}, {@code route} y {@code request_id} se leen
 * desde atributos del request (seteados por {@link StdlogMvcOperationInterceptor}
 * y {@link RequestIdMdcFilter} respectivamente).</p>
 *
 * <p><b>Exclusión</b> ({@code excluded-path-patterns} o {@code @StdlogExcluded}): a
 * diferencia de versiones anteriores, ya no se salta el procesamiento del filtro —
 * en cambio marca {@link appbrain.stdlog.core.StdlogEmitter#MDC_EXCLUDED} en el MDC
 * antes de {@code chain.doFilter(...)}, lo que suprime los eventos {@code TRACE}/
 * {@code DEBUG}/{@code INFO} de este filtro (y de JDBC/restclient/custom) durante
 * todo el request, pero <b>nunca</b> los eventos {@code WARN}/{@code ERROR}: una
 * respuesta 4xx/5xx o una excepción capturada siguen logueándose igual, con su body
 * si corresponde.</p>
 */
public class ControllerBodyAndOutLoggingFilter extends OncePerRequestFilter {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // --- Claves del schema stdlog (evitan typos silenciosos) ---
    private static final String EVT_CONTROLLER_HTTP = "CONTROLLER_HTTP";

    private final StdlogProperties props;
    private final ObjectMapper objectMapper;

    public ControllerBodyAndOutLoggingFilter(StdlogProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return props == null || !props.getController().isEnabled();
    }

    private boolean isExcludedPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;

        return props.getController().getExcludedPathPatterns().stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .anyMatch(pattern -> PATH_MATCHER.match(pattern.trim(), path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException,
            IOException {

        // Si el path matchea excluded-path-patterns, marcamos el MDC ANTES de chain.doFilter():
        // como el filtro envuelve todo lo que sigue (interceptor, controller, JDBC, llamadas
        // salientes) en el mismo thread, StdlogEmitter suprime TRACE/DEBUG/INFO de cualquier
        // módulo durante todo el request. WARN/ERROR nunca se suprimen.
        if (isExcludedPath(req)) {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
        }

        boolean cacheReq = props.getController().isLogRequestBody();
        boolean cacheRes = props.getController().isLogResponseBody();

        if (!cacheReq && !cacheRes) {
            try {
                if (req.getAttribute(StdlogAttrs.START_NANO) == null) {
                    req.setAttribute(StdlogAttrs.START_NANO, System.nanoTime());
                }
                chain.doFilter(req, res);
            } finally {
                try {
                    logInNoBody(req);
                    logOutNoBody(req, res);
                    logErrorOrWarnEventIfPresent(req, res);
                } finally {
                    // Limpieza incondicional: la key puede haber sido seteada acá arriba
                    // (path excluido) o por StdlogMvcOperationInterceptor (@StdlogExcluded).
                    MDC.remove(StdlogEmitter.MDC_EXCLUDED);
                }
            }
            return;
        }

        ContentCachingRequestWrapper requestWrapper =
                new ContentCachingRequestWrapper(req, props.getController().getMaxRequestBodyBytes());
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(res);

        try {
            if (requestWrapper.getAttribute(StdlogAttrs.START_NANO) == null) {
                requestWrapper.setAttribute(StdlogAttrs.START_NANO, System.nanoTime());
            }
            chain.doFilter(requestWrapper, responseWrapper);
        } finally {
            try {
                logIn(requestWrapper);
                logOut(requestWrapper, responseWrapper);
                logErrorOrWarnEventIfPresent(requestWrapper, responseWrapper);
            } finally {
                MDC.remove(StdlogEmitter.MDC_EXCLUDED);
                responseWrapper.copyBodyToResponse();
            }
        }
    }

    /**
     * Evento extra:
     * - 4xx => event=WARN y level=WARN
     * - 5xx => event=ERROR y level=ERROR
     * - NO se emite para errores de validación (MethodArgumentNotValidException)
     * <p>
     * Para "clickable": se loguea pasando el Throwable al logger (StdlogEmitter.emit(..., ex))
     * y se evita meter stack_trace/stack_trace_text dentro de stdlog.
     */
    private void logErrorOrWarnEventIfPresent(HttpServletRequest req, HttpServletResponse res) {
        if (props.getError() == null || !props.getError().isEnabled()) {
            return;
        }

        Object exObj = req.getAttribute(StdlogAttrs.ERROR);
        if (!(exObj instanceof Throwable ex)) {
            return;
        }

        int status = (res != null) ? res.getStatus() : 500;

        boolean is5xx = status >= 500;
        String eventName = is5xx ? "ERROR" : "WARN";
        StdlogLevel level = is5xx ? StdlogLevel.ERROR : StdlogLevel.WARN;

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", eventName);
        stdlog.putAll(baseStdlog(req));

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", status);
        stdlog.put("http", http);

        Map<String, Object> err = new LinkedHashMap<>();
        err.put("app_trace", AppTraceUtil.appTrace(ex, appTracePackagePrefix(), 15));
        err.put("type", ex.getClass().getName());
        err.put("message", ex.getMessage());
        stdlog.put("error", err);

        // Pasamos Throwable para que el encoder emita stacktrace estándar y sea cliqueable
        StdlogEmitter.emit(STDLOG, level, stdlog, ex);
    }

    private String appTracePackagePrefix() {
        String basePkg = props.getConsumerBasePackage();
        return (basePkg != null && !basePkg.isBlank()) ? basePkg + "." : null;
    }

    private void logInNoBody(HttpServletRequest req) {
        StdlogLevel level = props.getController().getInLevel();

        String queryString = req.getQueryString();
        boolean hasQuery = queryString != null && !queryString.isBlank();

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", req.getMethod());
        http.put("fullPath", hasQuery ? (req.getRequestURI() + "?" + queryString) : req.getRequestURI());

        Map<String, Object> requestNode = new LinkedHashMap<>();
        requestNode.put("inputType", hasQuery ? "QUERY" : "NONE");
        requestNode.put("queryParams", HttpLogExtractors.queryParams(req));
        requestNode.put("headers", HttpLogExtractors.allowedHeaders(req, props.getController().getAllowedHeaders()));
        requestNode.put("contentType", req.getContentType());
        requestNode.put("contentLength", req.getContentLengthLong());

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", EVT_CONTROLLER_HTTP);
        stdlog.put("direction", "IN");
        stdlog.putAll(baseStdlog(req));
        stdlog.put("http", http);
        stdlog.put("request", requestNode);

        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private void logOutNoBody(HttpServletRequest req, HttpServletResponse res) {
        int status = res.getStatus();
        StdlogLevel level = controllerOutLevel(status);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", status);

        Map<String, Object> responseNode = new LinkedHashMap<>();
        responseNode.put("bodyCapture", "DISABLED");

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", EVT_CONTROLLER_HTTP);
        stdlog.put("direction", "OUT");
        stdlog.putAll(baseStdlog(req));
        stdlog.put("elapsedMs", elapsedMs(req));
        stdlog.put("outcome", (status >= 400) ? "FAILURE" : "SUCCESS");
        stdlog.put("http", http);
        stdlog.put("response", responseNode);

        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private void logIn(ContentCachingRequestWrapper req) {
        StdlogLevel level = props.getController().getInLevel();

        String contentType = req.getContentType();
        boolean allowedCt = HttpLogExtractors.isAllowedContentType(contentType, props.getController().getAllowedContentTypes());

        String queryString = req.getQueryString();
        boolean hasQuery = queryString != null && !queryString.isBlank();

        byte[] bodyBytes = req.getContentAsByteArray();
        boolean hasBody = bodyBytes != null && bodyBytes.length > 0;

        boolean mayHaveBody = "POST".equalsIgnoreCase(req.getMethod()) || "PUT".equalsIgnoreCase(req.getMethod()) ||
                              "PATCH".equalsIgnoreCase(req.getMethod());
        boolean bodyExpected = req.getContentLengthLong() > 0 || (mayHaveBody && contentType != null);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", req.getMethod());
        http.put("fullPath", hasQuery ? (req.getRequestURI() + "?" + queryString) : req.getRequestURI());

        Map<String, Object> requestNode = new LinkedHashMap<>();
        requestNode.put("inputType", resolveInputType(hasQuery, hasBody, bodyExpected));
        requestNode.put("queryParams", HttpLogExtractors.queryParams(req));
        requestNode.put("headers", HttpLogExtractors.allowedHeaders(req, props.getController().getAllowedHeaders()));
        requestNode.put("contentType", contentType);
        requestNode.put("contentLength", req.getContentLengthLong());

        if (bodyExpected || hasBody) {
            if (!props.getController().isLogRequestBody()) {
                requestNode.put("bodyCapture", "DISABLED");
            } else if (!allowedCt) {
                requestNode.put("bodyCapture", "SKIPPED_CONTENT_TYPE");
            } else if (!hasBody) {
                requestNode.put("bodyCapture", "NOT_AVAILABLE");
            } else {
                Charset cs = Charset.forName(req.getCharacterEncoding() != null ? req.getCharacterEncoding() : "UTF-8");
                requestNode.putAll(captureBodyNode(bodyBytes, contentType, props.getController().getMaxRequestBodyBytes(), cs));
            }
        }

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", EVT_CONTROLLER_HTTP);
        stdlog.put("direction", "IN");
        stdlog.putAll(baseStdlog(req));
        stdlog.put("http", http);
        stdlog.put("request", requestNode);

        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private void logOut(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        int status = res.getStatus();
        StdlogLevel level = controllerOutLevel(status);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", status);

        Map<String, Object> responseNode = new LinkedHashMap<>();

        if (!props.getController().isLogResponseBody()) {
            responseNode.put("bodyCapture", "DISABLED");
        } else {
            String contentType = res.getContentType();
            boolean allowedCt = HttpLogExtractors.isAllowedContentType(contentType, props.getController().getAllowedContentTypes());
            byte[] bytes = res.getContentAsByteArray();
            boolean hasBody = bytes != null && bytes.length > 0;

            if (!hasBody) {
                responseNode.put("bodyCapture", "EMPTY");
            } else if (!allowedCt) {
                responseNode.put("bodyCapture", "SKIPPED_CONTENT_TYPE");
                responseNode.put("contentType", contentType);
            } else {
                Charset cs = Charset.forName(res.getCharacterEncoding() != null ? res.getCharacterEncoding() : "UTF-8");
                responseNode.putAll(captureBodyNode(bytes, contentType, props.getController().getMaxResponseBodyBytes(), cs));
            }
        }

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", EVT_CONTROLLER_HTTP);
        stdlog.put("direction", "OUT");
        stdlog.putAll(baseStdlog(req));
        stdlog.put("elapsedMs", elapsedMs(req));
        stdlog.put("outcome", (status >= 400) ? "FAILURE" : "SUCCESS");
        stdlog.put("http", http);
        stdlog.put("response", responseNode);

        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private StdlogLevel controllerOutLevel(int status) {
        if (status >= 500) {
            return props.getController().getOutLevelFailure5xx();
        }
        if (status >= 400) {
            return props.getController().getOutLevelFailure4xx();
        }
        return props.getController().getOutLevelSuccess();
    }

    private Map<String, Object> baseStdlog(HttpServletRequest req) {
        Map<String, Object> stdlog = new LinkedHashMap<>();

        Object op = req.getAttribute(StdlogAttrs.OPERATION);
        Object route = req.getAttribute(StdlogAttrs.ROUTE);

        if (op != null) {
            stdlog.put("operation", op);
        }
        if (route != null) {
            stdlog.put("route", route);
        }

        String requestId = MDC.get("request_id");
        if (requestId != null && !requestId.isBlank()) {
            stdlog.put("request_id", requestId);
        }

        return stdlog;
    }

    private long elapsedMs(HttpServletRequest req) {
        Object start = req.getAttribute(StdlogAttrs.START_NANO);
        if (start instanceof Long s) {
            return (System.nanoTime() - s) / 1_000_000;
        }
        return -1;
    }

    /**
     * Determina el tipo de input del request según la presencia de query params y body.
     *
     * @return {@code QUERY_AND_BODY}, {@code QUERY}, {@code BODY} o {@code NONE}
     */
    private static String resolveInputType(boolean hasQuery, boolean hasBody, boolean bodyExpected) {
        if (hasQuery && (hasBody || bodyExpected)) {
            return "QUERY_AND_BODY";
        }
        if (hasQuery) {
            return "QUERY";
        }
        if (hasBody || bodyExpected) {
            return "BODY";
        }
        return "NONE";
    }

    /**
     * Convierte un array de bytes de body HTTP en un nodo de log con {@code body},
     * {@code bodyFormat} y opcionalmente {@code bodyTruncated}.
     *
     * <p>Si el body excede {@code maxBytes} se trunca y se marca {@code bodyTruncated=true}.
     * Si el content-type es JSON se intenta parsear para incluirlo como objeto estructurado;
     * si el parse falla se incluye como texto con {@code bodyFormat=TEXT_INVALID_JSON}.</p>
     *
     * @param bytes       bytes del body; no debe ser vacío ni nulo
     * @param contentType content-type del request/response; puede ser {@code null}
     * @param maxBytes    máximo de bytes a incluir; {@code 0} no aplica límite
     * @param charset     charset para decodificar los bytes
     * @return mapa con las entradas {@code body}, {@code bodyFormat} y opcionalmente {@code bodyTruncated}
     */
    private Map<String, Object> captureBodyNode(byte[] bytes, String contentType, int maxBytes, Charset charset) {
        Map<String, Object> node = new LinkedHashMap<>();
        boolean truncated = maxBytes > 0 && bytes.length > maxBytes;
        int len = (maxBytes > 0) ? Math.min(bytes.length, maxBytes) : bytes.length;
        String bodyStr = new String(bytes, 0, len, charset);

        if (truncated) {
            node.put("body", bodyStr);
            node.put("bodyTruncated", true);
            node.put("bodyFormat", "TEXT_TRUNCATED");
        } else if (isJsonContentType(contentType)) {
            Object parsed = parseJsonBestEffort(bodyStr);
            if (parsed != null) {
                node.put("body", parsed);
                node.put("bodyFormat", "JSON");
            } else {
                node.put("body", bodyStr);
                node.put("bodyFormat", "TEXT_INVALID_JSON");
            }
        } else {
            node.put("body", bodyStr);
            node.put("bodyFormat", "TEXT");
        }
        return node;
    }

    private boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.contains("application/json") || ct.contains("+json");
    }

    private Object parseJsonBestEffort(String bodyStr) {
        try {
            return objectMapper.readValue(bodyStr, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
