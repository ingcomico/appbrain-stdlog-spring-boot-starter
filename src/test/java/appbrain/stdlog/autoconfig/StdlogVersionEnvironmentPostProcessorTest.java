package appbrain.stdlog.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StdlogVersionEnvironmentPostProcessorTest {

    private final StdlogVersionEnvironmentPostProcessor processor = new StdlogVersionEnvironmentPostProcessor();

    @Test
    void shouldAddLibVersionPropertyFromClasspathResource() {
        StandardEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, null);

        String version = env.getProperty("stdlog.libVersion");
        assertNotNull(version);
        assertFalse(version.isBlank());
    }

    @Test
    void shouldNotOverwriteExistingLibVersionProperty() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new MapPropertySource("test", java.util.Map.of("stdlog.libVersion", "custom-value")));

        processor.postProcessEnvironment(env, null);

        assertEquals("custom-value", env.getProperty("stdlog.libVersion"));
    }
}
