package appbrain.stdlog.autoconfig;

import appbrain.stdlog.webflux.StdlogWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogWebFluxAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogWebFluxAutoConfiguration.class));

    @Test
    void shouldRegisterWebFilterInReactiveApps() {
        runner.run(context -> assertThat(context).hasSingleBean(StdlogWebFilter.class));
    }

    @Test
    void shouldNotRegisterWhenControllerModuleDisabled() {
        runner.withPropertyValues("stdlog.controller.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StdlogWebFilter.class));
    }

    @Test
    void shouldNotRegisterWhenWebfluxDisabled() {
        runner.withPropertyValues("stdlog.controller.webflux.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StdlogWebFilter.class));
    }

    @Test
    void shouldNotRegisterInNonReactiveApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogWebFluxAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(StdlogWebFilter.class));
    }
}
