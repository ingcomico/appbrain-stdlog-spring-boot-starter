package appbrain.stdlog.core;

import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import reactor.util.context.ContextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resuelve la correlación de un request reactivo desde el {@code Context} de Reactor:
 * {@code request_id} (lo escribe {@code StdlogWebFilter}), {@code operation} (desde el
 * {@code ServerWebExchange} que también viaja en el Context), y el marcador de exclusión.
 *
 * <p>Lo usan {@code StdlogWebClientExchangeFilter} y {@code StdlogCustomReactive}. Fase 2/3
 * de ADR-0008. Sólo carga cuando Spring WebFlux está en el classpath.</p>
 */
public final class StdlogReactiveCorrelation {

    private StdlogReactiveCorrelation() {}

    public static String requestId(ContextView ctx) {
        return StdlogReactorContext.get(ctx, StdlogReactorContext.REQUEST_ID);
    }

    public static boolean excluded(ContextView ctx) {
        return StdlogReactorContext.isExcluded(ctx);
    }

    /**
     * {@code operation} ({@code Controller#method}) resuelto de forma perezosa desde el
     * {@code ServerWebExchange} del Context; sus atributos {@code HandlerMapping.*} ya están
     * poblados cuando el código de negocio hace la llamada. {@code null} fuera de WebFlux.
     */
    public static String operation(ContextView ctx) {
        String direct = StdlogReactorContext.get(ctx, StdlogReactorContext.OPERATION);
        if (direct != null && !direct.isBlank()) return direct;
        try {
            return ServerWebExchangeContextFilter.getExchange(ctx)
                    .map(ex -> ex.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE))
                    .filter(h -> h instanceof HandlerMethod)
                    .map(h -> {
                        HandlerMethod hm = (HandlerMethod) h;
                        return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
                    })
                    .orElse(null);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Construye un mapa listo para {@code MDC.setContextMap(...)} con las claves de correlación
     * presentes en el Context. Devuelve {@code null} si no hay ninguna.
     */
    public static Map<String, String> toMdc(ContextView ctx) {
        String requestId = requestId(ctx);
        String operation = operation(ctx);
        boolean excluded = excluded(ctx);
        if (requestId == null && operation == null && !excluded) return null;

        Map<String, String> mdc = new LinkedHashMap<>();
        if (requestId != null) mdc.put("request_id", requestId);
        if (operation != null) mdc.put("operation", operation);
        if (excluded) mdc.put(StdlogEmitter.MDC_EXCLUDED, "true");
        return mdc;
    }
}
