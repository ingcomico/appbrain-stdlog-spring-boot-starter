package appbrain.stdlog.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StdlogTraceCorrelationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        Span.reset();
    }

    @Test
    void shouldReadValidOpenTelemetrySpanWhenMdcIsEmpty() {
        Span.setCurrent(new Span(new SpanContext(true, "otel-trace", "otel-span")));

        StdlogTraceCorrelation.TraceIds traceIds = StdlogTraceCorrelation.current();

        assertEquals("otel-trace", traceIds.traceId());
        assertEquals("otel-span", traceIds.spanId());
    }

    @Test
    void shouldIgnoreInvalidOpenTelemetrySpan() {
        Span.setCurrent(new Span(SpanContext.invalid()));

        StdlogTraceCorrelation.TraceIds traceIds = StdlogTraceCorrelation.current();

        assertNull(traceIds.traceId());
        assertNull(traceIds.spanId());
    }

    @Test
    void shouldPreferMdcOverOpenTelemetry() {
        Span.setCurrent(new Span(new SpanContext(true, "otel-trace", "otel-span")));
        MDC.put(StdlogTraceCorrelation.MDC_TRACE_ID, "mdc-trace");
        MDC.put(StdlogTraceCorrelation.MDC_SPAN_ID, "mdc-span");

        StdlogTraceCorrelation.TraceIds traceIds = StdlogTraceCorrelation.current();

        assertEquals("mdc-trace", traceIds.traceId());
        assertEquals("mdc-span", traceIds.spanId());
    }
}
