package appbrain.stdlog.autoconfig;

import appbrain.stdlog.web.StdlogMvcOperationInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuración MVC: registra el interceptor que resuelve el nombre de la operación.
 *
 * <p>Registra {@code StdlogMvcOperationInterceptor} con {@code order=-100} para ejecutarse
 * antes que otros interceptors de la aplicación, garantizando que {@code operation}
 * esté disponible en el MDC desde el inicio del procesamiento MVC.</p>
 */
@AutoConfiguration
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StdlogWebMvcAutoConfiguration {

    @Bean
    public StdlogMvcOperationInterceptor stdlogMvcOperationInterceptor() {
        return new StdlogMvcOperationInterceptor();
    }

    @Bean
    public WebMvcConfigurer stdlogWebMvcConfigurer(StdlogMvcOperationInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**").order(-100);
            }
        };
    }
}
