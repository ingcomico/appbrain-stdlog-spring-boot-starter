package appbrain.stdlog.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpLogExtractorsTest {

    @Test
    void allowedHeadersShouldReturnEmptyMapWhenAllowlistIsNull() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("x-routing", "beta");

        assertTrue(HttpLogExtractors.allowedHeaders(req, null).isEmpty());
    }

    @Test
    void allowedHeadersShouldReturnEmptyMapWhenAllowlistIsEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("x-routing", "beta");

        assertTrue(HttpLogExtractors.allowedHeaders(req, List.of()).isEmpty());
    }

    @Test
    void allowedHeadersShouldOnlyIncludeAllowlistedHeadersCaseInsensitively() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Routing", "beta,1092");
        req.addHeader("Authorization", "Bearer secret");

        Map<String, String> headers = HttpLogExtractors.allowedHeaders(req, List.of("x-routing"));

        assertEquals(1, headers.size());
        assertEquals("beta,1092", headers.get("X-Routing"));
    }

    @Test
    void queryParamsShouldReturnSingleValueAsString() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setQueryString("site_id=MCO");

        Map<String, Object> params = HttpLogExtractors.queryParams(req);

        assertEquals("MCO", params.get("site_id"));
    }

    @Test
    void queryParamsShouldReturnMultiValueAsArray() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setQueryString("id=10&id=20");

        Map<String, Object> params = HttpLogExtractors.queryParams(req);

        assertArrayEquals(new String[] {"10", "20"}, (String[]) params.get("id"));
    }

    @Test
    void queryParamsShouldDecodeFormStyleQueryComponents() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setQueryString("search=hello+world&path=orders%2F42");

        Map<String, Object> params = HttpLogExtractors.queryParams(req);

        assertEquals("hello world", params.get("search"));
        assertEquals("orders/42", params.get("path"));
    }

    @Test
    void queryParamsShouldReturnEmptyMapWhenNoParams() {
        MockHttpServletRequest req = new MockHttpServletRequest();

        assertTrue(HttpLogExtractors.queryParams(req).isEmpty());
    }

    @Test
    void queryParamsShouldNotIncludeUrlEncodedFormFields() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setContentType("application/x-www-form-urlencoded");
        req.setQueryString("source=web");
        req.addParameter("source", "web");
        req.addParameter("username", "ana");
        req.addParameter("password", "s3cr3t-real");

        Map<String, Object> params = HttpLogExtractors.queryParams(req);

        assertEquals(Map.of("source", "web"), params);
    }

    @Test
    void isAllowedContentTypeShouldReturnFalseWhenContentTypeIsNull() {
        assertFalse(HttpLogExtractors.isAllowedContentType(null, List.of("application/json")));
    }

    @Test
    void isAllowedContentTypeShouldMatchExactType() {
        assertTrue(HttpLogExtractors.isAllowedContentType("application/json", List.of("application/json")));
    }

    @Test
    void isAllowedContentTypeShouldMatchWildcardSuffix() {
        assertTrue(HttpLogExtractors.isAllowedContentType(
                "application/vnd.api+json", List.of("application/*+json")));
    }

    @Test
    void isAllowedContentTypeShouldReturnFalseWhenNotInAllowlist() {
        assertFalse(HttpLogExtractors.isAllowedContentType("application/xml", List.of("application/json")));
    }

    @Test
    void isAllowedContentTypeShouldReturnFalseOnUnparseableContentType() {
        assertFalse(HttpLogExtractors.isAllowedContentType("???not-a-media-type???", List.of("application/json")));
    }

    @Test
    void isAllowedContentTypeShouldMatchWhenCharsetIsPresent() {
        assertTrue(HttpLogExtractors.isAllowedContentType(
                "application/json;charset=UTF-8", List.of("application/json")));
    }
}
