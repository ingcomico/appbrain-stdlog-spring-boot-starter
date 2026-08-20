package appbrain.stdlog.util.consumer;

import appbrain.stdlog.util.StdlogCallerResolver;
import appbrain.stdlog.util.consumer.commons.CommonsCaller;

/**
 * Helper de test que simula código de una app consumidora, ubicado deliberadamente
 * fuera del paquete de {@link StdlogCallerResolver} para poder probar el matching
 * por {@code basePackage} sin que el propio resolver se auto-matchee.
 */
public final class ConsumerCaller {

    private ConsumerCaller() {}

    public static StackTraceElement callResolver(String basePackage) {
        return StdlogCallerResolver.findConsumerCaller(basePackage);
    }

    public static StackTraceElement wrapThroughCommons(String basePackage) {
        return CommonsCaller.callResolver(basePackage);
    }
}
