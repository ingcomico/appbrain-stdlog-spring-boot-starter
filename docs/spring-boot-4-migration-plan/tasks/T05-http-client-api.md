# T05 - Migrar APIs HTTP de Boot 3 a Boot 4

## Objetivo
Adaptar los customizers usados por `stdlog`.

## Boot 3
```java
org.springframework.boot.web.client.RestClientCustomizer
org.springframework.boot.web.client.RestTemplateCustomizer
```

## Boot 4
```java
org.springframework.boot.restclient.RestClientCustomizer
org.springframework.boot.restclient.RestTemplateCustomizer
```

## Implementación esperada
```java
@Bean
RestTemplateCustomizer stdlogRestTemplateCustomizer(
        StdlogClientHttpInterceptor interceptor) {
    return restTemplate -> restTemplate.getInterceptors().add(interceptor);
}

@Bean
RestClientCustomizer stdlogRestClientCustomizer(
        StdlogClientHttpInterceptor interceptor) {
    return builder -> builder.requestInterceptor(interceptor);
}
```

## Revisar también
- Dependencia/módulo `spring-boot-restclient` o starter correspondiente.
- Que el interceptor no se registre dos veces.
- Que las condiciones de auto-config sigan siendo válidas.

## Done
- Compilan ambos customizers en Boot 4.
- Tests verifican que el interceptor se registra.
