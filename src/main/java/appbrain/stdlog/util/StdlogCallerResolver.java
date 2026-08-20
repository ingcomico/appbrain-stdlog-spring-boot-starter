package appbrain.stdlog.util;

/**
 * Utilidad para identificar el caller de la aplicación consumidora en el stack trace.
 *
 * <p>Se usa cuando {@code stdlog.restclient.captureSource=true} para enriquecer
 * los logs de llamadas HTTP salientes con la clase, método y línea que originó
 * la llamada. Tiene costo de CPU por llamada (stacktrace-walk), por lo que
 * está desactivado por defecto.</p>
 */
public final class StdlogCallerResolver {

    private StdlogCallerResolver() {}

    /**
     * Busca el primer frame del stack trace que pertenece a la aplicación consumidora.
     *
     * <p>Si se proporciona {@code basePackage}, solo se consideran frames cuyo
     * nombre de clase empieza por ese prefijo (excluyendo sub-paquetes {@code .commons.}).
     * Si no se proporciona, aplica una heurística que excluye frames de stdlog,
     * restclient, Spring y JDK.</p>
     *
     * @param basePackage paquete base del consumidor (ej. {@code "com.example.myapp"});
     *                    puede ser {@code null} o en blanco para usar la heurística de exclusión
     * @return el {@link StackTraceElement} del caller encontrado, o {@code null} si no se encontró
     */
    public static StackTraceElement findConsumerCaller(String basePackage) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();

        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn == null) continue;

            // Si tenemos basePackage: SOLO cosas del consumidor
            if (basePackage != null && !basePackage.isBlank()) {
                if (!cn.startsWith(basePackage + ".")) continue;
                if (cn.contains(".commons.")) continue;
                return e;
            }

            // Fallback si no configuraron basePackage
            if (cn.startsWith("appbrain.stdlog.")) continue;
            if (cn.startsWith("org.springframework.")) continue;
            if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) continue;

            return e;
        }
        return null;
    }
}