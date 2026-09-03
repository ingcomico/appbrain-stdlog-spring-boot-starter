# Contexto del Proyecto para Agentes de IA — rama `spring-boot-3.x`

Esta rama es la **línea Spring Boot 3** del starter (ver `ADR-0005`). Tiene **paridad funcional** con la rama `main` (línea Spring Boot 4): mismas capacidades, misma configuración `stdlog.*`, mismo JSON de salida, mismo comportamiento observable.

## Fuente de contexto arquitectónico

- La descripción completa de arquitectura, paquetes, contratos públicos, flujo principal, principios y limitaciones vive en `AI_CONTEXT.md` de la rama `main` y **aplica igual aquí**, salvo las diferencias de plataforma listadas abajo.
- Para consultarla sin cambiar de rama: `git show main:AI_CONTEXT.md`.
- Los ADR en `docs/adr/` son los mismos que en `main`. `ADR-0001`, `0002`, `0003` y `0006` llevan una "Nota de rama" al inicio con lo que difiere en esta línea. `ADR-0007` (R2DBC) y `ADR-0008` (soporte WebFlux; **todas las fases hechas**) aplican sin diferencias.

## Diferencias de esta rama respecto a `main`

| Aspecto | `main` (Boot 4) | `spring-boot-3.x` (esta rama) |
|---|---|---|
| Artefacto | `appbrain:appbrain-stdlog-spring-boot-starter:4.x.y` | `...:3.x.y` (arranca en `3.0.0`) |
| BOM | Spring Boot `4.1.0` | Spring Boot `3.5.16` |
| Binding JSON | Jackson 3 (`tools.jackson.core:jackson-databind`) | Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`) |
| `logstash-logback-encoder` | `9.0` | `8.1` |
| `logback-spring-stdlog.xml` | provider `pattern` para `stdlog_lib_version`, `stackTrace` sin `nestedField` | `globalCustomFields` + bloque `stackTrace` original (equivalente en salida) |
| Customizers HTTP saliente | `org.springframework.boot.restclient.RestClientCustomizer` / `RestTemplateCustomizer` (módulo `spring-boot-restclient`) | `org.springframework.boot.web.client.RestClientCustomizer` / `RestTemplateCustomizer` (parte de `spring-boot`) |
| `EnvironmentPostProcessor` | paquete `org.springframework.boot`; clave `spring.factories` = `org.springframework.boot.EnvironmentPostProcessor` | paquete `org.springframework.boot.env`; clave `spring.factories` = `org.springframework.boot.env.EnvironmentPostProcessor` |
| Iteración de `HttpHeaders` en `StdlogClientHttpInterceptor` y `StdlogClientHttpPayload` | `headers.headerSet()` | `headers.entrySet()` |
| Logging de `WebClient` (`ADR-0006`) | `spring-webflux` + `reactor-core` `provided`; filtro añadido vía `BeanPostProcessor` sobre `WebClient.Builder` | idéntico (el `BeanPostProcessor` evita depender del paquete de `WebClientCustomizer`, que difiere entre majors) |
| Logging de R2DBC (`ADR-0007`) | `io.r2dbc:r2dbc-proxy` + `r2dbc-spi` `provided` | **idéntico** (código agnóstico: `io.r2dbc.*` + `org.slf4j.MDC`) |
| Entrada WebFlux (`ADR-0008`, todas las fases) | `spring-webflux` + `io.micrometer:context-propagation` `provided`; `StdlogWebFilter`, `StdlogWebExceptionHandler`, `StdlogCustomReactive`, `StdlogReactiveCorrelation`, lectura del Reactor Context en `StdlogWebClientExchangeFilter`, `ThreadLocalAccessor` de `request_id` | **idéntico** (usa API de Spring 6/7 común: `WebFilter`, `WebExceptionHandler`, `HandlerMapping`, `AnnotatedElementUtils`, decoradores reactivos, `Mono.deferContextual`) |

Nada más difiere. El código de negocio (`StdlogEmitter`, `StdlogTraceCorrelation`, builders de payload, `StdlogProperties`, filtros, interceptores, listener JDBC, `StdlogWebClientExchangeFilter`, `StdlogR2dbcQueryListener`, `StdlogWebFilter`, `StdlogWebExceptionHandler`, `StdlogCustomReactive`, `StdlogReactiveCorrelation`) es funcionalmente idéntico.

## Plataforma y build (esta rama)

- Java: bytecode `release` 17; la build corre en JDK 17 a 25.
- Toolchain: `maven-compiler-plugin` 3.14.0 (`<release>17>`), `maven-surefire-plugin` 3.5.4, `jacoco-maven-plugin` 0.8.15.
- `spring-webflux` y `io.projectreactor:reactor-core` en scope `provided` (logging de `WebClient` `ADR-0006` y entrada WebFlux `ADR-0008`).
- `io.r2dbc:r2dbc-proxy` y `io.r2dbc:r2dbc-spi` en scope `provided` (solo para el logging de R2DBC, `ADR-0007`).
- `io.micrometer:context-propagation` en scope `provided` (`ThreadLocalAccessor` de `request_id` para R2DBC en apps WebFlux, `ADR-0008` Fase 2).
- `README.md` documenta coordenadas `...:3.0.0-local` para el flujo de `mvn clean deploy` a `release/`.
- Suite: 228 tests, 0 fallos, verificado en JDK 17 y JDK 25.

## Flujo de trabajo en esta rama

- Toda feature o fix transversal entra primero por `main` y se porta aquí (cherry-pick; si hay conflicto de `import`/dependencia se resuelve a mano; si divergieron mucho, reimplementación equivalente). Ver `ADR-0005`.
- Cambios específicos de Boot 3.5.x (seguridad, fixes de Spring) se hacen directamente aquí.
- `ADR-0004`: la actualización de documentación viaja en el mismo commit/PR que el cambio. Si un porte cambia algo de la tabla de "Diferencias" de arriba, se actualiza este archivo en el mismo cambio.
- Al portar un cambio que en `main` tocó su `AI_CONTEXT.md`, evaluar si afecta la tabla de diferencias o el delta de plataforma de esta rama.

## Reglas de Codebase Memory, frescura y "Cuándo crear un ADR"

Idénticas a `main` (`git show main:AI_CONTEXT.md`) y a `CLAUDE.md` / `AGENTS.md` de esta rama.
