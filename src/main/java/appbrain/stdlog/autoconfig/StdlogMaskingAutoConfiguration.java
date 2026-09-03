package appbrain.stdlog.autoconfig;

import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogMasker;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Instala en {@code StdlogMasker} la configuración de enmascaramiento del consumidor (ADR-0010).
 *
 * <p>{@code StdlogEmitter} y {@code StdlogMasker} son fachadas estáticas sin acceso al contexto
 * de Spring —el mismo patrón que ya usan {@code StdlogCustom} y {@code StdlogEmitter}—, así que
 * la configuración se transfiere una vez al arrancar. Hasta ese momento rige la lista
 * incorporada: el enmascarado está activo desde el primer evento, incluso durante el arranque.</p>
 *
 * <p>Sin condiciones de classpath: sólo depende de {@code appbrain.stdlog.core} y
 * {@code appbrain.stdlog.config}, que siempre están presentes.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StdlogProperties.class)
public class StdlogMaskingAutoConfiguration {

    @Bean
    public InitializingBean stdlogMaskingConfigurer(StdlogProperties props) {
        return () -> {
            StdlogProperties.Masking cfg = props.getMasking();
            if (cfg == null) return;

            // `keys` vacío significa "quedarse con la lista incorporada"; si trae valores,
            // la reemplaza. `additionalKeys` siempre suma sobre lo que resulte.
            List<String> effective = new ArrayList<>(
                    cfg.getKeys().isEmpty() ? StdlogMasker.DEFAULT_KEYS : cfg.getKeys());
            effective.addAll(cfg.getAdditionalKeys());

            StdlogMasker.configure(cfg.isEnabled(), effective, cfg.getPlaceholder());
        };
    }
}
