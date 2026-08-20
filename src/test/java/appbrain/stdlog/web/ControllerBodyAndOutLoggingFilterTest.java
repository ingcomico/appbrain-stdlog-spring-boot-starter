package appbrain.stdlog.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import appbrain.stdlog.config.StdlogProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code excluded-path-patterns} ya no afecta {@code shouldNotFilter} (ver
 * {@link ControllerBodyAndOutLoggingFilterBehaviorTest} para el comportamiento de
 * exclusión real, ahora implementado via MDC en {@code doFilterInternal}). Esta clase
 * cubre únicamente los dos bypass "duros" que sí siguen viviendo en
 * {@code shouldNotFilter}: módulo deshabilitado o sin configuración.
 */
class ControllerBodyAndOutLoggingFilterTest {

    @Test
    void shouldFilterWhenControllerModuleIsEnabled() {
        StdlogProperties properties = new StdlogProperties();
        properties.getController().setEnabled(true);

        assertFalse(new TestableControllerLoggingFilter(properties).shouldSkip(request("/api/orders")));
    }

    @Test
    void shouldNotFilterWhenPropertiesAreNull() {
        assertTrue(new TestableControllerLoggingFilter(null).shouldSkip(request("/api/orders")));
    }

    @Test
    void shouldNotFilterWhenControllerModuleIsDisabled() {
        StdlogProperties properties = new StdlogProperties();
        properties.getController().setEnabled(false);

        assertTrue(new TestableControllerLoggingFilter(properties).shouldSkip(request("/api/orders")));
    }

    private static MockHttpServletRequest request(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return request;
    }

    private static final class TestableControllerLoggingFilter extends ControllerBodyAndOutLoggingFilter {

        private TestableControllerLoggingFilter(StdlogProperties properties) {
            super(properties, new ObjectMapper());
        }

        private boolean shouldSkip(MockHttpServletRequest request) {
            return shouldNotFilter(request);
        }
    }
}
