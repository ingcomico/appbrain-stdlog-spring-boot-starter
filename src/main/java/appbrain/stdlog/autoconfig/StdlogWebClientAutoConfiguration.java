package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.restclient.StdlogWebClientExchangeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuración del logging de {@code WebClient} (cliente HTTP saliente reactivo).
 * Ver ADR-0006 y ADR-0008 (Fase 2).
 *
 * <p>Se activa siempre que {@code WebClient} esté en el classpath ({@code @ConditionalOnClass}),
 * en apps servlet, WebFlux o no-web. En apps WebFlux, la correlación (`request_id`) de las
 * llamadas salientes se toma del Reactor Context que puebla {@code StdlogWebFilter}.</p>
 *
 * <p>Registra {@link StdlogWebClientExchangeFilter} y lo añade a cualquier
 * {@link WebClient.Builder} del contexto mediante un {@link BeanPostProcessor} — así funciona
 * igual en Spring Boot 3 y 4 sin depender del paquete de {@code WebClientCustomizer}, que
 * cambió de módulo entre majors.</p>
 *
 * <p>Consumidores que construyen {@code WebClient} sin pasar por un {@code WebClient.Builder}
 * del contexto deben añadir el filtro a mano.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(prefix = "stdlog.restclient", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogWebClientAutoConfiguration {

    @Bean
    public StdlogWebClientExchangeFilter stdlogWebClientExchangeFilter(StdlogProperties props) {
        return new StdlogWebClientExchangeFilter(props);
    }

    @Bean
    public static BeanPostProcessor stdlogWebClientBuilderPostProcessor(
            ObjectProvider<StdlogWebClientExchangeFilter> filterProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof WebClient.Builder builder) {
                    StdlogWebClientExchangeFilter filter = filterProvider.getIfAvailable();
                    if (filter != null) {
                        builder.filters(filters -> {
                            if (!filters.contains(filter)) filters.add(filter);
                        });
                    }
                }
                return bean;
            }
        };
    }
}
