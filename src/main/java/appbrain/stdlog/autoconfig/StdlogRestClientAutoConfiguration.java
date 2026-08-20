package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.restclient.StdlogClientHttpInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuración del módulo de logging de llamadas HTTP salientes hechas con
 * {@link RestTemplate}.
 *
 * <p>Registra {@code StdlogClientHttpInterceptor} y un {@link RestTemplateCustomizer} que
 * lo agrega automáticamente a cualquier {@code RestTemplate} construido a partir del
 * {@code RestTemplateBuilder} autoconfigurado por Spring Boot, además de envolver el
 * request factory con buffering para que el body de la respuesta pueda leerse tanto en
 * el log como en los {@code HttpMessageConverter} de la aplicación.</p>
 *
 * <p>Si el consumidor construye su {@code RestTemplate} manualmente (sin pasar por
 * {@code RestTemplateBuilder}), debe inyectar {@code StdlogClientHttpInterceptor} y
 * registrarlo (más el buffering request factory) a mano.</p>
 *
 * <p>Solo activa cuando {@code stdlog.restclient.enabled=true} (default).</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnProperty(prefix = "stdlog.restclient", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogRestClientAutoConfiguration {

    @Bean
    public StdlogClientHttpInterceptor stdlogClientHttpInterceptor(StdlogProperties props) {
        return new StdlogClientHttpInterceptor(props);
    }

    @Bean
    @ConditionalOnClass(RestTemplate.class)
    public RestTemplateCustomizer stdlogRestTemplateCustomizer(StdlogClientHttpInterceptor interceptor) {
        return restTemplate -> {
            restTemplate.getInterceptors().add(interceptor);
            restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(restTemplate.getRequestFactory()));
        };
    }
}
