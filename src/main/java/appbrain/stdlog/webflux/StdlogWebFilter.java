package appbrain.stdlog.webflux;

import appbrain.stdlog.StdlogExcluded;
import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.core.StdlogReactorContext;
import appbrain.stdlog.core.StdlogTraceCorrelation;
import appbrain.stdlog.error.AppTraceUtil;
import appbrain.stdlog.web.HttpLogExtractors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Filtro reactivo que emite {@code CONTROLLER_HTTP} y el evento extra de error para
 * aplicaciones WebFlux, con el mismo schema que la vía servlet
 * ({@code ControllerBodyAndOutLoggingFilter} + {@code StdlogExceptionResolver}).
 * Fase 1 de ADR-0008.
 *
 * <p>Establece la correlación del request en el <b>Reactor Context</b> ({@code request_id},
 * exclusión) para que los puntos de emisión reactivos aguas abajo la lean. La resolución de
 * {@code operation}/{@code route} se hace tras completar la cadena, desde los atributos del
 * {@link ServerWebExchange} que puebla el {@code HandlerMapping}.</p>
 *
 * <p>La emisión corre en un hilo del event-loop; el filtro restaura el MDC con los valores
 * capturados alrededor de cada {@code StdlogEmitter.emit(...)} — mismo patrón que el filtro de
 * {@code WebClient}. No modifica ningún componente servlet.</p>
 */
public class StdlogWebFilter implements WebFilter, Ordered {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Atributo del exchange donde se guarda la excepción (por {@code doOnError} o por {@code StdlogWebExceptionHandler}). */
    static final String ATTR_ERROR = "appbrain.stdlog.error";

    private final StdlogProperties props;

    public StdlogWebFilter(StdlogProperties props) {
        this.props = props;
    }

    @Override
    public int getOrder() {
        // Lo más externo posible dentro de la cadena de WebFilter, para envolver todo el request.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        StdlogProperties.Controller cc = props != null ? props.getController() : null;
        if (cc == null || !cc.isEnabled() || cc.getWebflux() == null || !cc.getWebflux().isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        boolean excluded = isExcluded(path, cc.getExcludedPathPatterns());

        String requestId = request.getHeaders().getFirst("x-request-id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        exchange.getResponse().getHeaders().set("x-request-id", requestId);

        long startNano = System.nanoTime();
        StdlogTraceCorrelation.TraceIds traceIds = StdlogTraceCorrelation.current();

        boolean captureReqBody = cc.isLogRequestBody()
                && HttpLogExtractors.isAllowedContentType(contentType(request.getHeaders()), cc.getAllowedContentTypes());
        BodyBuffer reqBuf = new BodyBuffer(cc.getMaxRequestBodyBytes());
        BodyBuffer resBuf = new BodyBuffer(cc.getMaxResponseBodyBytes());

        ServerWebExchange decorated = decorate(exchange, captureReqBody ? reqBuf : null,
                cc.isLogResponseBody() ? resBuf : null);

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        String fRequestId = requestId;
        boolean fExcluded = excluded;

        return chain.filter(decorated)
                .doOnError(errorRef::set)
                .doFinally(signal -> {
                    Throwable error = errorRef.get();
                    if (error != null) decorated.getAttributes().put(ATTR_ERROR, error);
                    emitAll(decorated, cc, fRequestId, traceIds, fExcluded, startNano,
                            captureReqBody ? reqBuf : null, cc.isLogResponseBody() ? resBuf : null, error);
                })
                .contextWrite(ctx -> {
                    ctx = ctx.put(StdlogReactorContext.REQUEST_ID, fRequestId);
                    if (fExcluded) ctx = ctx.put(StdlogReactorContext.EXCLUDED, Boolean.TRUE);
                    // El exchange también en el Context (misma key que ServerWebExchangeContextFilter)
                    // para que los clientes salientes resuelvan operation/route de forma perezosa.
                    ctx = ctx.put(ServerWebExchangeContextFilter.EXCHANGE_CONTEXT_ATTRIBUTE, exchange);
                    return ctx;
                });
    }

    // ---- emisión ----

    private void emitAll(ServerWebExchange exchange, StdlogProperties.Controller cc, String requestId,
            StdlogTraceCorrelation.TraceIds traceIds, boolean excluded, long startNano,
            BodyBuffer reqBuf, BodyBuffer resBuf, Throwable error) {

        String operation = resolveOperation(exchange);
        String route = resolveRoute(exchange);
        int status = resolveStatus(exchange, error);
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        // Exclusión por @StdlogExcluded en el handler (además de la de path, ya evaluada).
        // Sólo afecta a los eventos CONTROLLER_HTTP / error; los CLIENT_* aguas abajo ya se
        // emitieron. La exclusión por path sí se propaga (viaja en el Context).
        boolean excludedNow = excluded || hasStdlogExcluded(exchange);

        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (requestId != null) MDC.put("request_id", requestId);
            if (operation != null) MDC.put("operation", operation);
            if (traceIds != null && traceIds.traceId() != null) MDC.put("traceId", traceIds.traceId());
            if (traceIds != null && traceIds.spanId() != null) MDC.put("spanId", traceIds.spanId());
            if (excludedNow) MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");

            emitIn(exchange, cc, requestId, operation, route, reqBuf);
            emitOut(cc, requestId, operation, route, status, elapsedMs, resBuf);
            emitErrorEvent(exchange, requestId, operation, route, status, error);
        } catch (RuntimeException loggingFailure) {
            // nunca romper el request por un fallo de logging
        } finally {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        }
    }

    private void emitIn(ServerWebExchange exchange, StdlogProperties.Controller cc, String requestId,
            String operation, String route, BodyBuffer reqBuf) {

        ServerHttpRequest request = exchange.getRequest();
        boolean hasQuery = !request.getQueryParams().isEmpty();

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", request.getMethod().name());
        http.put("fullPath", fullPath(request));

        Map<String, Object> requestNode = new LinkedHashMap<>();
        requestNode.put("inputType", inputType(hasQuery, reqBuf != null && reqBuf.hasData()));
        requestNode.put("queryParams", queryParams(request));
        requestNode.put("headers", allowedHeaders(request.getHeaders(), cc.getAllowedHeaders()));
        requestNode.put("contentType", contentType(request.getHeaders()));
        requestNode.put("contentLength", request.getHeaders().getContentLength());
        if (reqBuf != null && reqBuf.hasData()) {
            putBody(requestNode, reqBuf, charsetOf(request.getHeaders()));
        }

        Map<String, Object> stdlog = base("CONTROLLER_HTTP", "IN", requestId, operation, route);
        stdlog.put("http", http);
        stdlog.put("request", requestNode);
        StdlogEmitter.emit(STDLOG, cc.getInLevel(), stdlog);
    }

    private void emitOut(StdlogProperties.Controller cc, String requestId, String operation, String route,
            int status, long elapsedMs, BodyBuffer resBuf) {

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", status);

        Map<String, Object> responseNode = new LinkedHashMap<>();
        if (!cc.isLogResponseBody()) {
            responseNode.put("bodyCapture", "DISABLED");
        } else if (resBuf == null || !resBuf.hasData()) {
            responseNode.put("bodyCapture", "EMPTY");
        } else {
            putBody(responseNode, resBuf, StandardCharsets.UTF_8);
        }

        Map<String, Object> stdlog = base("CONTROLLER_HTTP", "OUT", requestId, operation, route);
        stdlog.put("elapsedMs", elapsedMs);
        stdlog.put("outcome", status >= 400 ? "FAILURE" : "SUCCESS");
        stdlog.put("http", http);
        stdlog.put("response", responseNode);
        StdlogEmitter.emit(STDLOG, outLevel(cc, status), stdlog);
    }

    private void emitErrorEvent(ServerWebExchange exchange, String requestId, String operation, String route,
            int status, Throwable error) {

        if (props.getError() == null || !props.getError().isEnabled()) return;
        if (status < 400) return;

        boolean is5xx = status >= 500;
        Map<String, Object> stdlog = base(is5xx ? "ERROR" : "WARN", null, requestId, operation, route);
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", status);
        stdlog.put("http", http);

        Object exObj = exchange.getAttribute(ATTR_ERROR);
        Throwable ex = error != null ? error : (exObj instanceof Throwable t ? t : null);

        Map<String, Object> err = new LinkedHashMap<>();
        if (ex != null) {
            err.put("app_trace", AppTraceUtil.appTrace(ex, appTracePrefix(), 15));
            err.put("type", ex.getClass().getName());
            err.put("message", ex.getMessage());
        } else {
            err.put("message", "HTTP " + status + " (excepción no disponible; manejada por WebExceptionHandler)");
        }
        stdlog.put("error", err);

        StdlogLevel level = is5xx ? StdlogLevel.ERROR : StdlogLevel.WARN;
        if (ex != null) StdlogEmitter.emit(STDLOG, level, stdlog, ex);
        else StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private Map<String, Object> base(String event, String direction, String requestId,
            String operation, String route) {
        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", event);
        if (direction != null) stdlog.put("direction", direction);
        if (operation != null) stdlog.put("operation", operation);
        if (route != null) stdlog.put("route", route);
        if (requestId != null && !requestId.isBlank()) stdlog.put("request_id", requestId);
        return stdlog;
    }

    // ---- decoración para tee de bodies ----

    private ServerWebExchange decorate(ServerWebExchange exchange, BodyBuffer reqBuf, BodyBuffer resBuf) {
        if (reqBuf == null && resBuf == null) return exchange;

        ServerHttpRequest request = reqBuf == null ? exchange.getRequest()
                : new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return super.getBody().doOnNext(reqBuf::offer);
                    }
                };

        ServerHttpResponse response = resBuf == null ? exchange.getResponse()
                : new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        return super.writeWith(Flux.from(body).doOnNext(resBuf::offer));
                    }

                    @Override
                    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                        return super.writeAndFlushWith(Flux.from(body).map(p -> Flux.from(p).doOnNext(resBuf::offer)));
                    }
                };

        return exchange.mutate().request(request).response(response).build();
    }

    // ---- resolución ----

    private static String resolveOperation(ServerWebExchange exchange) {
        Object handler = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod hm) {
            return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        }
        return null;
    }

    private static boolean hasStdlogExcluded(ServerWebExchange exchange) {
        Object handler = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod hm)) return false;
        return AnnotatedElementUtils.hasAnnotation(hm.getMethod(), StdlogExcluded.class)
                || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), StdlogExcluded.class);
    }

    private static String resolveRoute(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = exchange.getRequest().getMethod().name();
        if (pattern != null) return method + " " + pattern;
        return method + " " + exchange.getRequest().getPath().pathWithinApplication().value();
    }

    private static int resolveStatus(ServerWebExchange exchange, Throwable error) {
        HttpStatusCode code = exchange.getResponse().getStatusCode();
        int status = code != null ? code.value() : 200;
        if (error != null && status < 400) status = 500;
        return status;
    }

    private StdlogLevel outLevel(StdlogProperties.Controller cc, int status) {
        if (status >= 500) return cc.getOutLevelFailure5xx();
        if (status >= 400) return cc.getOutLevelFailure4xx();
        return cc.getOutLevelSuccess();
    }

    private String appTracePrefix() {
        String basePkg = props.getConsumerBasePackage();
        return (basePkg != null && !basePkg.isBlank()) ? basePkg + "." : null;
    }

    private static boolean isExcluded(String path, List<String> patterns) {
        if (patterns == null) return false;
        return patterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .anyMatch(p -> PATH_MATCHER.match(p.trim(), path));
    }

    private static String fullPath(ServerHttpRequest request) {
        String p = request.getPath().pathWithinApplication().value();
        String q = request.getURI().getRawQuery();
        return (q != null && !q.isBlank()) ? p + "?" + q : p;
    }

    private static String inputType(boolean hasQuery, boolean hasBody) {
        if (hasBody) return hasQuery ? "QUERY_AND_BODY" : "BODY";
        return hasQuery ? "QUERY" : "NONE";
    }

    private static Map<String, Object> queryParams(ServerHttpRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        request.getQueryParams().forEach((k, v) -> out.put(k, v.size() == 1 ? v.get(0) : v));
        return out;
    }

    private static Map<String, String> allowedHeaders(HttpHeaders headers, List<String> allowlist) {
        Map<String, String> out = new LinkedHashMap<>();
        if (allowlist == null || allowlist.isEmpty()) return out;
        for (String name : allowlist) {
            if (name == null || name.isBlank()) continue;
            String v = headers.getFirst(name.trim());
            if (v != null) out.put(name.trim().toLowerCase(Locale.ROOT), v);
        }
        return out;
    }

    private static String contentType(HttpHeaders headers) {
        MediaType ct = headers.getContentType();
        return ct != null ? ct.toString() : null;
    }

    private static Charset charsetOf(HttpHeaders headers) {
        MediaType ct = headers.getContentType();
        if (ct != null && ct.getCharset() != null) return ct.getCharset();
        return StandardCharsets.UTF_8;
    }

    private static void putBody(Map<String, Object> node, BodyBuffer buf, Charset charset) {
        String text = buf.text(charset);
        if (text == null) return;
        node.put("body", text);
        String t = text.trim();
        node.put("bodyFormat", (t.startsWith("{") || t.startsWith("[")) ? "JSON" : "TEXT");
        if (buf.truncated()) node.put("bodyTruncated", true);
    }

    /** Bufferiza los primeros {@code max} bytes de un body sin consumir los {@code DataBuffer}. */
    static final class BodyBuffer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int max;
        private boolean truncated;
        private boolean used;

        BodyBuffer(int max) {
            this.max = max;
        }

        void offer(DataBuffer db) {
            used = true;
            try {
                ByteBuffer view = db.asByteBuffer().duplicate();
                int available = view.remaining();
                int room = (max <= 0) ? available : Math.max(0, max - out.size());
                int n = Math.min(available, room);
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    view.get(chunk);
                    out.write(chunk, 0, n);
                }
                if (n < available) truncated = true;
            } catch (RuntimeException ignored) {
                // no arriesgamos el request por un fallo de captura
            }
        }

        boolean hasData() {
            return used && out.size() > 0;
        }

        boolean truncated() {
            return truncated;
        }

        String text(Charset charset) {
            if (!hasData()) return null;
            return new String(out.toByteArray(), charset);
        }
    }
}
