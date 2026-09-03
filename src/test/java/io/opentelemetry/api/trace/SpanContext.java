package io.opentelemetry.api.trace;

public final class SpanContext {

    private final boolean valid;
    private final String traceId;
    private final String spanId;

    public SpanContext(boolean valid, String traceId, String spanId) {
        this.valid = valid;
        this.traceId = traceId;
        this.spanId = spanId;
    }

    public static SpanContext invalid() {
        return new SpanContext(false, null, null);
    }

    public boolean isValid() {
        return valid;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }
}
