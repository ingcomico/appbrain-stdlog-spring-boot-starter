package appbrain.stdlog.autoconfig;

import appbrain.stdlog.web.ControllerBodyAndOutLoggingFilter;
import appbrain.stdlog.web.RequestIdMdcFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

class StdlogAutoConfigurationTest {

    @Test
    void shouldRegisterFiltersInServletWebApplications() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThatContextHasFilterBean(context, "requestIdMdcFilter", RequestIdMdcFilter.class);
                    assertThatContextHasFilterBean(context, "stdlogControllerFilter", ControllerBodyAndOutLoggingFilter.class);

                    FilterRegistrationBean<?> requestId = (FilterRegistrationBean<?>) context.getBean("requestIdMdcFilter");
                    FilterRegistrationBean<?> controller = (FilterRegistrationBean<?>) context.getBean("stdlogControllerFilter");
                    org.assertj.core.api.Assertions.assertThat(requestId.getOrder()).isEqualTo(Integer.MIN_VALUE);
                    // ADR-0012: el filtro pasó de LOWEST_PRECEDENCE (el más interno) a estar
                    // justo por dentro de RequestIdMdcFilter, y por tanto por fuera de la cadena
                    // de Spring Security (que se registra en -100). Es lo que hace visibles los
                    // 401/403 e iguala la posición de StdlogWebFilter en la vía reactiva.
                    org.assertj.core.api.Assertions.assertThat(controller.getOrder()).isEqualTo(Integer.MIN_VALUE + 100);
                    org.assertj.core.api.Assertions.assertThat(controller.getOrder())
                            .as("debe quedar por fuera de la cadena de Spring Security (-100)")
                            .isLessThan(-100);
                });
    }

    @Test
    void shouldNotRegisterFiltersInNonWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean("requestIdMdcFilter");
                    org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean("stdlogControllerFilter");
                });
    }

    @SuppressWarnings("unchecked")
    private static void assertThatContextHasFilterBean(
            org.springframework.context.ApplicationContext context, String beanName, Class<?> filterType) {
        FilterRegistrationBean<?> frb = (FilterRegistrationBean<?>) context.getBean(beanName);
        org.assertj.core.api.Assertions.assertThat(frb.getFilter()).isInstanceOf(filterType);
    }
}
