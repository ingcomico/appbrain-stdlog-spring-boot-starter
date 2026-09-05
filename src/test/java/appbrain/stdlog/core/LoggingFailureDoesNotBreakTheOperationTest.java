package appbrain.stdlog.core;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.jdbc.StdlogClientDbQueryListener;
import appbrain.stdlog.restclient.StdlogClientHttpInterceptor;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.AbstractList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La invariante de {@code ADR-0011}, comprobada en los tres módulos que hasta ahora emitían
 * sin red: <b>un fallo del logging no puede alterar el resultado de la operación
 * instrumentada</b>.
 *
 * <p>En cada caso se fuerza una {@code RuntimeException} durante la <b>construcción</b> del
 * payload —no durante la emisión—, porque es ahí donde estaba el hueco: la red de
 * {@code StdlogEmitter} no alcanza la construcción, que ocurre antes en cada módulo.</p>
 *
 * <p>El detonante es una lista que revienta al iterarla, colocada donde cada módulo la va a
 * recorrer al armar el evento.</p>
 */
class LoggingFailureDoesNotBreakTheOperationTest {

    /** Lista que lanza al iterarla: simula un fallo inesperado construyendo el payload. */
    private static final class ExplodingList extends AbstractList<String> {
        @Override public String get(int index) { throw new IllegalStateException("boom construyendo el payload"); }
        @Override public int size() { return 1; }
    }

    @BeforeEach
    void setUp() {
        StdlogFailsafe.resetFailureCount();
    }

    @AfterEach
    void tearDown() {
        StdlogFailsafe.resetFailureCount();
    }

    private static StdlogProperties propsThatBreakLogging() {
        StdlogProperties p = new StdlogProperties();
        p.getController().setEnabled(true);
        p.getController().setLogRequestBody(false);
        p.getController().setLogResponseBody(false);
        // Se recorre al armar el nodo `request.headers`.
        p.getController().setAllowedHeaders(new ExplodingList());
        // Se recorre al decidir si el content-type permite capturar el body.
        p.getRestclient().setRequestHeadersAllowlist(new ExplodingList());
        p.getRestclient().setLogAllRequestHeaders(false);
        return p;
    }

    // ---------- filtro servlet ----------

    @Test
    void servletFilterMustNotBreakTheRequest() throws Exception {
        var filter = StdlogTestSupport.controllerFilter(propsThatBreakLogging());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("x-routing", "MCO");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter.doFilter(req, res, new MockFilterChain()),
                "un fallo de logging no puede convertir un request correcto en un error del cliente");
        assertTrue(StdlogFailsafe.failureCount() > 0, "el fallo debe quedar registrado, no ignorado");
    }

    /** El caso más expuesto: el finally corre después de generar la respuesta. */
    @Test
    void servletFilterMustNotBreakTheRequestWhenBodyCachingIsOn() throws Exception {
        StdlogProperties p = propsThatBreakLogging();
        p.getController().setLogRequestBody(true);
        p.getController().setLogResponseBody(true);

        var filter = StdlogTestSupport.controllerFilter(p);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
        req.setContentType("application/json");
        req.setContent("{\"a\":1}".getBytes());
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter.doFilter(req, res, new MockFilterChain()));
        assertTrue(StdlogFailsafe.failureCount() > 0);
    }

    // ---------- listener JDBC ----------

    @Test
    void jdbcListenerMustNotBreakTheQuery() {
        StdlogProperties p = new StdlogProperties();
        p.getJdbc().setEnabled(true);

        ExecutionInfo info = new ExecutionInfo();
        info.setElapsedTime(5);
        // getQuery() revienta al armar el statement del evento.
        List<QueryInfo> queries = List.of(new QueryInfo() {
            @Override public String getQuery() { throw new IllegalStateException("boom leyendo el SQL"); }
        });

        assertDoesNotThrow(() -> new StdlogClientDbQueryListener(p).afterQuery(info, queries),
                "un fallo de logging no puede propagarse a la query del consumidor");
        assertTrue(StdlogFailsafe.failureCount() > 0);
    }

    // ---------- interceptor HTTP saliente ----------

    @Test
    void outgoingHttpInterceptorMustNotBreakTheCallAndMustKeepTheResponse() throws Exception {
        // Con logAllRequestHeaders=true la allowlist ni se mira, así que el detonante no
        // llegaba a dispararse y el test pasaba con y sin la red: no probaba nada.
        StdlogProperties p = propsThatBreakLogging();
        p.getRestclient().setLogAllRequestHeaders(false);

        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(URI.create("https://api.example.com/ok"));
        request.setMethod(org.springframework.http.HttpMethod.GET);
        // Sin cabeceras, headersFrom(...) sale antes y la allowlist nunca se recorre.
        request.getHeaders().add("x-routing", "MCO");

        MockClientHttpResponse upstream = new MockClientHttpResponse("respuesta".getBytes(), HttpStatus.OK);

        var result = assertDoesNotThrow(() -> new StdlogClientHttpInterceptor(p)
                .intercept(request, new byte[0], (req, body) -> upstream));

        assertEquals(200, result.getStatusCode().value(), "la llamada saliente debe completarse igual");
        assertEquals("respuesta", new String(result.getBody().readAllBytes()),
                "si la emisión falla se devuelve la respuesta original, no una rota");
    }
}
