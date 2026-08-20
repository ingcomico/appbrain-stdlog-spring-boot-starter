package appbrain.stdlog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excluye un controller (a nivel de clase) o un método handler específico de los
 * eventos {@code CONTROLLER_HTTP} (IN/OUT) y del evento extra de excepción (WARN/ERROR).
 *
 * <p>A diferencia de {@code stdlog.controller.excluded-path-patterns} (que filtra por
 * path antes de que Spring MVC resuelva el handler), esta anotación se evalúa una vez
 * resuelto el {@code HandlerMethod}, por lo que aplica sobre la clase/método real aunque
 * varias rutas distintas apunten al mismo controller.</p>
 *
 * <p>No afecta otros eventos: {@code StdlogCustom}, {@code CLIENT_HTTP} y {@code CLIENT_DB}
 * emitidos dentro de un handler excluido se siguen logueando normalmente.</p>
 *
 * <p>Ejemplo:</p>
 * <pre>{@code
 * @StdlogExcluded
 * @RestController
 * public class InternalDiagnosticsController { ... }
 *
 * @RestController
 * public class OrdersController {
 *     @StdlogExcluded
 *     @GetMapping("/orders/ping")
 *     public String ping() { return "pong"; }
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface StdlogExcluded {
}
