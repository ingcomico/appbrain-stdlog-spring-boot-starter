package appbrain.stdlog.core;

import appbrain.stdlog.config.StdlogProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdlogModeResolverTest {

    @Test
    void shouldReturnTrueWhenModeIsForcedToProd() {
        StdlogProperties props = new StdlogProperties();
        props.setMode(StdlogProperties.Mode.PROD);

        assertTrue(StdlogModeResolver.isProd(props));
    }

    @Test
    void shouldReturnFalseWhenModeIsForcedToNonProd() {
        StdlogProperties props = new StdlogProperties();
        props.setMode(StdlogProperties.Mode.NON_PROD);

        assertFalse(StdlogModeResolver.isProd(props));
    }

    @Test
    void shouldDefaultToNonProdWhenModeIsAutoAndNoEnvVarIsSet() {
        // STDLOG_MODE no está definido en el entorno de test.
        StdlogProperties props = new StdlogProperties();
        props.setMode(StdlogProperties.Mode.AUTO);

        assertFalse(StdlogModeResolver.isProd(props));
    }

    @Test
    void shouldDefaultToNonProdWhenPropsIsNull() {
        assertFalse(StdlogModeResolver.isProd(null));
    }

    @Test
    void shouldDefaultToNonProdWhenModeItselfIsNull() {
        StdlogProperties props = new StdlogProperties();
        props.setMode(null);

        assertFalse(StdlogModeResolver.isProd(props));
    }
}
