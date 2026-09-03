package appbrain.stdlog.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.web.ControllerBodyAndOutLoggingFilter;
import appbrain.stdlog.web.RequestIdMdcFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuración principal del starter: registra los filtros de logging HTTP.
 *
 * <p>Solo activa con aplicaciones servlet ({@code @ConditionalOnWebApplication(SERVLET)}).
 * Registra dos filtros con órdenes distintos:</p>
 * <ul>
 *   <li>{@code RequestIdMdcFilter} — orden {@code Integer.MIN_VALUE} (primero de todos).</li>
 *   <li>{@code ControllerBodyAndOutLoggingFilter} — orden {@code Integer.MIN_VALUE + 100}, justo
 *       por dentro del anterior y por tanto <b>por fuera de la cadena de Spring Security</b>
 *       (que se registra en {@code -100}). Ver {@code ADR-0012}: es lo que hace visibles los
 *       {@code 401}/{@code 403} y cualquier request cortado por un filtro externo, e iguala la
 *       posición que ya tenía {@code StdlogWebFilter} en la vía reactiva.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StdlogAutoConfiguration {

    @Bean
    public FilterRegistrationBean<RequestIdMdcFilter> requestIdMdcFilter() {
        FilterRegistrationBean<RequestIdMdcFilter> frb = new FilterRegistrationBean<>();
        frb.setFilter(new RequestIdMdcFilter());
        frb.setOrder(Integer.MIN_VALUE);
        return frb;
    }

    @Bean
    public FilterRegistrationBean<ControllerBodyAndOutLoggingFilter> stdlogControllerFilter(
            StdlogProperties props,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<ControllerBodyAndOutLoggingFilter> frb = new FilterRegistrationBean<>();
        frb.setFilter(new ControllerBodyAndOutLoggingFilter(props, objectMapper));
        frb.setOrder(Integer.MIN_VALUE + 100);
        return frb;
    }
}