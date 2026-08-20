package appbrain.stdlog.util;

import appbrain.stdlog.util.consumer.ConsumerCaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StdlogCallerResolverTest {

    private static final String CONSUMER_BASE_PACKAGE = "appbrain.stdlog.util.consumer";

    @Test
    void shouldReturnFirstFrameMatchingBasePackage() {
        StackTraceElement caller = ConsumerCaller.callResolver(CONSUMER_BASE_PACKAGE);

        assertNotNull(caller);
        assertEquals(ConsumerCaller.class.getName(), caller.getClassName());
        assertEquals("callResolver", caller.getMethodName());
    }

    @Test
    void shouldSkipFramesInsideCommonsSubpackage() {
        // CommonsCaller vive en appbrain.stdlog.util.consumer.commons: matchea el prefijo
        // pero debe ser saltado por la regla ".commons.", cayendo en el frame de ConsumerCaller.
        StackTraceElement caller = ConsumerCaller.wrapThroughCommons(CONSUMER_BASE_PACKAGE);

        assertNotNull(caller);
        assertEquals(ConsumerCaller.class.getName(), caller.getClassName());
        assertEquals("wrapThroughCommons", caller.getMethodName());
    }

    @Test
    void shouldReturnNullWhenNoFrameMatchesBasePackage() {
        assertNull(StdlogCallerResolver.findConsumerCaller("com.doesnotexist.anywhere"));
    }

    @Test
    void shouldFallBackToHeuristicWhenBasePackageIsBlank() {
        StackTraceElement caller = StdlogCallerResolver.findConsumerCaller("  ");

        assertNotNull(caller);
        assertFalse(caller.getClassName().startsWith("appbrain.stdlog."));
        assertFalse(caller.getClassName().startsWith("org.springframework."));
        assertFalse(caller.getClassName().startsWith("java."));
    }

    @Test
    void shouldFallBackToHeuristicWhenBasePackageIsNull() {
        StackTraceElement caller = StdlogCallerResolver.findConsumerCaller(null);

        assertNotNull(caller);
        assertFalse(caller.getClassName().startsWith("appbrain.stdlog."));
    }
}
