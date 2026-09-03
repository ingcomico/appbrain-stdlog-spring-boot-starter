package appbrain.stdlog.core;

import reactor.util.context.ContextView;

/**
 * Claves de correlación que {@code StdlogWebFilter} escribe en el {@code Context} de Reactor
 * y que los puntos de emisión reactivos ({@code StdlogWebClientExchangeFilter},
 * {@code StdlogR2dbcQueryListener}) leen como fuente primaria cuando el MDC está vacío.
 * Ver ADR-0008 (Fases 1 y 2).
 *
 * <p>{@link #REQUEST_ID} usa el mismo nombre que la key de MDC de la vía servlet para que,
 * si el consumidor activa Micrometer context-propagation, el puente Context↔MDC funcione
 * sin traducción (ver {@code StdlogWebFluxAutoConfiguration}, que registra el accessor).</p>
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
