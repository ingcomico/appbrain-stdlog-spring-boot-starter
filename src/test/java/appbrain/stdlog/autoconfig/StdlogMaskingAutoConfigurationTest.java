package appbrain.stdlog.autoconfig;

import appbrain.stdlog.core.StdlogMasker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdlogMaskingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StdlogMaskingAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        StdlogMasker.reset();
    }

    private static Map<String, Object> payload(String key, Object value) {
        return new LinkedHashMap<>(Map.of(key, value));
    }

    @Test
    void shouldApplyBuiltInKeysWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(StdlogMasker.mask(payload("password", "s3cr3t")).get("password")).isEqualTo("***");
        });
    }

    @Test
    void shouldAddAdditionalKeysOnTopOfTheBuiltInList() {
        runner.withPropertyValues("stdlog.masking.additional-keys=iban").run(context -> {
            assertThat(StdlogMasker.mask(payload("iban", "ES91")).get("iban")).isEqualTo("***");
            assertThat(StdlogMasker.mask(payload("password", "x")).get("password"))
                    .as("additionalKeys suma, no reemplaza")
                    .isEqualTo("***");
        });
    }

    @Test
    void shouldLetKeysReplaceTheBuiltInList() {
        runner.withPropertyValues("stdlog.masking.keys=iban").run(context -> {
            assertThat(StdlogMasker.mask(payload("iban", "ES91")).get("iban")).isEqualTo("***");
            assertThat(StdlogMasker.mask(payload("password", "x")).get("password"))
                    .as("keys reemplaza la lista incorporada")
                    .isEqualTo("x");
        });
    }

    @Test
    void shouldHonourCustomPlaceholder() {
        runner.withPropertyValues("stdlog.masking.placeholder=[REDACTED]").run(context ->
                assertThat(StdlogMasker.mask(payload("token", "abc")).get("token")).isEqualTo("[REDACTED]"));
    }

    @Test
    void shouldDisableMaskingWhenRequested() {
        runner.withPropertyValues("stdlog.masking.enabled=false").run(context ->
                assertThat(StdlogMasker.mask(payload("password", "s3cr3t")).get("password")).isEqualTo("s3cr3t"));
    }
}
