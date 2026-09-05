package appbrain.stdlog.autoconfig;

import appbrain.stdlog.core.backend.StdlogBackend;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Detecta el backend de logging al arrancar y lo anuncia (`ADR-0014`).
 *
 * <p>La detección es perezosa y se haría igual en el primer evento; esta autoconfiguración sólo
 * la adelanta para que el anuncio —y sobre todo el aviso cuando el backend no está soportado—
 * aparezca en el arranque y no enterrado en el primer request.</p>
 */
@AutoConfiguration
public class StdlogBackendAutoConfiguration {

    @Bean
    public InitializingBean stdlogBackendAnnouncer() {
        return StdlogBackend::detectAndAnnounce;
    }
}
