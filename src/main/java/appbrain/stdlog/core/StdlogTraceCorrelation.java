package appbrain.stdlog.core;

import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extrae correlación de tracing para todos los eventos stdlog.
 */
public final class StdlogTraceCorrelation {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String STDLOG_TRACE_ID = "trace_id";
    public static final String STDLOG_SPAN_ID = "span_id";

    private StdlogTraceCorrelation() {}

    public static Map<String, Object> enrich(Map<String, Object> stdlog) {
        if (stdlog == null) {
            return null;
        }

        TraceIds traceIds = current();
        if (traceIds.isEmpty()) {
            return stdlog;
        }

        boolean needsTraceId = isBlank(stdlog.get(STDLOG_TRACE_ID)) && !isBlank(traceIds.traceId());
        boolean needsSpanId = isBlank(stdlog.get(STDLOG_SPAN_ID)) && !isBlank(traceIds.spanId());
        if (!needsTraceId && !needsSpanId) {
            return stdlog;
        }

        Map<String, Object> enriched = new LinkedHashMap<>(stdlog);
        if (needsTraceId) {
            enriched.put(STDLOG_TRACE_ID, traceIds.traceId());
        }
        if (needsSpanId) {
            enriched.put(STDLOG_SPAN_ID, traceIds.spanId());
        }
        return enriched;
    }

    public static TraceIds current() {
        String traceId = MDC.get(MDC_TRACE_ID);
        String spanId = MDC.get(MDC_SPAN_ID);
        if (!isBlank(traceId) || !isBlank(spanId)) {
            return new TraceIds(traceId, spanId);
        }
        return currentOpenTelemetrySpan();
    }

    private static TraceIds currentOpenTelemetrySpan() {
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Method current = spanClass.getMethod("current");
            Object span = current.invoke(null);
            if (span == null) {
                return TraceIds.empty();
            }

            Method getSpanContext = span.getClass().getMethod("getSpanContext");
            Object spanContext = getSpanContext.invoke(span);
            if (spanContext == null) {
                return TraceIds.empty();
            }

            Method isValid = spanContext.getClass().getMethod("isValid");
            Object valid = isValid.invoke(spanContext);
            if (!Boolean.TRUE.equals(valid)) {
                return TraceIds.empty();
            }

            Method getTraceId = spanContext.getClass().getMethod("getTraceId");
            Method getSpanId = spanContext.getClass().getMethod("getSpanId");
            return new TraceIds((String) getTraceId.invoke(spanContext), (String) getSpanId.invoke(spanContext));
        } catch (Throwable ignored) {
            return TraceIds.empty();
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    public record TraceIds(String traceId, String spanId) {
        private static TraceIds empty() {
            return new TraceIds(null, null);
        }

        private boolean isEmpty() {
            return isBlank(traceId) && isBlank(spanId);
        }
    }
}
