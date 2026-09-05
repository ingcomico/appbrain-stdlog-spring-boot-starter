package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogModeResolver;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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

    /**
     * Rellena {@code stdlog.consumerBasePackage} con el paquete de la aplicacion cuando el
     * consumidor no lo configura (auditoria F-12).
     *
     * <p>Sin el, {@code AppTraceUtil} no tiene con que filtrar y {@code error.app_trace} sale
     * <b>siempre vacio</b>, en silencio: el campo estrella del evento de error estaba apagado
     * de fabrica y nada lo decia. Se usa {@code AutoConfigurationPackages}, que es el mecanismo
     * estandar de Spring Boot para saber donde vive la aplicacion —el paquete de la clase
     * anotada con {@code @SpringBootApplication}—, asi que acierta sin que nadie configure nada.</p>
     */
    @Bean
    public InitializingBean stdlogConsumerBasePackageDefaulter(StdlogProperties props, BeanFactory beanFactory) {
        return () -> {
            String configured = props.getConsumerBasePackage();
            if (configured != null && !configured.isBlank()) return;
            if (!AutoConfigurationPackages.has(beanFactory)) return;

            List<String> packages = AutoConfigurationPackages.get(beanFactory);
            if (!packages.isEmpty()) {
                props.setConsumerBasePackage(packages.get(0));
            }
        };
    }
}
