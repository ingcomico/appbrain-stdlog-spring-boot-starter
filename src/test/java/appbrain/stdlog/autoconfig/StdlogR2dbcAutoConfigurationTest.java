package appbrain.stdlog.autoconfig;

import appbrain.stdlog.r2dbc.StdlogR2dbcQueryListener;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogR2dbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogR2dbcAutoConfiguration.class));

    private static ConnectionFactory h2() {
        return ConnectionFactories.get("r2dbc:h2:mem:///ac-" + UUID.randomUUID());
    }

    @Test
    void shouldProxyConnectionFactoryWhenBeanIsPresent() {
        runner.withBean(ConnectionFactory.class, StdlogR2dbcAutoConfigurationTest::h2)
                .run(context -> {
                    assertThat(context).hasSingleBean(StdlogR2dbcQueryListener.class);
                    ConnectionFactory proxied = context.getBean(ConnectionFactory.class);
                    assertThat(proxied.getClass().getName().toLowerCase()).contains("proxy");
                });
    }

    @Test
    void shouldNotActivateWhenNoConnectionFactoryBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(StdlogR2dbcQueryListener.class));
    }

    @Test
    void shouldNotActivateWhenJdbcModuleDisabled() {
        runner.withBean(ConnectionFactory.class, StdlogR2dbcAutoConfigurationTest::h2)
                .withPropertyValues("stdlog.jdbc.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StdlogR2dbcQueryListener.class));
    }

    @Test
    void shouldNotActivateWhenR2dbcDisabled() {
        runner.withBean(ConnectionFactory.class, StdlogR2dbcAutoConfigurationTest::h2)
                .withPropertyValues("stdlog.jdbc.r2dbc.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StdlogR2dbcQueryListener.class));
    }
}
