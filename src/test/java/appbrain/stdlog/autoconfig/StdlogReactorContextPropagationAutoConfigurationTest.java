package appbrain.stdlog.autoconfig;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StdlogReactorContextPropagationAutoConfigurationTest {

    @AfterEach
    void cleanup() {
        ContextRegistry.getInstance().removeThreadLocalAccessor("request_id");
        MDC.clear();
    }

    @Test
    void shouldRegisterRequestIdThreadLocalAccessorInReactiveApps() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogReactorContextPropagationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasBean("stdlogRequestIdThreadLocalAccessorRegistrar");
                    assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                            .anyMatch(a -> "request_id".equals(a.key()));
                });
    }

    @Test
    void shouldNotRegisterInNonReactiveApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StdlogReactorContextPropagationAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean("stdlogRequestIdThreadLocalAccessorRegistrar"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registeredAccessorBridgesRequestIdToMdc() {
        // registro equivalente al del autoconfig
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                "request_id",
                () -> MDC.get("request_id"),
                v -> MDC.put("request_id", v),
                () -> MDC.remove("request_id"));

        ThreadLocalAccessor<String> acc = (ThreadLocalAccessor<String>) ContextRegistry.getInstance()
                .getThreadLocalAccessors().stream()
                .filter(a -> "request_id".equals(a.key()))
                .findFirst().orElseThrow();

        MDC.clear();
        acc.setValue("req-x");
        assertEquals("req-x", MDC.get("request_id"), "el accessor escribe request_id en el MDC");
        assertEquals("req-x", acc.getValue(), "y lo lee de vuelta");
    }
}
