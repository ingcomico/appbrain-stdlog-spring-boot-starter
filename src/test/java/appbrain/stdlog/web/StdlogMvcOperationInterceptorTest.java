package appbrain.stdlog.web;

import appbrain.stdlog.StdlogExcluded;
import appbrain.stdlog.core.StdlogEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

class StdlogMvcOperationInterceptorTest {

    private final StdlogMvcOperationInterceptor interceptor = new StdlogMvcOperationInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldResolveOperationFromHandlerMethod() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/configcases/v1/tags");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/configcases/v1/tags");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new TagsController(), TagsController.class.getMethod("searchTags"));

        assertTrue(interceptor.preHandle(req, res, handler));

        assertEquals("TagsController#searchTags", req.getAttribute(StdlogAttrs.OPERATION));
        assertEquals("/configcases/v1/tags", req.getAttribute(StdlogAttrs.PATH_PATTERN));
        assertEquals("GET /configcases/v1/tags", req.getAttribute(StdlogAttrs.ROUTE));
        assertEquals("TagsController#searchTags", MDC.get("operation"));
        assertNotNull(req.getAttribute(StdlogAttrs.START_NANO));
        assertNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void shouldMarkExcludedInMdcWhenMethodHasStdlogExcluded() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/ping");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("ping"));

        interceptor.preHandle(req, res, handler);

        assertNotNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void shouldMarkExcludedInMdcWhenControllerClassHasStdlogExcluded() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/dump");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(
                new InternalDiagnosticsController(), InternalDiagnosticsController.class.getMethod("dump"));

        interceptor.preHandle(req, res, handler);

        assertNotNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void shouldMarkExcludedInMdcWhenComposedMetaAnnotationIsPresent() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/meta");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("metaExcluded"));

        interceptor.preHandle(req, res, handler);

        assertNotNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void shouldNotMarkExcludedForOtherMethodsOfSameController() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("list"));

        interceptor.preHandle(req, res, handler);

        assertNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void shouldNotMarkExcludedForNonHandlerMethodHandlers() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        interceptor.preHandle(req, res, new Object());

        assertNull(MDC.get(StdlogEmitter.MDC_EXCLUDED));
    }

    @Test
    void afterCompletionShouldNotClearExclusionFlagLeavingItToTheFilter() throws NoSuchMethodException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/ping");
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("ping"));
        interceptor.preHandle(req, res, handler);

        interceptor.afterCompletion(req, res, handler, null);

        assertNotNull(MDC.get(StdlogEmitter.MDC_EXCLUDED), "la limpieza es responsabilidad del filtro, no del interceptor");
    }

    @Test
    void shouldFallBackToMethodAndUriForNonHandlerMethodHandlers() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Object handler = new Object();

        interceptor.preHandle(req, res, handler);

        assertEquals("GET /actuator/health", req.getAttribute(StdlogAttrs.OPERATION));
        assertEquals("GET /actuator/health", req.getAttribute(StdlogAttrs.ROUTE));
    }

    @Test
    void shouldUseRequestUriAsRouteWhenNoPatternAttribute() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        interceptor.preHandle(req, res, new Object());

        assertNull(req.getAttribute(StdlogAttrs.PATH_PATTERN));
        assertEquals("GET /actuator/health", req.getAttribute(StdlogAttrs.ROUTE));
    }

    @Test
    void shouldNotOverwriteStartNanoWhenAlreadySet() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        req.setAttribute(StdlogAttrs.START_NANO, 123L);

        interceptor.preHandle(req, res, new Object());

        assertEquals(123L, req.getAttribute(StdlogAttrs.START_NANO));
    }

    @Test
    void shouldRemoveOperationFromMdcOnAfterCompletion() {
        MDC.put("operation", "SomeController#someMethod");

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertNull(MDC.get("operation"));
    }

    static class TagsController {
        public void searchTags() {}
    }

    static class OrdersController {
        @StdlogExcluded
        public void ping() {}

        public void list() {}

        @MetaExcluded
        public void metaExcluded() {}
    }

    @StdlogExcluded
    static class InternalDiagnosticsController {
        public void dump() {}
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @StdlogExcluded
    @interface MetaExcluded {
    }
}
