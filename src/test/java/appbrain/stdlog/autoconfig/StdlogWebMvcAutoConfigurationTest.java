package appbrain.stdlog.autoconfig;

import appbrain.stdlog.web.StdlogMvcOperationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistryTestAccessor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogWebMvcAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogWebMvcAutoConfiguration.class));

    @Test
    void shouldRegisterOperationInterceptorAndWebMvcConfigurer() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StdlogMvcOperationInterceptor.class);
            assertThat(context).hasSingleBean(WebMvcConfigurer.class);
        });
    }

    @Test
    void webMvcConfigurerShouldRegisterTheOperationInterceptor() {
        runner.run(context -> {
            WebMvcConfigurer configurer = context.getBean(WebMvcConfigurer.class);
            InterceptorRegistry registry = new InterceptorRegistry();

            configurer.addInterceptors(registry);

            assertThat(InterceptorRegistryTestAccessor.interceptorsOf(registry)).hasSize(1);
        });
    }
}
