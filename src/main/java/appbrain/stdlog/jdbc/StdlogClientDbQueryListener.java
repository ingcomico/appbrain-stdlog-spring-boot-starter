package appbrain.stdlog.jdbc;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.core.StdlogFailsafe;
import appbrain.stdlog.core.StdlogModeResolver;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.*;

/**
 * Listener de datasource-proxy que emite eventos {@code CLIENT_DB direction=OUT}
 * por cada query JDBC ejecutada.
 *
 * <p>Se registra en {@code StdlogJdbcAutoConfiguration} y recibe notificaciones
 * de {@code afterQuery} para todas las queries del DataSource proxeado.</p>
 *
 * <p>Características principales:</p>
 * <ul>
 *   <li>Detección de queries lentas según {@code slowQueryThresholdMs}.</li>
 *   <li>Filtrado en PROD: con {@code logOnlySlowOrFailureInProd=true}, solo loguea
 *       queries lentas o fallidas, reduciendo el volumen en producción.</li>
 *   <li>SQL truncado a {@code maxSqlChars} para evitar logs excesivamente grandes.</li>
 *   <li>Parámetros opcionales bajo {@code db.params} (si {@code logParams=true}).</li>
 *   <li>Información de respuesta best-effort bajo {@code db.response}
 *       (si {@code logResponseInfo=true}); puede ser {@code null} según versión del driver.</li>
 *   <li>Contexto de correlación ({@code request_id}, {@code operation}) leído desde MDC.</li>
 * </ul>
 */
public class StdlogClientDbQueryListener implements QueryExecutionListener {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");

    private final StdlogProperties props;

    public StdlogClientDbQueryListener(StdlogProperties props) {
        this.props = props;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // no-op
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        if (props == null || props.getJdbc() == null || !props.getJdbc().isEnabled()) return;
        // Bloque guardado de ADR-0011: una excepcion construyendo el payload no puede romper
        // la query del consumidor, que en este punto ya se ejecuto correctamente.
        StdlogFailsafe.run(() -> emitQueryEvent(execInfo, queryInfoList));
    }

    private void emitQueryEvent(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {

        long elapsedMs = execInfo.getElapsedTime();
        Throwable error = execInfo.getThrowable();

        long slowThreshold = props.getJdbc().getSlowQueryThresholdMs();
        boolean slow = slowThreshold > 0 && elapsedMs >= slowThreshold;

        boolean prod = StdlogModeResolver.isProd(props);
        if (prod && props.getJdbc().isLogOnlySlowOrFailureInProd() && error == null && !slow) return;

        String sql = truncate(joinSql(queryInfoList), props.getJdbc().getMaxSqlChars());
        String stmtType = inferStatementType(sql); // SELECT/INSERT/UPDATE/DELETE/...

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
        String dsName = execInfo.getDataSourceName();
        peer.put("pool", (dsName != null && !dsName.isBlank()) ? dsName : props.getJdbc().getPoolName());
        stdlog.put("peer", peer);

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("statement", sql);
        db.put("type", stmtType);

        if (props.getJdbc().isLogParams()) {
            Object params = extractParamsExplicit(queryInfoList, props.getJdbc().getMaxParamChars());
            if (params != null) db.put("params", params);
        }

        if (props.getJdbc().isLogResponseInfo()) {
            Map<String, Object> response = buildResponseInfoBestEffort(execInfo, stmtType);
            if (!response.isEmpty()) db.put("response", response);
        }

        stdlog.put("db", db);

        if (error != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", error.getClass().getName());
            err.put("message", error.getMessage());
            stdlog.put("error", err);

            StdlogEmitter.emit(STDLOG, props.getJdbc().getLevelFailure(), stdlog, error);
            return;
        }

        StdlogLevel level = slow ? StdlogLevel.WARN : props.getJdbc().getLevelSuccess();
        StdlogEmitter.emit(STDLOG, level, stdlog);
    }

    private static Map<String, Object> buildResponseInfoBestEffort(ExecutionInfo execInfo, String stmtType) {
        Map<String, Object> response = new LinkedHashMap<>();

        boolean isSelect = "SELECT".equals(stmtType);

        if (isSelect) {
            response.put("type", "RESULT_SET");
            Integer rowsReturned = extractRowsReturnedBestEffort(execInfo);
            if (rowsReturned != null) response.put("rowsReturned", rowsReturned);
            return response;
        }

        // DML
        response.put("type", "UPDATE_COUNT");
        Integer rowsAffected = extractUpdateCountBestEffort(execInfo);
        if (rowsAffected != null) response.put("rowsAffected", rowsAffected);

        return response;
    }

    /**
     * Best-effort update count. Depende de la implementación/versión.
     * Si no se puede, devuelve null (no inventar).
     */
    private static Integer extractUpdateCountBestEffort(ExecutionInfo execInfo) {
        Object r;

        // intentos comunes
        r = invokeIfExists(execInfo, "getUpdateCount");
        if (r instanceof Number n) return n.intValue();

        r = invokeIfExists(execInfo, "getResult");
        if (r instanceof Number n) return n.intValue();

        r = invokeIfExists(execInfo, "getResultCount");
        if (r instanceof Number n) return n.intValue();

        return null;
    }

    /**
     * rowsReturned no suele estar disponible en datasource-proxy 1.9.
     * Intentamos por reflection nombres posibles; si no, null.
     */
    private static Integer extractRowsReturnedBestEffort(ExecutionInfo execInfo) {
        Object r;

        r = invokeIfExists(execInfo, "getRows");
        if (r instanceof Number n) return n.intValue();

        r = invokeIfExists(execInfo, "getRowCount");
        if (r instanceof Number n) return n.intValue();

        r = invokeIfExists(execInfo, "getResultCount");
        if (r instanceof Number n) return n.intValue();

        return null;
    }

    private static Object invokeIfExists(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ---------------- Params (explícitos) ----------------

    private static Object extractParamsExplicit(List<QueryInfo> queryInfoList, int maxParamChars) {
        if (queryInfoList == null || queryInfoList.isEmpty()) return null;

        List<Map<String, Object>> out = new ArrayList<>();

        for (QueryInfo qi : queryInfoList) {
            if (qi == null) continue;

            List<?> parametersList;
            try {
                parametersList = qi.getParametersList();
            } catch (Throwable ignore) {
                continue;
            }
            if (parametersList == null || parametersList.isEmpty()) continue;

            List<Object> sets = new ArrayList<>();

            for (Object setObj : parametersList) {
                if (!(setObj instanceof List<?> ops)) continue;

                List<Map<String, Object>> oneSet = new ArrayList<>();
                for (Object opObj : ops) {
                    Map<String, Object> op = toExplicitOperation(opObj, maxParamChars);
                    if (op != null && !op.isEmpty()) oneSet.add(op);
                }
                sets.add(oneSet);
            }

            if (!sets.isEmpty()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("params", sets);
                out.add(one);
            }
        }

        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> toExplicitOperation(Object opObj, int maxParamChars) {
        if (opObj == null) return Map.of();

        String methodName = null;
        Object[] args = null;

        if (opObj instanceof ParameterSetOperation pso) {
            methodName = safeMethodName(pso);
            args = safeArgs(pso);
        }

        if (methodName == null) {
            methodName = toMethodName(invokeIfExists(opObj, "getMethodName"));
            if (methodName == null) methodName = toMethodName(invokeIfExists(opObj, "getMethod"));
        }

        if (args == null) {
            Object r = invokeIfExists(opObj, "getArgs");
            if (r instanceof Object[] a) args = a;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (methodName != null) out.put("m", methodName);

        Integer index = null;
        Object value = null;
        Integer sqlType = null;

        if (args != null && args.length >= 1 && args[0] instanceof Number n) {
            index = n.intValue();
        }

        boolean isSetNull = methodName != null && methodName.toLowerCase(Locale.ROOT).contains("setnull");

        if (isSetNull) {
            if (args != null && args.length >= 2 && args[1] instanceof Number n) sqlType = n.intValue();
            value = null;
        } else {
            if (args != null && args.length >= 2) value = args[1];
            if (args != null && args.length >= 3 && args[2] instanceof Number n) sqlType = n.intValue();
        }

        if (sqlType == null || sqlType == 0) {
            sqlType = inferSqlTypeFromValue(value);
        }

        if (index != null) out.put("i", index);
        out.put("t", sqlTypeNameOrObject(sqlType));
        out.put("v", sanitizeValue(value, maxParamChars));

        return out;
    }

    private static int inferSqlTypeFromValue(Object v) {
        if (v == null) return 0;
        if (v instanceof String) return Types.VARCHAR;
        if (v instanceof Integer) return Types.INTEGER;
        if (v instanceof Long) return Types.BIGINT;
        if (v instanceof Short) return Types.SMALLINT;
        if (v instanceof Boolean) return Types.BOOLEAN;
        if (v instanceof Float) return Types.FLOAT;
        if (v instanceof Double) return Types.DOUBLE;
        if (v instanceof BigDecimal) return Types.DECIMAL;
        if (v instanceof java.sql.Date) return Types.DATE;
        if (v instanceof java.util.Date || v instanceof Instant || v instanceof Timestamp) return Types.TIMESTAMP;
        if (v instanceof byte[]) return Types.BINARY;
        return 0;
    }

    private static String safeMethodName(ParameterSetOperation pso) {
        try {
            Method m = pso.getClass().getMethod("getMethodName");
            return toMethodName(m.invoke(pso));
        } catch (Throwable ignore) {
            try {
                Method m = pso.getClass().getMethod("getMethod");
                return toMethodName(m.invoke(pso));
            } catch (Throwable ignore2) {
                return null;
            }
        }
    }

    private static Object[] safeArgs(ParameterSetOperation pso) {
        try {
            return pso.getArgs();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String toMethodName(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        if (obj instanceof Method m) return m.getName();
        return String.valueOf(obj);
    }

    private static String sqlTypeNameOrObject(Integer t) {
        if (t == null || t == 0) return "OBJECT";
        return switch (t) {
            case Types.VARCHAR -> "VARCHAR";
            case Types.CHAR -> "CHAR";
            case Types.LONGVARCHAR -> "LONGVARCHAR";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.BIT -> "BIT";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.DATE -> "DATE";
            case Types.DECIMAL -> "DECIMAL";
            case Types.NUMERIC -> "NUMERIC";
            case Types.DOUBLE -> "DOUBLE";
            case Types.FLOAT -> "FLOAT";
            case Types.REAL -> "REAL";
            case Types.SMALLINT -> "SMALLINT";
            case Types.TINYINT -> "TINYINT";
            case Types.BINARY -> "BINARY";
            case Types.VARBINARY -> "VARBINARY";
            case Types.LONGVARBINARY -> "LONGVARBINARY";
            default -> String.valueOf(t);
        };
    }

    private static Object sanitizeValue(Object v, int maxParamChars) {
        if (v == null) return null;
        if (v instanceof String s) return truncate(s, maxParamChars);
        if (v instanceof Number || v instanceof Boolean) return v;
        return truncate(String.valueOf(v), maxParamChars);
    }

    // ---------------- SQL helpers ----------------

    private static String joinSql(List<QueryInfo> queryInfoList) {
        if (queryInfoList == null || queryInfoList.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (QueryInfo qi : queryInfoList) {
            if (qi == null) continue;
            String q = qi.getQuery();
            if (q == null || q.isBlank()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(q);
        }
        return sb.toString();
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