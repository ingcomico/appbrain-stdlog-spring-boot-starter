package appbrain.stdlog.error;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTraceUtilTest {

    @Test
    void shouldReturnEmptyListWhenThrowableIsNull() {
        assertTrue(AppTraceUtil.appTrace(null, "com.example.", 15).isEmpty());
    }

    @Test
    void shouldReturnEmptyListWhenPackagePrefixIsNull() {
        RuntimeException ex = new RuntimeException("boom");
        assertTrue(AppTraceUtil.appTrace(ex, null, 15).isEmpty());
    }

    @Test
    void shouldReturnEmptyListWhenPackagePrefixIsBlank() {
        RuntimeException ex = new RuntimeException("boom");
        assertTrue(AppTraceUtil.appTrace(ex, "  ", 15).isEmpty());
    }

    @Test
    void shouldOnlyIncludeFramesMatchingThePackagePrefix() {
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.myapp.ServiceA", "doWork", "ServiceA.java", 42),
                new StackTraceElement("org.springframework.web.Dispatcher", "handle", "Dispatcher.java", 10),
                new StackTraceElement("com.example.myapp.ServiceB", "callB", "ServiceB.java", 7),
                new StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
        });

        List<String> trace = AppTraceUtil.appTrace(ex, "com.example.myapp.", 15);

        assertEquals(List.of(
                "com.example.myapp.ServiceA#doWork:42",
                "com.example.myapp.ServiceB#callB:7"
        ), trace);
    }

    @Test
    void shouldExcludeFramesWithoutLineNumberInfo() {
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.myapp.Native", "call", null, -1),
                new StackTraceElement("com.example.myapp.ServiceA", "doWork", "ServiceA.java", 42),
        });

        List<String> trace = AppTraceUtil.appTrace(ex, "com.example.myapp.", 15);

        assertEquals(List.of("com.example.myapp.ServiceA#doWork:42"), trace);
    }

    @Test
    void shouldRespectMaxFramesLimit() {
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.myapp.A", "m1", "A.java", 1),
                new StackTraceElement("com.example.myapp.B", "m2", "B.java", 2),
                new StackTraceElement("com.example.myapp.C", "m3", "C.java", 3),
        });

        List<String> trace = AppTraceUtil.appTrace(ex, "com.example.myapp.", 2);

        assertEquals(2, trace.size());
        assertEquals("com.example.myapp.A#m1:1", trace.get(0));
        assertEquals("com.example.myapp.B#m2:2", trace.get(1));
    }
}
