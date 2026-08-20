package appbrain.stdlog.autoconfig;

import appbrain.stdlog.restclient.StdlogClientHttpInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.HttpAccessor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogRestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogRestClientAutoConfiguration.class));

    @Test
    void shouldRegisterInterceptorAndCustomizerByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StdlogClientHttpInterceptor.class);
            assertThat(context).hasSingleBean(RestTemplateCustomizer.class);
            assertThat(context).hasSingleBean(RestClientCustomizer.class);
        });
    }

    @Test
    void shouldNotRegisterBeansWhenRestclientModuleDisabled() {
        runner.withPropertyValues("stdlog.restclient.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StdlogClientHttpInterceptor.class);
                    assertThat(context).doesNotHaveBean(RestTemplateCustomizer.class);
                    assertThat(context).doesNotHaveBean(RestClientCustomizer.class);
                });
    }

    @Test
    void customizerShouldAddInterceptorAndBufferingRequestFactory() throws Exception {
        RestTemplateCustomizer[] customizerHolder = new RestTemplateCustomizer[1];
        StdlogClientHttpInterceptor[] interceptorHolder = new StdlogClientHttpInterceptor[1];
        runner.run(context -> {
            customizerHolder[0] = context.getBean(RestTemplateCustomizer.class);
            interceptorHolder[0] = context.getBean(StdlogClientHttpInterceptor.class);
        });

        RestTemplate restTemplate = new RestTemplate();
        customizerHolder[0].customize(restTemplate);

        assertThat(restTemplate.getInterceptors()).contains(interceptorHolder[0]);

        // RestTemplate.getRequestFactory() envuelve con InterceptingClientHttpRequestFactory
        // apenas hay interceptors; hay que leer el factory "crudo" seteado internamente.
        Field field = HttpAccessor.class.getDeclaredField("requestFactory");
        field.setAccessible(true);
        assertThat(field.get(restTemplate)).isInstanceOf(BufferingClientHttpRequestFactory.class);
    }

    @Test
    void restClientCustomizerShouldAddInterceptor() {
        RestClientCustomizer[] customizerHolder = new RestClientCustomizer[1];
        StdlogClientHttpInterceptor[] interceptorHolder = new StdlogClientHttpInterceptor[1];
        runner.run(context -> {
            customizerHolder[0] = context.getBean(RestClientCustomizer.class);
            interceptorHolder[0] = context.getBean(StdlogClientHttpInterceptor.class);
        });

        RestClient.Builder builder = RestClient.builder();
        customizerHolder[0].customize(builder);

        builder.requestInterceptors(interceptors ->
                assertThat(interceptors).contains(interceptorHolder[0]));
    }
}
