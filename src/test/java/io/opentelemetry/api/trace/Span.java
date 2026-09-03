package io.opentelemetry.api.trace;

public final class Span {

    private static volatile Span current = new Span(SpanContext.invalid());

    private final SpanContext spanContext;

    public Span(SpanContext spanContext) {
        this.spanContext = spanContext;
    }

    public static Span current() {
        return current;
    }

    public static void setCurrent(Span span) {
        current = span;
    }

    public static void reset() {
        current = new Span(SpanContext.invalid());
    }

    public SpanContext getSpanContext() {
        return spanContext;
    }
}
