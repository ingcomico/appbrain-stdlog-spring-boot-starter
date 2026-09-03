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
        return OpenTelemetryAccess.currentSpan();
    }

    private static final class OpenTelemetryAccess {
        private static final Methods METHODS = resolveMethods();

        private OpenTelemetryAccess() {}

        private static TraceIds currentSpan() {
            if (METHODS == null) return TraceIds.empty();

            try {
                Object span = METHODS.current().invoke(null);
                if (span == null) {
                    return TraceIds.empty();
                }

                Object spanContext = METHODS.getSpanContext().invoke(span);
                if (spanContext == null) {
                    return TraceIds.empty();
                }

                Object valid = METHODS.isValid().invoke(spanContext);
                if (!Boolean.TRUE.equals(valid)) {
                    return TraceIds.empty();
                }

                return new TraceIds(
                        (String) METHODS.getTraceId().invoke(spanContext),
                        (String) METHODS.getSpanId().invoke(spanContext));
            } catch (Throwable ignored) {
                return TraceIds.empty();
            }
        }

        private static Methods resolveMethods() {
            try {
                Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
                Class<?> spanContextClass = Class.forName("io.opentelemetry.api.trace.SpanContext");
                return new Methods(
                        spanClass.getMethod("current"),
                        spanClass.getMethod("getSpanContext"),
                        spanContextClass.getMethod("isValid"),
                        spanContextClass.getMethod("getTraceId"),
                        spanContextClass.getMethod("getSpanId"));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private record Methods(Method current, Method getSpanContext, Method isValid,
                               Method getTraceId, Method getSpanId) {}
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
