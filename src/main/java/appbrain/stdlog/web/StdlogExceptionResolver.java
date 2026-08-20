package appbrain.stdlog.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * {@link HandlerExceptionResolver} de alta prioridad que captura excepciones MVC
 * sin consumirlas, dejándolas disponibles para el filtro de logging.
 *
 * <p>Cuando Spring MVC produce una excepción durante el procesamiento de un request,
 * este resolver almacena la excepción en el atributo {@link StdlogAttrs#ERROR} del request.
 * Al retornar {@code null}, <strong>no consume la excepción</strong>: los resolvers
 * subsiguientes (ej. {@code ResponseEntityExceptionHandler}) continúan procesando
 * normalmente.</p>
 *
 * <p>{@code ControllerBodyAndOutLoggingFilter} lee ese atributo en su bloque {@code finally}
 * para emitir un evento adicional ({@code event=WARN} para 4xx o {@code event=ERROR}
 * para 5xx) con el stack trace completo y el {@code app_trace} filtrado.</p>
 *
 * <p>Se registra con {@code @Order(Ordered.HIGHEST_PRECEDENCE)} para garantizar que
 * captura la excepción antes que cualquier otro resolver.</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StdlogExceptionResolver implements HandlerExceptionResolver {

    /**
     * Almacena la excepción en el atributo {@link StdlogAttrs#ERROR} del request
     * y retorna {@code null} para no consumirla.
     *
     * @return siempre {@code null}
     */
    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {

        request.setAttribute(StdlogAttrs.ERROR, ex);
        return null; // no consumir
    }
}