package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.restclient.StdlogClientHttpInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Auto-configuración del módulo de logging de llamadas HTTP salientes hechas con
 * {@link RestTemplate} o {@link RestClient}.
 *
 * <p>Registra {@code StdlogClientHttpInterceptor}, un {@link RestTemplateCustomizer}
 * y un {@link RestClientCustomizer} para que los clientes construidos a partir de los
 * builders autoconfigurados por Spring Boot emitan el mismo evento {@code CLIENT_HTTP}.</p>
 *
 * <p>Si el consumidor construye clientes manualmente (sin pasar por los builders de
 * Spring Boot), debe inyectar {@code StdlogClientHttpInterceptor} y registrarlo a mano.</p>
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
            addIfAbsent(restTemplate.getInterceptors(), interceptor);
            restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(restTemplate.getRequestFactory()));
        };
    }

    @Bean
    @ConditionalOnClass(RestClient.class)
    public RestClientCustomizer stdlogRestClientCustomizer(StdlogClientHttpInterceptor interceptor) {
        return restClientBuilder -> restClientBuilder.requestInterceptors(interceptors -> addIfAbsent(interceptors, interceptor));
    }

    private static void addIfAbsent(List<ClientHttpRequestInterceptor> interceptors,
            StdlogClientHttpInterceptor interceptor) {
        if (!interceptors.contains(interceptor)) {
            interceptors.add(interceptor);
        }
    }
}
