package appbrain.stdlog.core;

import appbrain.stdlog.config.StdlogProperties;

import java.util.Locale;

/**
 * Resuelve si la aplicación está ejecutándose en modo productivo o no productivo.
 *
 * <p>La resolución sigue esta prioridad:</p>
 * <ol>
 *   <li>Propiedad {@code stdlog.mode=PROD|NON_PROD} en {@code application.yml}.</li>
 *   <li>Variable de entorno {@code STDLOG_MODE=PROD|NON_PROD|NONPROD}.</li>
 *   <li>Default: {@code false} (NON_PROD) si ninguna de las anteriores está definida.</li>
 * </ol>
 *
 * <p>El resultado determina las políticas anti-ruido de cada módulo:
 * {@code logOnlyOnFailureInProd} en restclient y {@code logOnlySlowOrFailureInProd} en JDBC.</p>
 */
public final class StdlogModeResolver {

    private static final String ENV_STDLOG_MODE = "STDLOG_MODE";

    private StdlogModeResolver() {}

    /**
     * Determina si el entorno actual es productivo.
     *
     * @param props configuración del starter; si es {@code null} o {@code mode=AUTO},
     *              se infiere desde la variable de entorno {@code STDLOG_MODE}
     * @return {@code true} si el entorno es productivo
     */
    public static boolean isProd(StdlogProperties props) {
        if (props != null && props.getMode() != null) {
            switch (props.getMode()) {
                case PROD: return true;
                case NON_PROD: return false;
                case AUTO:
                default: return isProdAuto();
            }
        }
        return isProdAuto();
    }

    private static boolean isProdAuto() {
        String forced = System.getenv(ENV_STDLOG_MODE);
        if (forced != null && !forced.isBlank()) {
            String v = forced.trim().toUpperCase(Locale.ROOT);
            if ("PROD".equals(v)) return true;
            if ("NON_PROD".equals(v) || "NONPROD".equals(v)) return false;
        }
        return false;
    }
}
