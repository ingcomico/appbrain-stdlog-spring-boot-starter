package appbrain.stdlog.webflux;

import appbrain.stdlog.StdlogCustom;
import appbrain.stdlog.core.StdlogReactiveCorrelation;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Map;

/**
 * Variante reactiva de {@link StdlogCustom} para código WebFlux, donde no hay MDC.
 *
 * <p>Cada método devuelve un {@code Mono<Void>} que, al suscribirse, lee la correlación
 * ({@code request_id}, {@code operation}, exclusión) del {@code Context} de Reactor —lo puebla
 * {@code StdlogWebFilter}— la restaura en el MDC y delega en {@code StdlogCustom} (que no se
 * modifica). Componer con la cadena reactiva del consumidor:</p>
 *
 * <pre>{@code
 * return pagar(orden)
 *     .flatMap(res -> StdlogCustomReactive.success("PAGO_OK", Map.of("id", res.id())).thenReturn(res));
 * }</pre>
 *
 * <p>Ver ADR-0008 Fase 3.</p>
 */
public final class StdlogCustomReactive {

    private StdlogCustomReactive() {}

    public static Mono<Void> info(String event, Map<String, Object> payload) {
        return run(() -> StdlogCustom.info(event, payload));
    }

    public static Mono<Void> warn(String event, Map<String, Object> payload) {
        return run(() -> StdlogCustom.warn(event, payload));
    }

    public static Mono<Void> debug(String event, Map<String, Object> payload) {
        return run(() -> StdlogCustom.debug(event, payload));
    }

    public static Mono<Void> success(String event, Map<String, Object> payload) {
        return run(() -> StdlogCustom.success(event, payload));
    }

    public static Mono<Void> failure(String event, Map<String, Object> payload, Throwable t) {
        return run(() -> StdlogCustom.failure(event, payload, t));
    }

    public static Mono<Void> error(String event, String outcome, Map<String, Object> payload, Throwable t) {
        return run(() -> StdlogCustom.error(event, outcome, payload, t));
    }

    private static Mono<Void> run(Runnable emit) {
        return Mono.deferContextual(ctx -> {
            withContext(ctx, emit);
            return Mono.empty();
        });
    }

    private static void withContext(ContextView ctx, Runnable emit) {
        Map<String, String> fromContext = StdlogReactiveCorrelation.toMdc(ctx);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (fromContext != null) {
                // no pisamos lo que ya haya en el MDC (caso servlet); completamos lo que falte
                fromContext.forEach((k, v) -> {
                    if (MDC.get(k) == null) MDC.put(k, v);
                });
            }
            emit.run();
        } finally {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        }
    }
}
