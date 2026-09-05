package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogModeResolver;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Resuelve el modo productivo al arrancar y lo instala en {@code StdlogModeResolver} (`ADR-0013`).
 *
 * <p>Es el único punto donde la librería ve el {@code Environment} de Spring, que es lo que le
 * permite consultar los perfiles activos. {@code StdlogModeResolver} es una fachada estática
 * —igual que {@code StdlogMasker} y {@code StdlogFailsafe}— porque lo consultan puntos de
 * instrumentación que no son beans.</p>
 *
 * <p>Resolverlo una vez sustituye a un {@code System.getenv(...)} por evento en los cuatro
 * puntos que consultan el modo.</p>
 *
 * <p>Sin condiciones de classpath: sólo depende de {@code core} y {@code config}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
public class StdlogModeAutoConfiguration {

    @Bean
    public InitializingBean stdlogModeConfigurer(StdlogProperties props, Environment environment) {
        return () -> StdlogModeResolver.configure(
                props.getMode(),
                List.of(environment.getActiveProfiles()),
                props.getProdProfiles());
    }
}
