package appbrain.stdlog.webflux;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StdlogWebExceptionHandlerTest {

    @Test
    void shouldStashExceptionInExchangeAndRethrowIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        RuntimeException boom = new RuntimeException("boom");

        RuntimeException rethrown = assertThrows(RuntimeException.class,
                () -> new StdlogWebExceptionHandler().handle(exchange, boom).block());

        assertSame(boom, rethrown, "no debe consumir la excepción");
        assertSame(boom, exchange.getAttribute(StdlogWebFilter.ATTR_ERROR), "la deja en el atributo del exchange");
    }

    @Test
    void shouldNotOverwriteAnAlreadyStashedException() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        RuntimeException first = new RuntimeException("first");
        exchange.getAttributes().put(StdlogWebFilter.ATTR_ERROR, first);

        assertThrows(RuntimeException.class,
                () -> new StdlogWebExceptionHandler().handle(exchange, new RuntimeException("second")).block());

        assertSame(first, exchange.getAttribute(StdlogWebFilter.ATTR_ERROR));
    }
}
