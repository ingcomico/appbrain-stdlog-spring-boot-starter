package appbrain.stdlog.r2dbc;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StdlogR2dbcQueryListenerTest {

    private ListAppender<ILoggingEvent> appender;
    private ConnectionFactory cf;
    private Connection conn;

    @BeforeEach
    void setUp() {
        String url = "r2dbc:h2:mem:///stdlog-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConnectionFactory real = ConnectionFactories.get(url);
        cf = ProxyConnectionFactory.builder(real)
                .listener(new StdlogR2dbcQueryListener(props()))
                .build();
        conn = Mono.from(cf.create()).block();
    }

    @AfterEach
    void tearDown() {
        if (conn != null) Mono.from(conn.close()).block();
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    // ---- listener bajo test con props propias (para casos que cambian config) ----

    private StdlogR2dbcQueryListener listener(StdlogProperties p) {
        return new StdlogR2dbcQueryListener(p);
    }

    private ConnectionFactory proxied(StdlogProperties p) {
        String url = "r2dbc:h2:mem:///stdlog-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        return ProxyConnectionFactory.builder(ConnectionFactories.get(url)).listener(listener(p)).build();
    }

    @Test
    void shouldLogSelectQuery() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);

        run(conn, "SELECT 1");

        Map<String, Object> payload = lastPayload();
        assertEquals("CLIENT_DB", payload.get("event"));
        assertEquals("OUT", payload.get("direction"));
        assertEquals("SUCCESS", payload.get("outcome"));
        assertEquals(false, payload.get("slow"));
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertEquals("SELECT", db.get("type"));
        assertTrue(String.valueOf(db.get("statement")).startsWith("SELECT 1"));
    }

    @Test
    void shouldLogInsertAndCaptureRequestIdAndOperationFromMdc() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.INFO);
        run(conn, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))");
        appender.list.clear();

        MDC.put("request_id", "req-42");
        MDC.put("operation", "UsersController#create");
        run(conn, "INSERT INTO t (id, name) VALUES (1, 'alice')");

        Map<String, Object> payload = lastPayload();
        assertEquals("INSERT", ((Map<?, ?>) payload.get("db")).get("type"));
        assertEquals("req-42", payload.get("request_id"));
        assertEquals("UsersController#create", payload.get("operation"));
    }

    @Test
    void shouldLogFailureOnInvalidSql() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);

        assertThrows(Exception.class, () -> run(conn, "SELECT * FROM does_not_exist"));

        Map<String, Object> payload = lastPayload();
        assertEquals("FAILURE", payload.get("outcome"));
        assertNotNull(payload.get("error"));
        assertNotNull(appender.list.get(appender.list.size() - 1).getThrowableProxy());
    }

    @Test
    void shouldMarkSlowQueryAndUseWarnLevel() {
        StdlogProperties p = props();
        p.getJdbc().setSlowQueryThresholdMs(1);
        Connection c = Mono.from(proxied(p).create()).block();
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            // consulta pesada: recorre un rango de 2M filas -> supera 1ms con holgura
            run(c, "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 2000000)");
            Map<String, Object> payload = lastPayload();
            assertEquals(true, payload.get("slow"));
            assertEquals(Level.WARN, appender.list.get(appender.list.size() - 1).getLevel());
        } finally {
            Mono.from(c.close()).block();
        }
    }

    @Test
    void shouldSkipSuccessInProdWhenLogOnlySlowOrFailureInProd() {
        StdlogProperties p = props();
        p.setMode(StdlogProperties.Mode.PROD);
        p.getJdbc().setLogOnlySlowOrFailureInProd(true);
        ConnectionFactory prodCf = proxied(p);
        Connection c = Mono.from(prodCf.create()).block();
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            run(c, "SELECT 1");
            assertTrue(appender.list.isEmpty());
        } finally {
            Mono.from(c.close()).block();
        }
    }

    @Test
    void shouldNotLogWhenR2dbcDisabled() {
        StdlogProperties p = props();
        p.getJdbc().getR2dbc().setEnabled(false);
        ConnectionFactory offCf = proxied(p);
        Connection c = Mono.from(offCf.create()).block();
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            run(c, "SELECT 1");
            assertTrue(appender.list.isEmpty());
        } finally {
            Mono.from(c.close()).block();
        }
    }

    @Test
    void shouldIncludeBoundParamsWhenLogParamsEnabled() {
        StdlogProperties p = props();
        p.getJdbc().setLogParams(true);
        ConnectionFactory paramCf = proxied(p);
        Connection c = Mono.from(paramCf.create()).block();
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        try {
            run(c, "CREATE TABLE p (id INT PRIMARY KEY, name VARCHAR(50))");
            appender.list.clear();
            Flux.from(c.createStatement("INSERT INTO p (id, name) VALUES ($1, $2)")
                            .bind("$1", 7).bind("$2", "bob").execute())
                    .flatMap(r -> Mono.from(r.getRowsUpdated()))
                    .collectList().block();

            Map<?, ?> db = (Map<?, ?>) lastPayload().get("db");
            Map<?, ?> params = (Map<?, ?>) db.get("params");
            assertNotNull(params);
            assertTrue(params.containsValue("bob") || params.containsValue(7));
        } finally {
            Mono.from(c.close()).block();
        }
    }

    // ---- helpers ----

    private static void run(Connection c, String sql) {
        Flux.from(c.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()).defaultIfEmpty(0L))
                .then()
                .block();
    }

    private static StdlogProperties props() {
        StdlogProperties p = new StdlogProperties();
        p.getJdbc().setEnabled(true);
        return p;
    }

    private Map<String, Object> lastPayload() {
        assertFalse(appender.list.isEmpty(), "se esperaba al menos un evento CLIENT_DB");
        return StdlogTestSupport.stdlogPayload(appender.list.get(appender.list.size() - 1));
    }
}
