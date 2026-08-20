package appbrain.stdlog.web;

import appbrain.stdlog.StdlogExcluded;
import appbrain.stdlog.core.StdlogEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Interceptor MVC que resuelve y propaga el nombre de la operación en curso.
 *
 * <p>En {@code preHandle} determina el nombre de la operación a partir del handler:</p>
 * <ul>
 *   <li>Si es un {@link HandlerMethod}: {@code "NombreController#nombreMetodo"} (ej. {@code "TagsController#searchTags"}).</li>
 *   <li>Fallback para handlers no-MVC: {@code "METHOD /uri"} (ej. {@code "GET /actuator/health"}).</li>
 * </ul>
 *
 * <p>El valor se almacena en dos lugares para cubrir distintos escenarios de propagación:</p>
 * <ol>
 *   <li><b>Request attribute</b> {@code stdlog.operation} — leído por {@code ControllerBodyAndOutLoggingFilter}
 *       para los eventos {@code CONTROLLER_HTTP}.</li>
 *   <li><b>MDC</b> {@code operation} — leído por {@code StdlogCustom}, {@code StdlogClientDbQueryListener}
 *       y {@code StdlogClientHttpInterceptor} para correlacionar eventos de negocio,
 *       queries JDBC y llamadas HTTP salientes con el endpoint que los originó.</li>
 * </ol>
 *
 * <p>También resuelve si el handler (clase o método) tiene {@code @StdlogExcluded} y, de
 * ser así, marca {@link StdlogEmitter#MDC_EXCLUDED} en el MDC. Esa key la consume
 * {@code StdlogEmitter} para suprimir eventos {@code TRACE}/{@code DEBUG}/{@code INFO}
 * de <em>cualquier</em> módulo (controller, JDBC, restclient, custom) durante el resto
 * del request — {@code WARN}/{@code ERROR} nunca se suprimen. La limpieza de esa key
 * queda a cargo de {@code ControllerBodyAndOutLoggingFilter} (no de este interceptor),
 * porque su bloque {@code finally} corre después y todavía necesita verla para decidir
 * si suprime el evento {@code OUT}.</p>
 *
 * <p>El interceptor también registra el tiempo de inicio ({@code stdlog.startNano})
 * si no ha sido inicializado previamente por algún otro componente.</p>
 *
 * <p>Limpieza: la key {@code operation} se elimina del MDC en {@code afterCompletion}.</p>
 */
public class StdlogMvcOperationInterceptor implements HandlerInterceptor {

    private static final String MDC_OPERATION = "operation";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String operation = resolveOperation(handler, req);

        String pattern = (String) req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = req.getMethod() + " " + (pattern != null ? pattern : req.getRequestURI());

        // Request attributes (para CONTROLLER_HTTP)
        req.setAttribute(StdlogAttrs.OPERATION, operation);
        req.setAttribute(StdlogAttrs.PATH_PATTERN, pattern);
        req.setAttribute(StdlogAttrs.ROUTE, route);

        // MDC (para CLIENT_DB, StdlogCustom, CLIENT_HTTP, etc.)
        if (operation != null) {
            MDC.put(MDC_OPERATION, operation);
        }
        if (isExcluded(handler)) {
            MDC.put(StdlogEmitter.MDC_EXCLUDED, "true");
        }

        // start time (si aún no está)
        if (req.getAttribute(StdlogAttrs.START_NANO) == null) {
            req.setAttribute(StdlogAttrs.START_NANO, System.nanoTime());
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        MDC.remove(MDC_OPERATION);
        // MDC_EXCLUDED se limpia en ControllerBodyAndOutLoggingFilter, no acá:
        // ese filtro todavía necesita leerla para el evento OUT que emite después.
    }

    private String resolveOperation(Object handler, HttpServletRequest req) {
        if (handler instanceof HandlerMethod hm) {
            return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        }
        return req.getMethod() + " " + req.getRequestURI();
    }

    /**
     * {@code true} si el método handler o su clase declarante tienen {@code @StdlogExcluded}
     * (soporta anotaciones compuestas/meta-anotadas via {@link AnnotatedElementUtils}).
     */
    private boolean isExcluded(Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return false;
        }
        return AnnotatedElementUtils.hasAnnotation(hm.getMethod(), StdlogExcluded.class)
                || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), StdlogExcluded.class);
    }
}
