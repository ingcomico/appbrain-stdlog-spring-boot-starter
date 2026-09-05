# Contexto del Proyecto para Agentes de IA

Este archivo es la fuente canonica de contexto compartido para los agentes de IA que trabajan sobre este repositorio.

## Estado de Verificacion

- Este contexto fue construido y verificado usando Codebase Memory MCP (`list_projects`, `index_status`, `get_architecture`, `check_index_coverage`) y lectura puntual del codigo actual.
- El indice del proyecto estaba `ready` y sin archivos `parse_partial` ni `skipped`; solo se excluyen por diseno (`gitignore`) archivos `.DS_Store` y los directorios `.git`, `.idea`, `release`, `target`.
- Antes de realizar analisis de impacto o cambios arquitectonicos, verificar la frescura del grafo mediante `check_index_coverage`.
- El grafo es la primera fuente para descubrir estructura de codigo, pero archivos de recursos o documentacion pueden no estar rastreados con la misma frescura; si `check_index_coverage` reporta `not_tracked`, leer la fuente directamente antes de concluir.
- Los directorios generados como `target`, `release`, `.git` e `.idea` no deben considerarse fuente estructural del proyecto.
- El grafo reporta una `Route` `/api/orders`: es un fixture usado en tests del filtro de controller (`ControllerBodyAndOutLoggingFilterTest`, `ControllerBodyAndOutLoggingFilterBehaviorTest`), no un endpoint real del starter. No debe interpretarse como contrato publico.

## Flujo de Trabajo de los Agentes

Antes de realizar cambios arquitectonicos, estructurales o que afecten contratos publicos:

1. Leer este archivo.
2. Usar Codebase Memory para entender la estructura del codigo afectado y sus relaciones.
3. Revisar los ADR relevantes dentro de `docs/adr/`. ADRs vigentes (todos en estado `Aceptado`):
   - `0001` — migracion de `main` a Spring Boot 4 / Jackson 3 (su regla de "congelar Boot 3" fue anulada por `0005`).
   - `0002` — correlacion de tracing MDC + OpenTelemetry por reflexion.
   - `0003` — salida JSON via Logback + logstash-logback-encoder.
   - `0004` — la documentacion afectada se actualiza en el mismo commit/PR que el cambio.
   - `0005` — dos lineas de mantenimiento (`main` = Boot 4, `spring-boot-3.x` = Boot 3) con paridad funcional.
   - `0006` — logging del evento `CLIENT_HTTP` para `WebClient` (cliente saliente reactivo, solo en apps servlet).
   - `0007` — logging del evento `CLIENT_DB` para R2DBC (base de datos reactiva, add-on no limitado a apps reactivas).
   - `0008` — soporte de aplicaciones WebFlux (entrada HTTP reactiva) como stack de primera clase. Implementado por fases (todas hechas) en `appbrain.stdlog.webflux`, sin tocar la via servlet.
   - `0010` — enmascaramiento de datos sensibles en el punto unico de emision. Resuelve el hallazgo F-04 de la auditoria.
   - `0011` — el logging nunca rompe el request, y nunca falla en silencio. Resuelve F-07. **Implementado.**
   - `0013` — deteccion del entorno productivo: perfiles de Spring y default seguro. Resuelve F-10. **Implementado.**
   - `0012` — orden de la instrumentacion de entrada HTTP y paridad servlet/reactivo. Resuelve F-08. **Implementado.**
   - `0014` — backend de logging: Logback y Log4j2 con salida equivalente (`Propuesto`). Atiende el fondo de F-13.
   - `0016` — integracion continua y verificacion automatica de la paridad entre lineas. Da cumplimiento a `ADR-0005`.

   Los numeros `0009` y `0015` siguen **reservados** para los hallazgos pendientes de la auditoria tecnica (guardas de classpath ya aplicadas, datos sensibles, fail-safety del logging, orden del filtro, deteccion de entorno, acoplamiento a Logback, versionado del esquema JSON). Se numeraron por tema, no por fecha de creacion, asi que el hueco es deliberado.
4. Determinar el impacto antes de modificar codigo.
5. No duplicar decisiones arquitectonicas en archivos especificos de cada agente.
6. Si existe una contradiccion entre este archivo, el codigo actual y otros documentos, reportarla antes de asumir cual es correcta.

## Proposito del Proyecto

`appbrain-stdlog-spring-boot-starter` es un starter Maven para logging estructurado JSON en aplicaciones Spring Boot servlet/MVC y WebFlux.

El proyecto provee:

- logging de requests/responses HTTP entrantes como `CONTROLLER_HTTP`: servlet/MVC y, desde `ADR-0008`, WebFlux (entrada reactiva);
- logging de llamadas HTTP salientes de `RestTemplate` y `RestClient` como `CLIENT_HTTP`;
- logging de queries a base de datos como `CLIENT_DB`: JDBC via `datasource-proxy` y R2DBC via `r2dbc-proxy` (ver `ADR-0007`);
- API publica de eventos de negocio mediante `StdlogCustom`;
- evento extra para excepciones MVC con severidad `WARN` o `ERROR` segun status final;
- correlacion de `trace_id` y `span_id` desde MDC o, si esta disponible, OpenTelemetry por reflexion;
- configuracion Logback reusable en `classpath:stdlog/logback-spring-stdlog.xml`.

Consumidores esperados: aplicaciones Spring Boot que quieran emitir logs estructurados bajo una clave `stdlog` usando SLF4J/Logback y configuracion por properties `stdlog.*`.

## Modelo de Ramas

Existen dos ramas permanentes con paridad funcional (ver `ADR-0005`). Este archivo describe la rama `main`.

| Rama | Spring Boot | Jackson | logstash-encoder | Estado |
|---|---|---|---|---|
| `main` | 4.1.0 | 3 (`tools.jackson.core`) | 9.0 | desarrollo activo, referencia de diseño; artefacto `4.x.y` |
| `spring-boot-3.x` | 3.5.16 | 2 (`com.fasterxml.jackson.core`) | 8.1 | soporte para consumidores Boot 3; artefacto `3.x.y` |

Ambas ofrecen las mismas capacidades, la misma configuracion `stdlog.*`, el mismo JSON de salida y el mismo comportamiento observable. Difieren solo en la version de Spring Boot, el binding Jackson, la version del encoder y las lineas `import` / recursos `META-INF` / `logback-spring-stdlog.xml` que eso implica. Toda feature o fix transversal entra por `main` y se porta a `spring-boot-3.x` inmediatamente tras el merge. Las ramas de trabajo se borran al mergear.

## Plataforma y Dependencias

- Artefacto Maven: `appbrain:appbrain-stdlog-spring-boot-starter`.
- Version declarada en `pom.xml`: `4.0.0` (el major sigue al major de Spring Boot; `spring-boot-3.x` publica `3.x.y`). Ver `ADR-0005`.
- Java: bytecode `release` 17; la build corre en JDK 17 a 25.
- BOM: Spring Boot `4.1.0`.
- Toolchain de build: `maven-compiler-plugin` 3.14.0 (`<release>17>`), `maven-surefire-plugin` 3.5.4, `jacoco-maven-plugin` 0.8.15.
- Dependencias relevantes:
  - `spring-boot-autoconfigure`;
  - `spring-boot-restclient`;
  - `spring-webmvc` con scope `provided`;
  - `spring-webflux` y `io.projectreactor:reactor-core` con scope `provided` (logging de `WebClient` `ADR-0006` y entrada WebFlux `ADR-0008`);
  - `io.r2dbc:r2dbc-proxy` y `io.r2dbc:r2dbc-spi` con scope `provided` (solo para el logging de R2DBC, ver `ADR-0007`);
  - `io.micrometer:context-propagation` con scope `provided` (`ThreadLocalAccessor` de `request_id` para R2DBC en apps WebFlux, `ADR-0008` Fase 2);
  - `jakarta.servlet-api` con scope `provided`;
  - `tools.jackson.core:jackson-databind` (Jackson 3);
  - `net.ttddyy:datasource-proxy:1.9`;
  - `net.logstash.logback:logstash-logback-encoder:9.0`;
  - `slf4j-api`.
- Publicacion configurada en `distributionManagement` hacia `file://${maven.multiModuleProjectDirectory}/release`.

`README.md` documenta las coordenadas con sufijo `-local` (`...:4.0.0-local`) para el flujo de `mvn clean deploy` a `release/`; `pom.xml` declara `4.0.0`. El sufijo `-local` es intencional para ese flujo de prueba local.

## Arquitectura

El codigo principal esta bajo `src/main/java/appbrain/stdlog`.

### Paquetes y Responsabilidades

- `appbrain.stdlog`: API publica directa del consumidor:
  - `StdlogCustom` para eventos de negocio;
  - `StdlogExcluded` para excluir endpoints/metodos de logs no criticos.
- `appbrain.stdlog.autoconfig`: autoconfiguraciones Spring Boot y post-procesamiento de version:
  - `StdlogAutoConfiguration`;
  - `StdlogWebMvcAutoConfiguration`;
  - `StdlogRestClientAutoConfiguration`;
  - `StdlogJdbcAutoConfiguration`;
  - `StdlogErrorAutoConfiguration`;
  - `StdlogVersionEnvironmentPostProcessor`.
- `appbrain.stdlog.config`: contrato de configuracion:
  - `StdlogProperties` con prefijo `stdlog`;
  - `StdlogLevel`;
  - modo `AUTO`, `PROD`, `NON_PROD`.
- `appbrain.stdlog.core`: primitives transversales:
  - `StdlogEmitter`;
  - `StdlogModeResolver` (modo productivo; cadena de `ADR-0013`, resuelto una vez al arrancar);
  - `StdlogFailsafe` (garantiza que un fallo del logging no altere la operacion instrumentada ni ocurra en silencio; `ADR-0011`);
  - `StdlogMasker` (enmascarado de valores sensibles antes de emitir; `ADR-0010`);
  - `StdlogTraceCorrelation`;
  - `StdlogReactorContext` (claves de correlacion en el `Context` de Reactor; lo escribe `StdlogWebFilter` y lo lee `StdlogWebClientExchangeFilter`; ver `ADR-0008` Fase 2);
  - `StdlogReactiveCorrelation` (resuelve `request_id`/`operation`/exclusion desde el `Context` — `operation` de forma perezosa desde el `ServerWebExchange`; lo usan `StdlogWebClientExchangeFilter` y `StdlogCustomReactive`; `ADR-0008` Fase 3).
- `appbrain.stdlog.web`: filtros/interceptores servlet/MVC y extractores HTTP:
  - `RequestIdMdcFilter`;
  - `ControllerBodyAndOutLoggingFilter`;
  - `StdlogMvcOperationInterceptor`;
  - `StdlogExceptionResolver`;
  - `HttpLogExtractors`;
  - `StdlogAttrs`.
- `appbrain.stdlog.restclient`: instrumentacion de clientes HTTP salientes:
  - `StdlogClientHttpInterceptor` (`RestTemplate` / `RestClient`);
  - `StdlogWebClientExchangeFilter` (`WebClient`, ver `ADR-0006`);
  - `StdlogClientHttpPayload` (armado del evento `CLIENT_HTTP`, compartido por el filtro WebClient);
  - `StdlogHttpBodyDecoder`.
- `appbrain.stdlog.r2dbc`: listener `r2dbc-proxy` para queries reactivas (`CLIENT_DB`, ver `ADR-0007`):
  - `StdlogR2dbcQueryListener`.
- `appbrain.stdlog.webflux`: instrumentacion de entrada HTTP reactiva (`ADR-0008` Fases 1 y 3):
  - `StdlogWebFilter` (`WebFilter` que emite `CONTROLLER_HTTP` + evento de error, escribe `request_id`/exclusion en el Reactor Context; resuelve `@StdlogExcluded` del `HandlerMethod` tras la cadena);
  - `StdlogWebExceptionHandler` (`WebExceptionHandler` `@Order(HIGHEST_PRECEDENCE)` que guarda la excepcion real en un atributo del exchange y la re-propaga sin consumirla, para que el evento de error lleve `type`/`message`/`app_trace` reales; `ADR-0008` Fase 3);
  - `StdlogCustomReactive` (variante reactiva de `StdlogCustom`: cada metodo devuelve `Mono<Void>` que lee la correlacion del Reactor Context y la restaura en el MDC alrededor de la delegacion a `StdlogCustom`, que **no se modifica**; `ADR-0008` Fase 3).
- `appbrain.stdlog.jdbc`: listener `datasource-proxy`:
  - `StdlogClientDbQueryListener`.
- `appbrain.stdlog.error`: utilidades de stack trace de aplicacion:
  - `AppTraceUtil`.
- `appbrain.stdlog.util`: resolucion de caller del consumidor:
  - `StdlogCallerResolver`.

### Recursos Publicos

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` registra once autoconfiguraciones (`StdlogModeAutoConfiguration`, `StdlogMaskingAutoConfiguration`, `StdlogAutoConfiguration`, `StdlogWebMvcAutoConfiguration`, `StdlogWebFluxAutoConfiguration`, `StdlogReactorContextPropagationAutoConfiguration`, `StdlogRestClientAutoConfiguration`, `StdlogWebClientAutoConfiguration`, `StdlogJdbcAutoConfiguration`, `StdlogR2dbcAutoConfiguration`, `StdlogErrorAutoConfiguration`). `StdlogAutoConfiguration`, `StdlogWebMvcAutoConfiguration` y `StdlogErrorAutoConfiguration` son `@ConditionalOnWebApplication(SERVLET)`; las dos ultimas tambien comprueban las clases MVC que exponen. `StdlogWebFluxAutoConfiguration` y `StdlogReactorContextPropagationAutoConfiguration` son `@ConditionalOnWebApplication(REACTIVE)`, por lo que las vias servlet y reactiva no co-activan. `StdlogMaskingAutoConfiguration` no tiene condiciones de classpath —solo depende de `core` y `config`— y transfiere `stdlog.masking.*` al campo estatico de `StdlogMasker` al arrancar. `StdlogWebFluxAutoConfiguration` registra dos beans: `StdlogWebFilter` y `StdlogWebExceptionHandler` (ambos gated por `stdlog.controller.webflux.enabled`). La Fase 3 no añade una linea nueva a `AutoConfiguration.imports`.
- `META-INF/spring.factories` registra `StdlogVersionEnvironmentPostProcessor` como `EnvironmentPostProcessor`.
- `stdlog/logback-spring-stdlog.xml` define un appender JSON de consola, logger `stdlog`, root logger y campo `stdlog_lib_version`.
- `stdlog-version.properties` alimenta la version de libreria expuesta como property `stdlog.libVersion`.

## Contratos Publicos

### Configuracion `stdlog.*`

`StdlogProperties` expone el prefijo `stdlog`, la property `prod-profiles` (`ADR-0013`) y cinco secciones: `controller`, `restclient`, `jdbc`, `error` y `masking` (`ADR-0010`: `enabled`, `keys`, `additional-keys`, `placeholder`).

Reglas vigentes:

- `stdlog.mode=AUTO` infiere desde `STDLOG_MODE`; si la variable no esta definida o no es reconocida, el modo efectivo es no productivo.
- `PROD` activa politicas anti-ruido donde el modulo lo soporte.
- `NON_PROD` fuerza logging amplio.
- `stdlog.consumerBasePackage` se usa para filtrar trazas de aplicacion y resolver caller en HTTP saliente.

### Controller HTTP

`StdlogAutoConfiguration` aplica solo en aplicaciones web servlet y registra:

- `RequestIdMdcFilter` con orden `Integer.MIN_VALUE`;
- `ControllerBodyAndOutLoggingFilter` con orden `Ordered.LOWEST_PRECEDENCE`.

`StdlogWebMvcAutoConfiguration` registra:

- `StdlogMvcOperationInterceptor`;
- un `WebMvcConfigurer` que lo aplica a `/**` con orden `-100`.

El flujo de controller emite eventos `CONTROLLER_HTTP` para entrada y salida. El filtro puede envolver request/response con `ContentCaching*Wrapper` segun `stdlog.controller.log-request-body` y `stdlog.controller.log-response-body`.

Desde `ADR-0012`, el filtro servlet se registra en `Integer.MIN_VALUE + 100`, es decir **por fuera de la cadena de Spring Security** (que va en `-100`), igual que `StdlogWebFilter` en la via reactiva. Consecuencia observable: los `401`/`403` de Spring Security, los rechazos de CORS y cualquier request cortado por un filtro externo pasan a emitir `CONTROLLER_HTTP` y evento de error, donde antes no emitian nada. Ademas el evento extra de error se emite **segun el status final** y no segun si hubo excepcion, asi que tambien se registran los 4xx/5xx sin excepcion (`ResponseEntity.status(403)`, 404 y 405 de Spring MVC). En los requests que nunca llegan a un handler, `route` se rellena con metodo + URI y `operation` queda ausente, porque no hubo handler que nombrar.

En aplicaciones **WebFlux** (`ADR-0008`), `StdlogWebFluxAutoConfiguration` (`@ConditionalOnWebApplication(REACTIVE)`) registra `StdlogWebFilter`, que emite el mismo `CONTROLLER_HTTP IN/OUT` y el evento extra de error (`WARN`/`ERROR` por status final), resuelve `operation`/`route` desde los atributos del `HandlerMapping` tras la cadena, aplica `excluded-path-patterns`, captura bodies con decoradores reactivos acotados por `maxRequestBodyBytes`/`maxResponseBodyBytes`, y escribe `request_id`/exclusion en el Reactor Context. Reutiliza `stdlog.controller.*` y `stdlog.error.*`; se apaga con `stdlog.controller.webflux.enabled=false`. Desde `ADR-0008` Fase 3, `StdlogWebFluxAutoConfiguration` tambien registra `StdlogWebExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`), que guarda la excepcion real en un atributo del exchange antes de que `ExceptionHandlingWebHandler` la convierta en respuesta; `StdlogWebFilter` la lee en su `doFinally`, asi el evento de error lleva `type`/`message`/`app_trace` reales (con `doOnError` como respaldo). Tambien en Fase 3, `@StdlogExcluded` en un handler method reactivo suprime sus `CONTROLLER_HTTP`/error INFO.

### Exclusion de Logs

Las exclusiones de paths `stdlog.controller.excluded-path-patterns` y la anotacion `StdlogExcluded` marcan `StdlogEmitter.MDC_EXCLUDED`.

La supresion aplica a `TRACE`, `DEBUG` e `INFO`. `WARN` y `ERROR` nunca se suprimen por esa marca.

La exclusion se propaga en el mismo thread a controller, JDBC, HTTP saliente y eventos custom porque se transporta en MDC durante el request.

### HTTP Saliente

`StdlogRestClientAutoConfiguration` se activa con `stdlog.restclient.enabled=true` o por omision.

Registra:

- `StdlogClientHttpInterceptor`;
- `RestTemplateCustomizer` si `RestTemplate` esta disponible;
- `RestClientCustomizer` si `RestClient` esta disponible.

Para `RestTemplate`, el customizer agrega el interceptor si no existe y reemplaza el request factory por `BufferingClientHttpRequestFactory`.

`StdlogClientHttpInterceptor` emite un unico evento `CLIENT_HTTP direction=IN` por llamada saliente despues de recibir la respuesta o capturar la excepcion. Aunque representa una llamada HTTP saliente, la direccion vigente es `IN` porque el evento se registra al entrar la respuesta al cliente instrumentado. En modo productivo puede omitir exitos segun `stdlog.restclient.log-only-on-failure-in-prod`.

Para `WebClient` (cliente reactivo), `StdlogWebClientAutoConfiguration` (activa con sólo `WebClient` en classpath — servlet, WebFlux o no-web, `ADR-0008` Fase 2) registra `StdlogWebClientExchangeFilter` y lo añade a los `WebClient.Builder` del contexto via `BeanPostProcessor`. El filtro emite el mismo evento `CLIENT_HTTP` con el mismo formato (helper `StdlogClientHttpPayload`). Para la correlacion resuelve `request_id`/`operation`/exclusion **MDC primero** (app servlet + `.block()`) y, si el MDC esta vacio, del **Reactor Context** (app WebFlux — lo puebla `StdlogWebFilter`); restaura ese contexto en el MDC alrededor de la emision. Bufferiza los bodies hasta `stdlog.restclient.webclient.max-capture-bytes` sin alterar el stream que recibe la app. Se apaga con `stdlog.restclient.webclient.enabled=false`. Ver `ADR-0006` / `ADR-0008`. `StdlogClientHttpInterceptor` no se modifico.

### JDBC

`StdlogJdbcAutoConfiguration` se activa si:

- `ProxyDataSourceBuilder` esta en classpath;
- existe un bean `DataSource`;
- `stdlog.jdbc.enabled=true` o no se configuro la propiedad.

Registra:

- `QueryExecutionListener` como `StdlogClientDbQueryListener`;
- un `DataSource` `@Primary` construido con `ProxyDataSourceBuilder`.

El listener emite `CLIENT_DB direction=OUT` despues de la query, con tiempo, outcome, flag `slow`, pool, SQL truncado, tipo de sentencia inferido, parametros opcionales y respuesta opcional. En modo productivo puede omitir queries exitosas no lentas segun `stdlog.jdbc.log-only-slow-or-failure-in-prod`.

### R2DBC (base de datos reactiva)

`StdlogR2dbcAutoConfiguration` se activa si `ProxyConnectionFactory` (`r2dbc-proxy`) esta en classpath, existe un bean `io.r2dbc.spi.ConnectionFactory`, y `stdlog.jdbc.enabled` + `stdlog.jdbc.r2dbc.enabled` (ambos default `true`). **No** esta limitada a apps reactivas: R2DBC en una app servlet (con `.block()`) es un caso valido. Registra `StdlogR2dbcQueryListener` (un `ProxyExecutionListener`) y un `ConnectionFactory` `@Primary` proxeado, igual patron que JDBC.

`StdlogR2dbcQueryListener` emite el mismo evento `CLIENT_DB` que el listener JDBC. Reutiliza toda la configuracion `stdlog.jdbc.*`. Correlacion: `beforeQuery` (hilo que suscribe) copia el MDC al `ValueStore` de la query; `afterQuery` (hilo del event-loop) lo restaura alrededor de la emision. En app servlet + `.block()` la correlacion es completa; en app WebFlux depende de Micrometer context-propagation del consumidor. `StdlogClientDbQueryListener` (JDBC) no se modifico. Ver `ADR-0007`.

### Errores MVC

`StdlogErrorAutoConfiguration` se activa con `stdlog.error.enabled=true` o por omision y registra un `HandlerExceptionResolver`.

El sistema genera un evento adicional para excepciones MVC reales, separado del `CONTROLLER_HTTP OUT`, con `WARN` para status 4xx y `ERROR` para status 5xx. La documentacion de `StdlogProperties` indica que `MethodArgumentNotValidException` no genera evento extra para evitar ruido.

### Eventos Custom

`StdlogCustom` es API publica estatica para emitir eventos de negocio:

- `info`;
- `warn`;
- `debug`;
- `success`;
- `failure`.

Los payloads custom pasan por `StdlogEmitter`, quedan bajo la clave `stdlog` y se enriquecen con correlacion si existe.

## Flujo Principal

1. Spring Boot carga autoconfiguraciones desde `AutoConfiguration.imports`.
2. `StdlogProperties` se enlaza desde `stdlog.*`.
3. `StdlogVersionEnvironmentPostProcessor` expone la version como property usada por Logback.
4. En un request servlet:
   - `RequestIdMdcFilter` prepara `request_id` en MDC;
   - `ControllerBodyAndOutLoggingFilter` marca exclusiones de path, captura tiempos/correlacion y decide si cachea bodies;
   - `StdlogMvcOperationInterceptor` resuelve `operation`, route/patron MVC y `StdlogExcluded`;
   - codigo del consumidor ejecuta controller, JDBC, HTTP saliente y eventos custom;
   - al terminar la cadena, el filtro emite `CONTROLLER_HTTP IN`, `CONTROLLER_HTTP OUT` y, si aplica, evento extra de error;
   - se limpia la marca de exclusion del MDC y se copia el body cacheado de respuesta.
5. En llamadas HTTP salientes, el interceptor captura MDC, call id opcional, source opcional, ejecuta la llamada y emite `CLIENT_HTTP` si la politica lo permite.
6. En queries JDBC, el datasource proxy llama a `StdlogClientDbQueryListener.afterQuery`, que emite `CLIENT_DB` si la politica lo permite.
7. Todo evento emitido por la libreria pasa por `StdlogEmitter`, que agrega tracing desde MDC u OpenTelemetry (`StdlogTraceCorrelation`) y usa logger SLF4J `stdlog`.

## Principios Arquitectonicos Vigentes

- **El esquema emitido es un contrato: ningun cambio puede dejar de emitir un campo que ya emitia.**
  `operation`, `route`, `request_id` y la correlacion de tracing son el nucleo del valor de la
  libreria. Un cambio que mejora un aspecto a costa de degradar uno de esos campos **no es
  aceptable**: o se encuentra otra forma, o no se hace. Corolarios:
  - antes de aceptar un supuesto intercambio («ganamos X pero perdemos Y»), hay que **medirlo**;
    en `ADR-0012` el intercambio que el informe de auditoria daba por hecho resulto ser inexistente;
  - capturar un fallo y no decir nada **tambien es perder datos**, solo que sin avisar (ver `ADR-0011`);
  - añadir un campo o un evento donde antes no habia nada no viola el principio: solo lo viola quitar.
- El starter evita dependencias fuertes innecesarias: Spring MVC y servlet son `provided`; OpenTelemetry se consulta por reflexion.
- El contrato de configuracion esta centralizado en `StdlogProperties`.
- La emision esta centralizada en `StdlogEmitter`; los modulos construyen payloads y delegan el logging final.
- La correlacion transversal usa MDC y se enriquece lo mas tarde posible, dentro del emitter.
- Las politicas anti-ruido dependen de `StdlogModeResolver` y se aplican por modulo antes de emitir.
- Cambios en `StdlogProperties`, `StdlogCustom`, `StdlogExcluded`, archivos `META-INF/spring/*` o `stdlog/logback-spring-stdlog.xml` deben tratarse como cambios de contrato publico.
- Todo evento pasa por `StdlogEmitter`, que aplica en ese unico punto el enmascarado de datos sensibles (`ADR-0010`). Ningun modulo enmascara por su cuenta: asi un modulo nuevo lo hereda sin hacer nada.
- **Un fallo del logging nunca altera el resultado de la operacion instrumentada, y nunca ocurre en silencio** (`ADR-0011`). Se aplica en dos capas: `StdlogEmitter` envuelve la emision —lo que cubre a cualquier modulo por construccion— y ademas cada punto de instrumentacion envuelve su bloque de construccion del payload, porque el emitter recibe el payload ya construido y su red no llega ahi. Los fallos se registran en el logger `appbrain.stdlog.internal`, nunca en `stdlog`, y con freno por potencias de diez.
- Cambios en autoconfiguraciones pueden afectar aplicaciones consumidoras por registrar filtros, interceptores, customizers o reemplazar `DataSource` con un bean `@Primary`.

## Decisiones Tecnicas Actuales

- `main` apunta a Spring Boot 4.1.0, bytecode Java 17, build en JDK 17-25. La linea Spring Boot 3 vive en `spring-boot-3.x` (ver "Modelo de Ramas" y `ADR-0005`).
- La entrada HTTP del starter cubre servlet/MVC y, desde `ADR-0008`, WebFlux (`StdlogWebFilter`, `@ConditionalOnWebApplication(REACTIVE)`, sin tocar la via servlet). Los dos stacks son mutuamente excluyentes en runtime. Los clientes salientes cubren stacks bloqueantes y reactivos (`WebClient`, R2DBC).
- `ADR-0008` estado de fases: **todas hechas**.
  - **Fase 1**: `StdlogWebFilter` (`CONTROLLER_HTTP` + evento de error + `request_id`/exclusion en el Reactor Context; captura de body reactiva).
  - **Fase 2**: `StdlogWebClientExchangeFilter` lee el Reactor Context (`CLIENT_HTTP` correlacionado en apps WebFlux sin necesidad de context-propagation) y resuelve `operation` desde el `ServerWebExchange` que `StdlogWebFilter` pone en el Context. `StdlogWebClientAutoConfiguration` ya no esta gated a `SERVLET`. `StdlogReactorContext` movido a `core`. `StdlogReactorContextPropagationAutoConfiguration` registra un `ThreadLocalAccessor` de `request_id` -> para R2DBC en WebFlux, el `request_id` llega al MDC (y por tanto a `CLIENT_DB`) si el consumidor activa `Hooks.enableAutomaticContextPropagation()`. El starter no activa el hook. `route` no se propaga (menos util downstream).
  - **Fase 3**: `@StdlogExcluded` en handler methods reactivos (lo resuelve `StdlogWebFilter` del `HandlerMethod` tras la cadena; solo afecta `CONTROLLER_HTTP`/error). `StdlogWebExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`) guarda la excepcion real en un atributo del exchange para que el evento de error lleve `type`/`message`/`app_trace` reales. `StdlogCustomReactive` (`appbrain.stdlog.webflux`): variante `Mono<Void>` de `StdlogCustom` que restaura la correlacion del Reactor Context en el MDC; helper compartido `StdlogReactiveCorrelation` en `core`. `StdlogCustom`, `StdlogEmitter` y el listener R2DBC sin cambios.
- Logback/logstash encoder (v9.0) es el mecanismo de salida JSON provisto. **Es tambien el unico**: el payload viaja integro en el `Marker` de logstash y el mensaje es la cadena literal `stdlog`, asi que bajo cualquier otro backend —Log4j2— el evento se pierde entero y en silencio. `ADR-0014` (propuesto) decide detectar el backend y emitir por dos caminos con salida equivalente; su spike verifico que `ObjectMessage` + `JsonTemplateLayout` produce el mismo JSON anidado.
- `stdlog.mode=AUTO` cae a no productivo cuando `STDLOG_MODE` no esta definido.
- `RestTemplate` se envuelve con `BufferingClientHttpRequestFactory` para poder leer bodies.
- `RestClient` recibe el mismo `StdlogClientHttpInterceptor` mediante customizer.
- `WebClient` recibe `StdlogWebClientExchangeFilter` (código nuevo, no toca el interceptor síncrono); comparte el formato del evento vía `StdlogClientHttpPayload`.
- JDBC se instrumenta reemplazando el `DataSource` del consumidor por un proxy `@Primary` (`datasource-proxy`); R2DBC igual, con `@Primary ConnectionFactory` (`r2dbc-proxy`), sin tocar el listener JDBC. Ver `ADR-0007`.
- `TRACE`, `DEBUG` e `INFO` pueden suprimirse por path/anotacion, pero `WARN` y `ERROR` no.
- La version de libreria se expone via environment post processor y se agrega al JSON como `stdlog_lib_version`.
- El binding JSON usa Jackson 3 (`tools.jackson.core:jackson-databind`) tras la migracion desde Jackson 2.

## Tests y Cobertura Funcional

Existen tests bajo `src/test/java` para:

- autoconfiguraciones principales;
- propiedades y resolucion de modo;
- filtro controller y extractores HTTP;
- interceptor MVC y exclusion;
- resolver de excepciones MVC;
- emitter y custom logs (incl. `StdlogCustomReactive` sobre el Reactor Context);
- interceptor HTTP saliente y decoder de body;
- entrada reactiva (`StdlogWebFilter`, `@StdlogExcluded` reactivo), `StdlogWebExceptionHandler`, filtro `WebClient` reactivo, listener R2DBC;
- listener JDBC;
- resolver de caller;
- post-procesador de version.

Suite ejecutada en `main` (`mvn clean test`): 235 tests, 0 fallos, `BUILD SUCCESS`, verificado en JDK 17 y JDK 25.

### Integracion continua (`ADR-0016`)

- `.github/workflows/ci.yml` — matriz JDK 17 y 25 (Temurin) sobre `push` a las dos ramas permanentes y sobre toda `pull_request` dirigida a ellas. Ejecuta `mvn -B clean verify` y publica el informe de JaCoCo como artefacto.
- **Trinquete de cobertura**: `jacoco:check` en la fase `verify` con minimos `BUNDLE` de 85 % de lineas y 65 % de ramas (nivel actual redondeado a la baja: 89 % / 68 %). Impide regresiones; no obliga a subir. `mvn test` no lo ejecuta, asi que el ciclo local no cambia.
- `.github/workflows/parity.yml` — compara `src/` entre `main` y `spring-boot-3.x`. Todo fichero que difiera y no este declarado en `.github/branch-parity-allowlist.txt` hace fallar el job. Estado medido: 63 de 77 ficheros identicos, 13 diferencias declaradas (todas por el major de Spring Boot).
- El job de paridad corre **solo en `push`**, nunca en `pull_request`: segun `ADR-0005` el porte ocurre despues del merge, asi que su rojo sobre `main` significa "falta portar" (el estado que `ADR-0005` llama "no cerrado"), no "algo se rompio". Vuelve a verde con el push del porte.

## Limitaciones Actuales

- La entrada HTTP cubre servlet/MVC y WebFlux (`ADR-0008`, todas las fases); los clientes salientes cubiertos son `RestTemplate`/`RestClient`/`WebClient` (HTTP) y JDBC/R2DBC (base de datos).
- El soporte de HTTP saliente cubre `RestTemplate`, `RestClient` y `WebClient` (este ultimo tambien en apps WebFlux y no-web, `ADR-0006`/`ADR-0008` Fase 2).
- `RestTemplate` queda con request factory buffering, con posible impacto de memoria en bodies grandes.
- El proxy JDBC como `@Primary DataSource` (y el proxy R2DBC como `@Primary ConnectionFactory`) puede interactuar con configuraciones avanzadas del consumidor, multiples datasources/connection factories, pooling o wrapping previo.
- En R2DBC, `db.response` (filas devueltas/afectadas) no se emite: en R2DBC el conteo es best-effort y asincrono. En apps WebFlux, `request_id` en `CLIENT_DB` requiere que el consumidor active `Hooks.enableAutomaticContextPropagation()` (el starter provee el `ThreadLocalAccessor`); `operation` no llega a `CLIENT_DB` en WebFlux.
- Las politicas de exclusion basadas en MDC se propagan solo dentro del mismo thread.
- La captura de source en HTTP saliente usa stacktrace-walk y depende de `consumerBasePackage`; tiene costo de CPU por llamada cuando se habilita.
- El enmascarado de datos sensibles (`ADR-0010`) actua **por nombre de clave**, no por contenido del valor: un campo sensible con un nombre no previsto sigue saliendo, y el consumidor debe anadirlo con `stdlog.masking.additional-keys`. Sobre bodies que llegan como texto la pasada es best-effort declarada (opera sobre el texto, no sobre un arbol, para funcionar tambien con bodies truncados o JSON invalido).
- Los directorios `release` y `target` no estan indexados ni deben tratarse como fuente estructural del codigo.
- Existen los ADR `0001`-`0008` y `0016` (estado `Aceptado`; suite de tests en verde). El resto de decisiones ya implementadas siguen sin ADR formal.
- `ADR-0004` (la documentacion viaja en el mismo commit) **no tiene verificacion automatica**: depende de revision humana. `ADR-0005` si la tiene desde `ADR-0016`.

## Decisiones Pendientes

- Definir si se publica a un repositorio remoto (Maven Central / JitPack) ademas del flujo local `release/`. El esquema de version por linea (`4.x.y` / `3.x.y`) ya esta decidido en `ADR-0005`.
- Definir si el reemplazo `@Primary DataSource` / `@Primary ConnectionFactory` es el contrato definitivo o si debe existir una alternativa menos invasiva.
- Definir politica formal de soporte para multiples datasources / connection factories.
- Definir si el ciclo `StdlogCustom`/`StdlogEmitter` debe aceptarse como patron de fachada estatica o refactorizarse.

## Reglas para Cambios

Antes de modificar contratos publicos o arquitectura:

- identificar implementaciones y consumidores con Codebase Memory;
- revisar callers y dependencias;
- ejecutar `check_index_coverage` sobre archivos y scopes afectados;
- evaluar compatibilidad hacia atras;
- preferir cambios aditivos cuando sea razonable;
- actualizar pruebas;
- revisar si el cambio requiere actualizar este archivo;
- crear un ADR cuando corresponda.

## Reglas para Uso de Codebase Memory

Usar primero las herramientas MCP directas de `codebase-memory-mcp`.

Preferir el grafo para descubrir:

- estructura;
- modulos;
- clases;
- interfaces;
- callers;
- implementaciones;
- imports;
- dependencias;
- relaciones;
- impacto potencial de cambios.

Evitar como primera opcion:

- busquedas masivas con `grep`;
- lectura indiscriminada de archivos;
- exploracion manual extensiva;
- uso del CLI cuando el MCP directo este disponible.

## Politica de Frescura de Codebase Memory

Antes de usar el grafo para decisiones importantes:

1. Consultar `check_index_coverage` sobre los archivos o modulos afectados.
2. Si la frescura es `metadata_match`, usar el grafo normalmente con caveat best-effort.
3. Si la frescura es `metadata_changed`, `missing` o `not_tracked`:
   - esperar primero a `auto_watch`;
   - volver a comprobar la frescura;
   - reindexar manualmente solo si sigue desactualizado.
4. Evitar reindexados completos innecesarios.

## Mantenimiento del Contexto Compartido

Antes de cerrar una tarea que cambie arquitectura, contratos publicos, dependencias importantes o responsabilidades:

1. Comparar el estado final del codigo con este archivo.
2. Usar Codebase Memory para detectar drift.
3. Reportar contradicciones o documentacion desactualizada.
4. Determinar si corresponde:
   - actualizar este archivo;
   - crear un ADR;
   - sustituir un ADR existente.

## Cuando Crear un ADR

Crear un ADR cuando una decision:

- cambie limites entre modulos;
- introduzca o elimine dependencias tecnologicas importantes;
- modifique contratos publicos o responsabilidades arquitectonicas;
- reemplace una decision previa;
- tenga impacto importante en seguridad, observabilidad, compatibilidad o despliegue;
- tenga varias alternativas razonables;
- sea probable que vuelva a discutirse en el futuro.

No crear ADR para:

- bugs sin impacto arquitectonico;
- refactors internos;
- cambios cosmeticos;
- tareas temporales;
- decisiones facilmente reversibles y de bajo impacto.

## Reglas de Documentacion

Usar:

- `AI_CONTEXT.md` para contexto vigente;
- `docs/adr/` para decisiones arquitectonicas;
- documentacion adicional como contexto complementario.

No guardar aqui:

- tareas temporales;
- prompts;
- debugging transitorio;
- decisiones no confirmadas como si fueran definitivas.

## Fuente de Verdad

La prioridad se interpreta asi: el codigo actual y Codebase Memory describen el estado real de la implementacion; `AI_CONTEXT.md` es la guia canonica compartida para agentes sobre como entender y mantener ese estado. Si difieren, reportar la contradiccion antes de asumir.

Prioridad operativa:

1. Codigo actual y Codebase Memory para el estado real de implementacion.
2. `AI_CONTEXT.md` para contexto compartido vigente.
3. ADRs para decisiones arquitectonicas.
4. Documentacion adicional como contexto complementario.

Si existe contradiccion entre estas fuentes, reportarla antes de modificar el sistema.

## Decisiones Arquitectonicas Candidatas a ADR

### Ya promovidas a ADR

- Migracion a Spring Boot 4.1.0 y Jackson 3, con Java 17 como `release` minimo -> **ADR-0001**.
- Estrategia de correlacion de tracing: MDC primero y OpenTelemetry opcional por reflexion -> **ADR-0002**.
- Estrategia de logging JSON basada en Logback/logstash encoder y archivo `logback-spring-stdlog.xml` provisto por el starter -> **ADR-0003**.
- La documentacion afectada se actualiza en el mismo commit/PR que el cambio -> **ADR-0004**.
- Dos lineas de mantenimiento (`main` Boot 4, `spring-boot-3.x` Boot 3) con paridad funcional -> **ADR-0005**.
- Logging del evento `CLIENT_HTTP` para `WebClient` (cliente saliente reactivo) -> **ADR-0006**.
- Logging del evento `CLIENT_DB` para R2DBC (base de datos reactiva) -> **ADR-0007**.
- Soporte de aplicaciones WebFlux (entrada HTTP reactiva) -> **ADR-0008** (alcance decidido; implementacion por fases).

### Pendientes

- Estrategia de instrumentacion de base de datos: reemplazo `@Primary DataSource` / `@Primary ConnectionFactory` y politica para multiples datasources/connection factories.
- Politica de modo `AUTO` y default a `NON_PROD` cuando `STDLOG_MODE` no esta definido.
- Politica de exclusion: suprimir solo `TRACE/DEBUG/INFO` y nunca `WARN/ERROR`.
- Contrato de instrumentacion HTTP saliente para `RestTemplate`/`RestClient`, incluyendo buffering en `RestTemplate` (la vía `WebClient` está en `ADR-0006`).
- Politica de seguridad para bodies, headers y parametros SQL.
- Publicacion a un repositorio remoto (Maven Central / JitPack) ademas del flujo local `release/`.
