package appbrain.stdlog.autoconfig;

import appbrain.stdlog.restclient.StdlogWebClientExchangeFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogWebClientAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogWebClientAutoConfiguration.class));

    @Test
    void shouldAddFilterToWebClientBuildersInTheContext() {
        runner.withBean("webClientBuilder", WebClient.Builder.class, WebClient::builder).run(context -> {
            assertThat(context).hasSingleBean(StdlogWebClientExchangeFilter.class);

            WebClient.Builder builder = context.getBean(WebClient.Builder.class);
            List<ExchangeFilterFunction> filters = new ArrayList<>();
            builder.filters(filters::addAll);

            assertThat(filters).contains(context.getBean(StdlogWebClientExchangeFilter.class));
        });
    }

    @Test
    void shouldNotRegisterWhenRestclientDisabled() {
        runner.withPropertyValues("stdlog.restclient.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StdlogWebClientExchangeFilter.class));
    }

    @Test
    void shouldNotRegisterInNonServletApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogWebClientAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(StdlogWebClientExchangeFilter.class));
    }
}
