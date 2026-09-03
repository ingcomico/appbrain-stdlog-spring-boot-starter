package appbrain.stdlog.r2dbc;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.core.StdlogModeResolver;
import io.r2dbc.proxy.core.Binding;
import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.BoundValue;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Listener de {@code r2dbc-proxy} que emite eventos {@code CLIENT_DB direction=OUT} por cada
 * query R2DBC ejecutada, con el mismo formato que {@code StdlogClientDbQueryListener} produce
 * para JDBC. Ver ADR-0007.
 *
 * <p><b>Correlación:</b> {@code afterQuery} corre en el hilo del event-loop del driver, donde el
 * MDC está vacío. {@code beforeQuery} corre en el hilo que suscribe la query (el de request
 * cuando la app hace {@code .block()} desde un controller servlet): ahí se copia el MDC al
 * {@code ValueStore} de la query y en {@code afterQuery} se restaura alrededor de la emisión.
 * Si el MDC está vacío (app WebFlux sin context-propagation) los campos de correlación se
 * omiten, igual que hace el listener JDBC cuando no hay MDC.</p>
 *
 * <p><b>No modifica</b> {@code StdlogClientDbQueryListener} (JDBC).</p>
 */
public class StdlogR2dbcQueryListener implements ProxyExecutionListener {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");
    private static final String MDC_SNAPSHOT_KEY = "appbrain.stdlog.mdc";

    private final StdlogProperties props;

    public StdlogR2dbcQueryListener(StdlogProperties props) {
        this.props = props;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void beforeQuery(QueryExecutionInfo info) {
        // Corre en el hilo que suscribe: capturamos el MDC (request_id, operation, trace ids, exclusión)
        // y lo guardamos en el store de la query para leerlo en afterQuery (otro hilo).
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        if (mdc != null) {
            info.getValueStore().put(MDC_SNAPSHOT_KEY, mdc);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterQuery(QueryExecutionInfo info) {
        StdlogProperties.Jdbc cfg = activeConfig();
        if (cfg == null) return;

        Map<String, String> capturedMdc = (Map<String, String>) info.getValueStore().get(MDC_SNAPSHOT_KEY);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (capturedMdc != null) MDC.setContextMap(capturedMdc);
            emit(info, cfg);
        } catch (RuntimeException loggingFailure) {
            // nunca romper la query por un fallo de logging
        } finally {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        }
    }

    private void emit(QueryExecutionInfo info, StdlogProperties.Jdbc cfg) {
        long elapsedMs = info.getExecuteDuration() != null ? info.getExecuteDuration().toMillis() : 0L;
        Throwable error = info.getThrowable();

        long slowThreshold = cfg.getSlowQueryThresholdMs();
        boolean slow = slowThreshold > 0 && elapsedMs >= slowThreshold;

        if (StdlogModeResolver.isProd(props)
                && cfg.isLogOnlySlowOrFailureInProd()
                && error == null && !slow) {
            return;
        }

        String sql = truncate(joinSql(info.getQueries()), cfg.getMaxSqlChars());
        String stmtType = inferStatementType(sql);

        Map<String, Object> stdlog = new LinkedHashMap<>();
        stdlog.put("event", "CLIENT_DB");
        stdlog.put("direction", "OUT");
        stdlog.put("elapsedMs", elapsedMs);
        stdlog.put("outcome", (error == null) ? "SUCCESS" : "FAILURE");
        stdlog.put("slow", slow);

        String requestId = MDC.get("request_id");
        if (requestId != null && !requestId.isBlank()) stdlog.put("request_id", requestId);
        String operation = MDC.get("operation");
        if (operation != null && !operation.isBlank()) stdlog.put("operation", operation);

        Map<String, Object> peer = new LinkedHashMap<>();
        String connId = info.getConnectionInfo() != null ? info.getConnectionInfo().getConnectionId() : null;
        peer.put("pool", (connId != null && !connId.isBlank()) ? connId : cfg.getPoolName());
        stdlog.put("peer", peer);

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("statement", sql);
        db.put("type", stmtType);
        if (cfg.isLogParams()) {
            Object params = extractParams(info.getQueries(), cfg.getMaxParamChars());
            if (params != null) db.put("params", params);
        }
        stdlog.put("db", db);

        if (error != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", error.getClass().getName());
            err.put("message", error.getMessage());
            stdlog.put("error", err);
            StdlogEmitter.emit(STDLOG, cfg.getLevelFailure(), stdlog, error);
            return;
        }

        StdlogLevel level = slow ? StdlogLevel.WARN : cfg.getLevelSuccess();
        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private StdlogProperties.Jdbc activeConfig() {
        if (props == null) return null;
        StdlogProperties.Jdbc cfg = props.getJdbc();
        if (cfg == null || !cfg.isEnabled()) return null;
        if (cfg.getR2dbc() == null || !cfg.getR2dbc().isEnabled()) return null;
        return cfg;
    }

    // ---- helpers (espejo de StdlogClientDbQueryListener; R2DBC no comparte tipos con JDBC) ----

    private static String joinSql(List<QueryInfo> queries) {
        if (queries == null || queries.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (QueryInfo qi : queries) {
            if (qi == null) continue;
            String q = qi.getQuery();
            if (q == null || q.isBlank()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(q);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Object extractParams(List<QueryInfo> queries, int maxParamChars) {
        if (queries == null || queries.isEmpty()) return null;

        Map<Object, Object> merged = new LinkedHashMap<>();
        boolean any = false;
        for (QueryInfo qi : queries) {
            if (qi == null) continue;
            for (Bindings bindings : qi.getBindingsList()) {
                for (Binding b : bindings.getIndexBindings()) {
                    merged.put(b.getKey(), renderBound(b.getBoundValue(), maxParamChars));
                    any = true;
                }
                for (Binding b : bindings.getNamedBindings()) {
                    merged.put(b.getKey(), renderBound(b.getBoundValue(), maxParamChars));
                    any = true;
                }
            }
        }
        return any ? merged : null;
    }

    private static Object renderBound(BoundValue bound, int maxParamChars) {
        if (bound == null || bound.isNull()) return null;
        Object value = bound.getValue();
        if (value instanceof CharSequence cs) {
            String s = cs.toString();
            return (maxParamChars > 0 && s.length() > maxParamChars)
                    ? s.substring(0, maxParamChars) + "...(truncated)"
                    : s;
        }
        return value;
    }

    private static String inferStatementType(String sql) {
        if (sql == null) return "UNKNOWN";
        String s = sql.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("SELECT")) return "SELECT";
        if (s.startsWith("INSERT")) return "INSERT";
        if (s.startsWith("UPDATE")) return "UPDATE";
        if (s.startsWith("DELETE")) return "DELETE";
        if (s.startsWith("CALL")) return "CALL";
        return "OTHER";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return s;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
