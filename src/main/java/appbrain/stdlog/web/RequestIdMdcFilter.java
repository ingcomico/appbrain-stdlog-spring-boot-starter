package appbrain.stdlog.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro que gestiona el {@code request_id} en el MDC para toda la vida del request.
 *
 * <p>Extrae el header {@code x-request-id} del request entrante. Si no está presente
 * o está en blanco, genera un UUID aleatorio. El valor queda disponible en el MDC
 * bajo la key {@code request_id} para todos los componentes aguas abajo
 * (loggers, interceptors, listeners JDBC, etc.).</p>
 *
 * <p>El mismo {@code request_id} se devuelve en el header {@code x-request-id}
 * de la respuesta para facilitar la correlación en el cliente.</p>
 *
 * <p>Este filtro se registra con orden {@code Integer.MIN_VALUE} para garantizar
 * que sea el primero en ejecutarse y que el MDC esté disponible para todos los
 * demás filtros e interceptors.</p>
 *
 * <p>Limpieza garantizada: el MDC se limpia en bloque {@code finally},
 * independientemente de si el request lanzó excepción.</p>
 */
public class RequestIdMdcFilter extends OncePerRequestFilter {

    /** Nombre del header HTTP usado para leer y devolver el request ID. */
    public static final String HEADER_REQUEST_ID = "x-request-id";

    /** Key del MDC donde se almacena el request ID durante el request. */
    public static final String MDC_REQUEST_ID = "request_id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String requestId = req.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            // Opcional: devolverlo al cliente para correlación
            res.setHeader(HEADER_REQUEST_ID, requestId);

            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}