package appbrain.stdlog.webflux;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * {@link WebExceptionHandler} de máxima precedencia que <b>no consume</b> la excepción: la
 * guarda en un atributo del exchange y la vuelve a propagar para que la maneje el handler de
 * Spring Boot. {@link StdlogWebFilter} la lee en su {@code doFinally} para que el evento
 * extra de error incluya el tipo, mensaje y stack trace reales.
 *
 * <p>Análogo a {@code StdlogExceptionResolver} de la vía servlet. Fase 3 de ADR-0008.</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StdlogWebExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        exchange.getAttributes().putIfAbsent(StdlogWebFilter.ATTR_ERROR, ex);
        return Mono.error(ex); // no consumir: la maneja el siguiente WebExceptionHandler
    }
}
