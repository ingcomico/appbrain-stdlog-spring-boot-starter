package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogModeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auditoría F-12: {@code error.app_trace} salía <b>siempre vacío</b> si nadie configuraba
 * {@code stdlog.consumerBasePackage}, porque {@code AppTraceUtil} no tiene con qué filtrar.
 * El campo estrella del evento de error estaba apagado de fábrica, y nada lo decía.
 */
class ConsumerBasePackageDefaultTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogModeAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        StdlogModeResolver.reset();
    }

    @Configuration
    @AutoConfigurationPackage
    static class AppInThisPackage {}

    @Test
    void shouldDefaultToTheApplicationPackageWhenNotConfigured() {
        runner.withUserConfiguration(AppInThisPackage.class).run(context -> {
            StdlogProperties props = context.getBean(StdlogProperties.class);
            assertThat(props.getConsumerBasePackage())
                    .as("se infiere del paquete de la aplicacion, sin configurar nada")
                    .isEqualTo("appbrain.stdlog.autoconfig");
        });
    }

    @Test
    void shouldNotOverrideAnExplicitValue() {
        runner.withUserConfiguration(AppInThisPackage.class)
                .withPropertyValues("stdlog.consumer-base-package=com.example.myapp")
                .run(context -> assertThat(context.getBean(StdlogProperties.class).getConsumerBasePackage())
                        .isEqualTo("com.example.myapp"));
    }

    @Test
    void shouldNotFailWhenThereIsNoApplicationPackage() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StdlogProperties.class).getConsumerBasePackage()).isNull();
        });
    }
}
