package appbrain.stdlog.util.consumer.commons;

import appbrain.stdlog.util.StdlogCallerResolver;

/**
 * Helper de test ubicado deliberadamente en un subpaquete {@code .commons.} dentro
 * del basePackage de test, para ejercitar la exclusión de frames "commons" de
 * {@link StdlogCallerResolver}.
 */
public final class CommonsCaller {

    private CommonsCaller() {}

    public static StackTraceElement callResolver(String basePackage) {
        return StdlogCallerResolver.findConsumerCaller(basePackage);
    }
}
