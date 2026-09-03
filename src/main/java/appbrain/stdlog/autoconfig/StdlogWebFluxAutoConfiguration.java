package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.webflux.StdlogWebFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

/**
 * Auto-configuración de la instrumentación de entrada HTTP reactiva (WebFlux). Fase 1 de ADR-0008.
 *
 * <p>Sólo activa en aplicaciones reactivas ({@code @ConditionalOnWebApplication(REACTIVE)}) que
 * tengan `spring-webflux` en el classpath. No puede co-activar con las auto-configs servlet
 * ({@code StdlogAutoConfiguration}, {@code StdlogWebMvcAutoConfiguration}, {@code StdlogErrorAutoConfiguration}),
 * que son {@code SERVLET}-only. La vía servlet no se toca.</p>
 *
 * <p>Registra {@link StdlogWebFilter}, que emite {@code CONTROLLER_HTTP} y el evento extra de
 * error, y escribe la correlación del request en el Reactor Context.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnClass(WebFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "stdlog.controller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogWebFluxAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "stdlog.controller.webflux", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StdlogWebFilter stdlogWebFilter(StdlogProperties props) {
        return new StdlogWebFilter(props);
    }
}
