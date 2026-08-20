package appbrain.stdlog.restclient;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.core.StdlogModeResolver;
import appbrain.stdlog.util.StdlogCallerResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Interceptor de llamadas HTTP salientes hechas via {@link org.springframework.web.client.RestTemplate}
 * o {@link org.springframework.web.client.RestClient} ({@code CLIENT_HTTP direction=IN}).
 *
 * <p>Estrategia <em>single-log</em>: se emite un único evento por llamada saliente, combinando
 * datos de request y response en el mismo JSON. {@link ClientHttpRequestInterceptor} entrega
 * request y response en el mismo método, por lo que no hace falta correlacionar dos interceptors
 * ni propagar atributos entre threads.</p>
 *
 * <p>El body de request/response solo se incluye cuando {@code logging.level.stdlog=DEBUG}
 * ({@code logger.isDebugEnabled()}). Si el body está comprimido (gzip/deflate), se descomprime
 * antes de loguear via {@link StdlogHttpBodyDecoder}.</p>
 *
 * <p>Cuando se captura el body de la respuesta, el interceptor retorna un wrapper
 * re-leíble para que los {@code HttpMessageConverter} de la aplicación puedan consumirlo
 * después del log.</p>
 *
 * <p>En modo {@code PROD} con {@code logOnlyOnFailureInProd=true}, las respuestas
 * exitosas (status {@code < 400}) se filtran completamente para reducir volumen de logs.</p>
 */
public class StdlogClientHttpInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");
    private static final String MDC_REQUEST_ID = "request_id";
    private static final String MDC_OPERATION = "operation";

    private final StdlogProperties props;

    public StdlogClientHttpInterceptor(StdlogProperties props) {
        this.props = props;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        if (props == null || props.getRestclient() == null || !props.getRestclient().isEnabled()) {
            return execution.execute(request, body);
        }

        String requestId = MDC.get(MDC_REQUEST_ID);
        String operation = MDC.get(MDC_OPERATION);

        String callId = props.getRestclient().isCaptureCallId() ? UUID.randomUUID().toString() : null;

        Map<String, Object> source = null;
        if (props.getRestclient().isCaptureSource()) {
            String basePkg = firstNonBlank(props.getRestclient().getConsumerBasePackage(), props.getConsumerBasePackage());
            StackTraceElement caller = StdlogCallerResolver.findConsumerCaller(basePkg);
            if (caller != null) {
                source = new LinkedHashMap<>();
                source.put("class", caller.getClassName());
                source.put("method", caller.getMethodName());
                source.put("file", caller.getFileName());
                source.put("line", caller.getLineNumber());
            }
        }

        long start = System.currentTimeMillis();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long elapsedMs = System.currentTimeMillis() - start;

            int status = response.getStatusCode().value();
            boolean failure = status >= 400;

            boolean isProd = StdlogModeResolver.isProd(props);
            if (!(isProd && props.getRestclient().isLogOnlyOnFailureInProd() && !failure)) {
                StdlogLevel level = levelForStatus(status);
                response = emit(request, body, response, status, elapsedMs, failure, level, null, requestId, operation, callId, source);
            }
            return response;
        } catch (IOException e) {
            long elapsedMs = System.currentTimeMillis() - start;
            StdlogLevel level = props.getRestclient().getInLevelFailure5xx();
            emit(request, body, null, 500, elapsedMs, true, level, e, requestId, operation, callId, source);
            throw e;
        }
    }

    private StdlogLevel levelForStatus(int status) {
        if (status >= 500) return props.getRestclient().getInLevelFailure5xx();
        if (status >= 400) return props.getRestclient().getInLevelFailure4xx();
        return props.getRestclient().getInLevelSuccess();
    }

    private ClientHttpResponse emit(HttpRequest request,
            byte[] requestBody,
            ClientHttpResponse response,
            int status,
            long elapsedMs,
            boolean failure,
            StdlogLevel level,
            Throwable t,
            String requestId,
            String operation,
            String callId,
            Map<String, Object> source) {

        ClientHttpResponse responseToReturn = response;
        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", "CLIENT_HTTP");
        stdlog.put("direction", "IN");
        stdlog.put("elapsedMs", elapsedMs);
        stdlog.put("outcome", failure ? "FAILURE" : "SUCCESS");

        if (requestId != null && !requestId.isBlank()) stdlog.put("request_id", requestId);
        if (operation != null && !operation.isBlank()) stdlog.put("operation", operation);
        if (callId != null) stdlog.put("call_id", callId);
        if (source != null) stdlog.put("source", source);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", String.valueOf(request.getMethod()));
        http.put("url", String.valueOf(request.getURI()));
        http.put("status", status);
        stdlog.put("http", http);

        Map<String, Object> peer = new LinkedHashMap<>();
        String host = request.getURI().getHost();
        if (host != null) peer.put("host", host);
        stdlog.put("peer", peer);

        // request node
        Map<String, Object> reqNode = new LinkedHashMap<>();
        reqNode.put("headers", headersFrom(
                request.getHeaders(),
                props.getRestclient().isLogAllRequestHeaders(),
                props.getRestclient().getRequestHeadersAllowlist()
        ));

        if (STDLOG.isDebugEnabled() && requestBody != null && requestBody.length > 0) {
            String contentEncoding = request.getHeaders().getFirst("content-encoding");
            Charset charset = charsetOf(request.getHeaders().getContentType());

            StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(
                    requestBody, contentEncoding, charset, props.getRestclient().getMaxBodyChars());

            putDecoded(reqNode, decoded);
        }
        stdlog.put("request", reqNode);

        // response node
        Map<String, Object> resNode = new LinkedHashMap<>();
        if (response != null) {
            try {
                resNode.put("headers", headersFrom(
                        response.getHeaders(),
                        props.getRestclient().isLogAllResponseHeaders(),
                        props.getRestclient().getResponseHeadersAllowlist()
                ));

                if (STDLOG.isDebugEnabled()) {
                    byte[] raw = StreamUtils.copyToByteArray(response.getBody());
                    responseToReturn = new CachedBodyClientHttpResponse(response, raw);
                    if (raw.length > 0) {
                        String contentEncoding = response.getHeaders().getFirst("content-encoding");
                        Charset charset = charsetOf(response.getHeaders().getContentType());

                        StdlogHttpBodyDecoder.Decoded decoded =
                                StdlogHttpBodyDecoder.decodeToText(raw, contentEncoding, charset, props.getRestclient().getMaxBodyChars());

                        putDecoded(resNode, decoded);
                    }
                }
            } catch (IOException ignored) {
                // no-op: no arriesgamos el log si no se pudo leer el body
            }
        }
        stdlog.put("response", resNode);

        if (t != null) StdlogEmitter.emit(STDLOG, level, stdlog, t);
        else StdlogEmitter.emit(STDLOG, level, stdlog);
        return responseToReturn;
    }

    private static Charset charsetOf(MediaType contentType) {
        if (contentType != null && contentType.getCharset() != null) return contentType.getCharset();
        return StandardCharsets.UTF_8;
    }

    private static void putDecoded(Map<String, Object> node, StdlogHttpBodyDecoder.Decoded decoded) {
        if (decoded.bodyEncoding != null) node.put("bodyEncoding", decoded.bodyEncoding);
        if (decoded.text != null) {
            node.put("body", decoded.text);
            node.put("bodyFormat", guessFormat(decoded.text));
        } else if (decoded.bodyBytes != null) {
            node.put("bodyBytes", decoded.bodyBytes);
            node.put("bodyDecodeError", decoded.decodeError);
        }
    }

    static Map<String, Object> headersFrom(HttpHeaders headers, boolean logAll, Collection<String> allowlist) {
        if (headers == null || headers.isEmpty()) return Map.of();

        Set<String> allowedLower = null;
        if (!logAll) {
            if (allowlist == null || allowlist.isEmpty()) return Map.of();
            allowedLower = allowlist.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.headerSet()) {
            if (e.getKey() == null) continue;

            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (allowedLower != null && !allowedLower.contains(key)) continue;

            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) out.put(key, null);
            else if (values.size() == 1) out.put(key, values.get(0));
            else out.put(key, values);
        }
        return out;
    }

    private static String guessFormat(String body) {
        if (body == null) return null;
        String t = body.trim();
        return (t.startsWith("{") || t.startsWith("[")) ? "JSON" : "TEXT";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static final class CachedBodyClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private CachedBodyClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
