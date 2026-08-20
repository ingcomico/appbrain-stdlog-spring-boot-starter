package org.springframework.web.servlet.config.annotation;

import java.util.List;

/**
 * Expone {@code InterceptorRegistry.getInterceptors()} (protected) para poder
 * verificar en tests que un {@code WebMvcConfigurer} realmente registró un interceptor.
 */
public final class InterceptorRegistryTestAccessor {

    private InterceptorRegistryTestAccessor() {}

    public static List<Object> interceptorsOf(InterceptorRegistry registry) {
        return registry.getInterceptors();
    }
}
