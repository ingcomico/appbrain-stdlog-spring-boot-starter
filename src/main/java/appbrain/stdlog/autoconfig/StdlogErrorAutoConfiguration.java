package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.web.StdlogExceptionResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Auto-configuración del módulo de captura de excepciones MVC.
 *
 * <p>Registra {@code StdlogExceptionResolver} con {@code @Order(HIGHEST_PRECEDENCE)}
 * para capturar excepciones antes que otros resolvers. Solo activa cuando
 * {@code stdlog.error.enabled=true} (default).</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnClass(HandlerExceptionResolver.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "stdlog.error", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogErrorAutoConfiguration {

    @Bean
    public HandlerExceptionResolver stdlogExceptionResolver() {
        return new StdlogExceptionResolver();
    }
}
