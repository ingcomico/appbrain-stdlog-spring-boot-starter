package appbrain.stdlog.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StdlogExceptionResolverTest {

    @Test
    void shouldStoreExceptionAsRequestAttributeAndReturnNull() {
        StdlogExceptionResolver resolver = new StdlogExceptionResolver();
        MockHttpServletRequest req = new MockHttpServletRequest();
        RuntimeException ex = new RuntimeException("boom");

        ModelAndView result = resolver.resolveException(req, new MockHttpServletResponse(), new Object(), ex);

        assertNull(result, "no debe consumir la excepción");
        assertSame(ex, req.getAttribute(StdlogAttrs.ERROR));
    }
}
