package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.r2dbc.StdlogR2dbcQueryListener;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuración del logging de queries R2DBC ({@code CLIENT_DB direction=OUT}).
 * Ver ADR-0007.
 *
 * <p>Condiciones de activación:</p>
 * <ul>
 *   <li>{@code r2dbc-proxy} en classpath ({@code ProxyConnectionFactory}).</li>
 *   <li>Existe un bean {@code io.r2dbc.spi.ConnectionFactory}.</li>
 *   <li>{@code stdlog.jdbc.enabled=true} (default) y {@code stdlog.jdbc.r2dbc.enabled=true} (default).</li>
 * </ul>
 *
 * <p><b>No</b> se limita a aplicaciones reactivas: R2DBC en una app servlet (con {@code .block()})
 * es un caso válido y ahí la correlación con el request es completa. Reutiliza toda la
 * configuración {@code stdlog.jdbc.*}.</p>
 *
 * <p>El {@code ConnectionFactory} proxeado se registra como {@code @Primary}, como hace el
 * módulo JDBC con el {@code DataSource}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnClass(ProxyConnectionFactory.class)
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnProperty(prefix = "stdlog.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogR2dbcAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "stdlog.jdbc.r2dbc", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StdlogR2dbcQueryListener stdlogR2dbcQueryListener(StdlogProperties props) {
        return new StdlogR2dbcQueryListener(props);
    }

    @Bean
    @Primary
    @ConditionalOnBean(StdlogR2dbcQueryListener.class)
    public ConnectionFactory stdlogConnectionFactory(ConnectionFactory realConnectionFactory,
            ProxyExecutionListener stdlogR2dbcQueryListener) {

        return ProxyConnectionFactory.builder(realConnectionFactory)
                .listener(stdlogR2dbcQueryListener)
                .build();
    }
}
