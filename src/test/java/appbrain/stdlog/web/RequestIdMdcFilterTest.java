package appbrain.stdlog.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdMdcFilterTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @Test
    void shouldGenerateRequestIdWhenHeaderIsAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcDuringChain[0] = MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));

        assertTrue(UUID_PATTERN.matcher(mdcDuringChain[0]).matches());
        assertEquals(mdcDuringChain[0], response.getHeader(RequestIdMdcFilter.HEADER_REQUEST_ID));
        assertNull(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID), "MDC debe limpiarse después del filtro");
    }

    @Test
    void shouldReuseIncomingRequestIdHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.HEADER_REQUEST_ID, "incoming-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcDuringChain[0] = MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));

        assertEquals("incoming-id-123", mdcDuringChain[0]);
        assertEquals("incoming-id-123", response.getHeader(RequestIdMdcFilter.HEADER_REQUEST_ID));
    }

    @Test
    void shouldGenerateRequestIdWhenIncomingHeaderIsBlank() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.HEADER_REQUEST_ID, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcDuringChain[0] = MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));

        assertTrue(UUID_PATTERN.matcher(mdcDuringChain[0]).matches());
    }

    @Test
    void shouldClearMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        }));

        assertNull(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));
    }
}
