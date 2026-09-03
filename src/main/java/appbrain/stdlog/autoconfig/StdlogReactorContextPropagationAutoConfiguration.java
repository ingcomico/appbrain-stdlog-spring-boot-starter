package appbrain.stdlog.autoconfig;

import appbrain.stdlog.core.StdlogReactorContext;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registra un {@code ThreadLocalAccessor} de Micrometer para {@code request_id}, de modo que
 * ese valor viaje Reactor Context ↔ MDC cuando el consumidor active la propagación automática
 * ({@code reactor.core.publisher.Hooks.enableAutomaticContextPropagation()}). Fase 2 de ADR-0008.
 *
 * <p>Sirve sobre todo para R2DBC: {@code r2dbc-proxy} no expone el {@code ContextView} a sus
 * listeners, así que {@code StdlogR2dbcQueryListener} lee del MDC; con este accessor + el hook
 * activado por la app, el {@code request_id} llega al MDC en los hilos del event-loop y por
 * tanto al evento {@code CLIENT_DB}.</p>
 *
 * <p>Sólo se activa en apps reactivas con {@code io.micrometer:context-propagation} en el
 * classpath. El registro es idempotente (por clave) e inerte hasta que la app active el hook;
 * el starter <b>no</b> lo activa (es un switch global de la aplicación).</p>
 */
@AutoConfiguration
@ConditionalOnClass(ContextRegistry.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class StdlogReactorContextPropagationAutoConfiguration {

    @Bean
    InitializingBean stdlogRequestIdThreadLocalAccessorRegistrar() {
        return () -> ContextRegistry.getInstance().registerThreadLocalAccessor(
                StdlogReactorContext.REQUEST_ID,
                () -> MDC.get(StdlogReactorContext.REQUEST_ID),
                value -> MDC.put(StdlogReactorContext.REQUEST_ID, value),
                () -> MDC.remove(StdlogReactorContext.REQUEST_ID));
    }
}
