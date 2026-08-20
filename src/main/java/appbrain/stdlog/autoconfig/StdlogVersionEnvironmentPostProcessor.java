package appbrain.stdlog.autoconfig;

import java.io.IOException;
import java.util.Properties;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * {@link EnvironmentPostProcessor} que carga la versión del starter en el entorno de Spring.
 *
 * <p>Lee {@code stdlog-version.properties} desde el classpath (empaquetado dentro del JAR)
 * e inyecta la propiedad {@code stdlog.libVersion} en el entorno de la aplicación.
 * Esto permite que las apps consumidoras exponer la versión del starter via
 * {@code /actuator/env} o usarla en logs de startup.</p>
 *
 * <p>No sobreescribe la propiedad si ya estaba definida (ej. en tests o sobreescritura manual).</p>
 */
public class StdlogVersionEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Properties props = new Properties();
        try (var in = getClass().getClassLoader().getResourceAsStream("stdlog-version.properties")) {
            if (in == null) return;
            props.load(in);

            // Solo agrega si existe y no está ya definida
            if (!props.isEmpty() && environment.getProperty("stdlog.libVersion") == null) {
                environment.getPropertySources().addLast(new PropertiesPropertySource("stdlogVersion", props));
            }
        } catch (IOException ignored) {
            // no-op
        }
    }
}
