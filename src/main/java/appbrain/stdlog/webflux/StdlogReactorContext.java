package appbrain.stdlog.webflux;

import reactor.util.context.ContextView;

/**
 * Claves de correlación que {@link StdlogWebFilter} escribe en el {@code Context} de Reactor
 * y que los puntos de emisión reactivos (filtro de `WebClient`, listener de R2DBC) leen como
 * fuente primaria cuando el MDC está vacío. Ver ADR-0008.
 *
 * <p>Las claves usan el mismo nombre que las keys de MDC de la vía servlet
 * (`request_id`, `operation`) para que, si el consumidor activa Micrometer
 * context-propagation, el puente MDC↔Context funcione sin traducción.</p>
 */
public final class StdlogReactorContext {

    public static final String REQUEST_ID = "request_id";
    public static final String OPERATION = "operation";
    public static final String ROUTE = "stdlog.route";
    /** Marca de exclusión: si está presente, se suprimen eventos TRACE/DEBUG/INFO del request. */
    public static final String EXCLUDED = "stdlog.excluded";

    private StdlogReactorContext() {}

    /** Devuelve el valor de la clave en el {@link ContextView}, o {@code null}. */
    public static String get(ContextView ctx, String key) {
        if (ctx == null || !ctx.hasKey(key)) return null;
        Object v = ctx.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static boolean isExcluded(ContextView ctx) {
        return ctx != null && ctx.hasKey(EXCLUDED);
    }
}
