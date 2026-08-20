package appbrain.stdlog.autoconfig;

import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogJdbcAutoConfiguration.class));

    @Test
    void shouldProxyDataSourceWhenDataSourceBeanIsPresent() {
        runner.withBean(DataSource.class, StubDataSource::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(QueryExecutionListener.class);
                    DataSource proxied = context.getBean(DataSource.class);
                    assertThat(proxied.getClass().getName()).contains("ProxyDataSource");
                });
    }

    @Test
    void shouldNotActivateWhenNoDataSourceBeanIsPresent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(QueryExecutionListener.class));
    }

    @Test
    void shouldNotActivateWhenJdbcModuleDisabled() {
        runner.withBean(DataSource.class, StubDataSource::new)
                .withPropertyValues("stdlog.jdbc.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryExecutionListener.class));
    }

    @Test
    void shouldNotRegisterDebugRunnersByDefault() {
        runner.withBean(DataSource.class, StubDataSource::new)
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void shouldRegisterDebugRunnersWhenDebugEnabled() {
        runner.withBean(DataSource.class, StubDataSource::new)
                .withPropertyValues("stdlog.jdbc.debug=true")
                .run(context -> assertThat(context.getBeansOfType(ApplicationRunner.class)).hasSize(2));
    }

    /** Doble mínimo de DataSource: ninguno de sus métodos se invoca al construir el proxy. */
    private static final class StubDataSource implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { throw new UnsupportedOperationException(); }
    }
}
