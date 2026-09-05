package appbrain.stdlog.jdbc;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogClientDbQueryListenerTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void shouldNotLogWhenJdbcDisabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setEnabled(false);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(10, null), queries("SELECT 1"));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldLogSuccessfulSelect() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLevelSuccess(StdlogLevel.INFO);

        new StdlogClientDbQueryListener(props).afterQuery(
                execInfo(10, null), queries("SELECT * FROM tags WHERE site_id = ?"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("CLIENT_DB", payload.get("event"));
        assertEquals("OUT", payload.get("direction"));
        assertEquals("SUCCESS", payload.get("outcome"));
        assertEquals(false, payload.get("slow"));
        assertEquals(Level.INFO, appender.list.get(0).getLevel());

        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertEquals("SELECT * FROM tags WHERE site_id = ?", db.get("statement"));
        assertEquals("SELECT", db.get("type"));
    }

    @Test
    void shouldLogFailureWithErrorDetails() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLevelFailure(StdlogLevel.ERROR);
        RuntimeException error = new RuntimeException("syntax error");

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(5, error), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("FAILURE", payload.get("outcome"));
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
        Map<?, ?> err = (Map<?, ?>) payload.get("error");
        assertEquals("java.lang.RuntimeException", err.get("type"));
        assertEquals("syntax error", err.get("message"));
        assertNotNull(appender.list.get(0).getThrowableProxy());
    }

    @Test
    void shouldMarkAsSlowAndUseWarnLevelWhenAboveThreshold() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setSlowQueryThresholdMs(50);
        props.getJdbc().setLevelSuccess(StdlogLevel.INFO);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(200, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        assertEquals(true, payload.get("slow"));
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
    }

    @Test
    void shouldNotBeSlowWhenThresholdIsZero() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setSlowQueryThresholdMs(0);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(999999, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        assertEquals(false, payload.get("slow"));
    }

    @Test
    void shouldSkipFastSuccessInProdWhenLogOnlySlowOrFailureInProdEnabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getJdbc().setLogOnlySlowOrFailureInProd(true);
        props.getJdbc().setSlowQueryThresholdMs(1000);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(10, null), queries("SELECT 1"));

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void shouldStillLogSlowQueryInProdWhenLogOnlySlowOrFailureInProdEnabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getJdbc().setLogOnlySlowOrFailureInProd(true);
        props.getJdbc().setSlowQueryThresholdMs(50);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(200, null), queries("SELECT 1"));

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldStillLogFailureInProdWhenLogOnlySlowOrFailureInProdEnabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.setMode(StdlogProperties.Mode.PROD);
        props.getJdbc().setLogOnlySlowOrFailureInProd(true);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, new RuntimeException("x")), queries("SELECT 1"));

        assertEquals(1, appender.list.size());
    }

    @Test
    void shouldTruncateSqlToMaxSqlChars() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setMaxSqlChars(10);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT * FROM very_long_table_name"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertEquals("SELECT * F...(truncated)", db.get("statement"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeExplicitParamsWhenLogParamsEnabled() throws NoSuchMethodException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLogParams(true);

        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        Method setNull = PreparedStatement.class.getMethod("setNull", int.class, int.class);
        ParameterSetOperation opString = new ParameterSetOperation(setString, new Object[] {1, "MCO"});
        ParameterSetOperation opNull = new ParameterSetOperation(setNull, new Object[] {2, 0});

        QueryInfo qi = new QueryInfo("SELECT * FROM tags WHERE site_id = ? AND owner = ?");
        List<List<ParameterSetOperation>> parametersList = new ArrayList<>();
        parametersList.add(List.of(opString, opNull));
        qi.setParametersList(parametersList);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), List.of(qi));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        List<Map<String, Object>> params = (List<Map<String, Object>>) db.get("params");
        assertEquals(1, params.size());
        List<List<Map<String, Object>>> sets = (List<List<Map<String, Object>>>) params.get(0).get("params");
        List<Map<String, Object>> ops = sets.get(0);

        assertEquals("setString", ops.get(0).get("m"));
        assertEquals(1, ops.get(0).get("i"));
        assertEquals("VARCHAR", ops.get(0).get("t"));
        assertEquals("MCO", ops.get(0).get("v"));

        assertEquals("setNull", ops.get(1).get("m"));
        assertEquals(2, ops.get(1).get("i"));
        assertEquals("OBJECT", ops.get(1).get("t"));
        assertNull(ops.get(1).get("v"));
    }

    @Test
    void shouldOmitParamsWhenLogParamsDisabled() throws NoSuchMethodException {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLogParams(false);

        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        ParameterSetOperation op = new ParameterSetOperation(setString, new Object[] {1, "MCO"});
        QueryInfo qi = new QueryInfo("SELECT 1");
        List<List<ParameterSetOperation>> parametersList = new ArrayList<>();
        parametersList.add(List.of(op));
        qi.setParametersList(parametersList);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), List.of(qi));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertFalse(db.containsKey("params"));
    }

    @Test
    void shouldIncludeResultSetResponseInfoForSelect() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLogResponseInfo(true);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        Map<?, ?> response = (Map<?, ?>) db.get("response");
        assertEquals("RESULT_SET", response.get("type"));
    }

    @Test
    void shouldIncludeUpdateCountResponseInfoForDml() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLogResponseInfo(true);

        ExecutionInfo execInfo = execInfo(1, null);
        execInfo.setResult(3);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo, queries("UPDATE tags SET name = 'x'"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        Map<?, ?> response = (Map<?, ?>) db.get("response");
        assertEquals("UPDATE_COUNT", response.get("type"));
        assertEquals(3, response.get("rowsAffected"));
    }

    @Test
    void shouldOmitResponseInfoWhenDisabled() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setLogResponseInfo(false);

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertFalse(db.containsKey("response"));
    }

    @Test
    void shouldIncludeOperationAndRequestIdFromMdc() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("operation", "TagsController#searchTags");
        MDC.put("request_id", "uuid-9");
        StdlogProperties props = props();

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("TagsController#searchTags", payload.get("operation"));
        assertEquals("uuid-9", payload.get("request_id"));
    }

    @Test
    void shouldIncludeTraceAndSpanIdsFromMdc() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("traceId", "trace-client-db");
        MDC.put("spanId", "span-client-db");
        StdlogProperties props = props();

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        assertEquals("trace-client-db", payload.get("trace_id"));
        assertEquals("span-client-db", payload.get("span_id"));
    }

    @Test
    void shouldUseDataSourceNameOverPoolNameWhenPresent() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setPoolName("configured-pool");

        ExecutionInfo execInfo = execInfo(1, null);
        execInfo.setDataSourceName("actual-datasource");

        new StdlogClientDbQueryListener(props).afterQuery(execInfo, queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> peer = (Map<?, ?>) payload.get("peer");
        assertEquals("actual-datasource", peer.get("pool"));
    }

    @Test
    void shouldFallBackToConfiguredPoolNameWhenDataSourceNameIsBlank() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        props.getJdbc().setPoolName("configured-pool");

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), queries("SELECT 1"));

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> peer = (Map<?, ?>) payload.get("peer");
        assertEquals("configured-pool", peer.get("pool"));
    }

    @Test
    void shouldInferAllKnownStatementTypes() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();
        StdlogClientDbQueryListener listener = new StdlogClientDbQueryListener(props);

        assertStatementType(listener, "insert into tags values (1)", "INSERT");
        assertStatementType(listener, "update tags set name='x'", "UPDATE");
        assertStatementType(listener, "delete from tags", "DELETE");
        assertStatementType(listener, "call sp_something()", "CALL");
        assertStatementType(listener, "merge into tags", "OTHER");
    }

    @Test
    void shouldMarkStatementTypeUnknownWhenNoQueryInfo() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        StdlogProperties props = props();

        new StdlogClientDbQueryListener(props).afterQuery(execInfo(1, null), List.of());

        Map<String, Object> payload = onlyPayload();
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertEquals("UNKNOWN", db.get("type"));
        assertNull(db.get("statement"));
    }

    private void assertStatementType(StdlogClientDbQueryListener listener, String sql, String expectedType) {
        listener.afterQuery(execInfo(1, null), queries(sql));
        Map<String, Object> payload = StdlogTestSupport.stdlogPayload(appender.list.get(appender.list.size() - 1));
        Map<?, ?> db = (Map<?, ?>) payload.get("db");
        assertEquals(expectedType, db.get("type"));
    }

    private static StdlogProperties props() {
        StdlogProperties props = new StdlogProperties();
        // Modo explicito: estos tests comprueban el payload de llamadas EXITOSAS, y desde
        // ADR-0013 el modo AUTO sin ninguna senal resuelve productivo, donde
        // logOnlyOnFailureInProd / logOnlySlowOrFailureInProd las suprimen. Declararlo hace
        // visible lo que antes dependia de un default que resulto ser el equivocado.
        props.setMode(StdlogProperties.Mode.NON_PROD);
        props.getJdbc().setEnabled(true);
        return props;
    }

    private static ExecutionInfo execInfo(long elapsedMs, Throwable error) {
        ExecutionInfo execInfo = new ExecutionInfo();
        execInfo.setElapsedTime(elapsedMs);
        execInfo.setThrowable(error);
        return execInfo;
    }

    private static List<QueryInfo> queries(String sql) {
        return List.of(new QueryInfo(sql));
    }

    private Map<String, Object> onlyPayload() {
        assertEquals(1, appender.list.size());
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }
}
