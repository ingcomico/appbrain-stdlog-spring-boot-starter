package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.jdbc.StdlogClientDbQueryListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Auto-configuración del módulo JDBC: wrappea el {@code DataSource} con datasource-proxy.
 *
 * <p>Condiciones de activación:</p>
 * <ul>
 *   <li>{@code datasource-proxy} en classpath ({@code ProxyDataSourceBuilder}).</li>
 *   <li>Existe un bean {@code DataSource}.</li>
 *   <li>{@code stdlog.jdbc.enabled=true} (default).</li>
 * </ul>
 *
 * <p>El {@code DataSource} proxeado se registra como {@code @Primary} para que
 * Spring Boot (y cualquier otra librería que inyecte {@code DataSource}) use
 * automáticamente el proxy sin configuración adicional en la aplicación.</p>
 *
 * <p>Los beans {@code checkDatasourceProxyOnClasspath} y {@code showDataSource}
 * son helpers de diagnóstico, activos solo con {@code stdlog.jdbc.debug=true}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
@ConditionalOnClass(ProxyDataSourceBuilder.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "stdlog.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StdlogJdbcAutoConfiguration {

    @Bean
    public QueryExecutionListener stdlogDbListener(StdlogProperties props) {
        // Listener nuevo: recibe StdlogProperties completo para:
        // - usar mode (PROD/NON_PROD)
        // - usar niveles (success/failure)
        // - aplicar política slow/error
        return new StdlogClientDbQueryListener(props);
    }

    @Bean
    @Primary
    public DataSource stdlogDataSource(DataSource realDataSource,
            QueryExecutionListener stdlogDbListener,
            StdlogProperties props) {

        return ProxyDataSourceBuilder
                .create(realDataSource)
                .name(props.getJdbc().getPoolName())
                .listener(stdlogDbListener)
                .build();
    }

    // --- Debug helpers (detrás de property stdlog.jdbc.debug=true) ---

    @Bean
    @ConditionalOnProperty(prefix = "stdlog.jdbc", name = "debug", havingValue = "true")
    ApplicationRunner checkDatasourceProxyOnClasspath() {
        return args -> {
            try {
                Class.forName("net.ttddyy.dsproxy.support.ProxyDataSourceBuilder");
                System.out.println("datasource-proxy PRESENT");
            } catch (ClassNotFoundException e) {
                System.out.println("datasource-proxy NOT present");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "stdlog.jdbc", name = "debug", havingValue = "true")
    ApplicationRunner showDataSource(DataSource ds) {
        return args -> System.out.println("DataSource bean = " + ds.getClass().getName());
    }
}