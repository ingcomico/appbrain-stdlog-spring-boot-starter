package appbrain.stdlog.error;

import java.util.Arrays;
import java.util.List;

/**
 * Utilidad para extraer un stack trace filtrado de la aplicación.
 *
 * <p>En lugar de incluir todo el stack trace (que en Spring MVC puede tener
 * decenas de frames de framework), extrae solo los frames pertenecientes
 * al paquete de la aplicación. Esto produce un {@code app_trace} compacto
 * y orientado a la causa raíz en el código propio.</p>
 */
public final class AppTraceUtil {
    private AppTraceUtil() {}

    /**
     * Extrae los frames del stack trace que pertenecen al paquete de la aplicación.
     *
     * @param t             excepción de la que se extrae el trace; si es {@code null} devuelve lista vacía
     * @param packagePrefix prefijo del paquete de la aplicación (ej. {@code "com.example."});
     *                      si es {@code null} o vacío devuelve lista vacía (no hay forma de filtrar)
     * @param maxFrames     límite máximo de frames a incluir
     * @return lista de strings con formato {@code "NombreClase#metodo:linea"};
     *         vacía si no hay frames coincidentes
     */
    public static List<String> appTrace(Throwable t, String packagePrefix, int maxFrames) {
        if (t == null || t.getStackTrace() == null) return List.of();
        if (packagePrefix == null || packagePrefix.isBlank()) return List.of();
        return Arrays.stream(t.getStackTrace())
                .filter(el -> el.getClassName().startsWith(packagePrefix) && el.getLineNumber() > 0)
                .limit(maxFrames)
                .map(el -> el.getClassName() + "#" + el.getMethodName() + ":" + el.getLineNumber())
                .toList();
    }
}