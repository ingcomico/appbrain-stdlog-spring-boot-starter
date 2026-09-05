package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogModeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogModeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogModeAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        StdlogModeResolver.reset();
    }

    private static StdlogProperties auto() {
        StdlogProperties p = new StdlogProperties();
        p.setMode(StdlogProperties.Mode.AUTO);
        return p;
    }

    @Test
    void shouldResolveProdFromAnActiveSpringProfile() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(StdlogModeResolver.isProd(auto())).isTrue());
    }

    @Test
    void shouldResolveNonProdFromANonProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(StdlogModeResolver.isProd(auto())).isFalse());
    }

    /** El caso que cerraba F-10: sin ninguna señal se asume productivo. */
    @Test
    void shouldFallBackToProdWhenThereIsNoSignal() {
        runner.run(context -> assertThat(StdlogModeResolver.isProd(auto())).isTrue());
    }

    @Test
    void shouldHonourACustomProdProfileList() {
        runner.withPropertyValues("spring.profiles.active=produccion", "stdlog.prod-profiles=produccion,live")
                .run(context -> assertThat(StdlogModeResolver.isProd(auto())).isTrue());
    }

    @Test
    void explicitModeShouldWinOverProfiles() {
        runner.withPropertyValues("spring.profiles.active=prod", "stdlog.mode=NON_PROD")
                .run(context -> {
                    StdlogProperties props = context.getBean(StdlogProperties.class);
                    assertThat(StdlogModeResolver.isProd(props)).isFalse();
                });
    }
}
