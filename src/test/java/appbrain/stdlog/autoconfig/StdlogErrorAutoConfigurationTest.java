package appbrain.stdlog.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogErrorAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogErrorAutoConfiguration.class));

    @Test
    void shouldRegisterExceptionResolverByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(HandlerExceptionResolver.class));
    }

    @Test
    void shouldRegisterExceptionResolverWhenExplicitlyEnabled() {
        runner.withPropertyValues("stdlog.error.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(HandlerExceptionResolver.class));
    }

    @Test
    void shouldNotRegisterExceptionResolverWhenDisabled() {
        runner.withPropertyValues("stdlog.error.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HandlerExceptionResolver.class));
    }
}
