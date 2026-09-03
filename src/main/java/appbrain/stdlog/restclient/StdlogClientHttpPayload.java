package appbrain.stdlog.restclient;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Construye el payload del evento {@code CLIENT_HTTP} a partir de datos ya extraídos de la
 * llamada HTTP saliente, de forma agnóstica al cliente que la originó.
 *
 * <p>Es código nuevo (no toca {@code StdlogClientHttpInterceptor}). Lo usa
 * {@link StdlogWebClientExchangeFilter} para producir exactamente el mismo formato de evento
 * que el interceptor síncrono de {@code RestTemplate} / {@code RestClient}. La lógica de
 * armado es un espejo de la del interceptor; ver ADR-0006.</p>
 */
final class StdlogClientHttpPayload {

    private StdlogClientHttpPayload() {}

    /**
     * @param source mapa {@code class/method/file/line} o {@code null}
     * @param requestBody body de request ya decodificado, o {@code null} si no se captura
     * @param responseBody body de response ya decodificado, o {@code null} si no se captura
     */
    static Map<String, Object> build(
            String method,
            String url,
            String host,
            int status,
            long elapsedMs,
            boolean failure,
            String requestId,
            String operation,
            String callId,
            Map<String, Object> source,
            HttpHeaders requestHeaders,
            boolean logAllRequestHeaders,
            Collection<String> requestHeadersAllowlist,
            StdlogHttpBodyDecoder.Decoded requestBody,
            HttpHeaders responseHeaders,
            boolean logAllResponseHeaders,
            Collection<String> responseHeadersAllowlist,
            StdlogHttpBodyDecoder.Decoded responseBody) {

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
        http.put("method", method);
        http.put("url", url);
        http.put("status", status);
        stdlog.put("http", http);

        Map<String, Object> peer = new LinkedHashMap<>();
        if (host != null) peer.put("host", host);
        stdlog.put("peer", peer);

        Map<String, Object> reqNode = new LinkedHashMap<>();
        reqNode.put("headers", headersMap(requestHeaders, logAllRequestHeaders, requestHeadersAllowlist));
        putDecoded(reqNode, requestBody);
        stdlog.put("request", reqNode);

        Map<String, Object> resNode = new LinkedHashMap<>();
        if (responseHeaders != null) {
            resNode.put("headers", headersMap(responseHeaders, logAllResponseHeaders, responseHeadersAllowlist));
        }
        putDecoded(resNode, responseBody);
        stdlog.put("response", resNode);

        return stdlog;
    }

    static Charset charsetOf(MediaType contentType) {
        if (contentType != null && contentType.getCharset() != null) return contentType.getCharset();
        return StandardCharsets.UTF_8;
    }

    static String guessFormat(String body) {
        if (body == null) return null;
        String t = body.trim();
        return (t.startsWith("{") || t.startsWith("[")) ? "JSON" : "TEXT";
    }

    private static void putDecoded(Map<String, Object> node, StdlogHttpBodyDecoder.Decoded decoded) {
        if (decoded == null) return;
        if (decoded.bodyEncoding != null) node.put("bodyEncoding", decoded.bodyEncoding);
        if (decoded.text != null) {
            node.put("body", decoded.text);
            node.put("bodyFormat", guessFormat(decoded.text));
        } else if (decoded.bodyBytes != null) {
            node.put("bodyBytes", decoded.bodyBytes);
            node.put("bodyDecodeError", decoded.decodeError);
        }
    }

    private static Map<String, Object> headersMap(HttpHeaders headers, boolean logAll, Collection<String> allowlist) {
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
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
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
}
