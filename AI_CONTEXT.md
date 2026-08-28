# Contexto del Proyecto para Agentes de IA

Este archivo es la fuente canonica de contexto compartido para los agentes de IA que trabajan sobre este repositorio.

## Estado de Verificacion

- Este contexto fue construido usando Codebase Memory MCP y el codigo actual.
- Antes de realizar analisis de impacto o cambios arquitectonicos, verificar la frescura del grafo mediante `check_index_coverage`.
- Los directorios generados como `target`, `release`, `.git` e `.idea` no deben considerarse fuente estructural del proyecto.

## Flujo de Trabajo de los Agentes

Antes de realizar cambios arquitectonicos, estructurales o que afecten contratos publicos:

1. Leer este archivo.
2. Usar Codebase Memory para entender la estructura del codigo afectado y sus relaciones.
3. Revisar los ADR relevantes dentro de `docs/adr/`, cuando existan.
4. Determinar el impacto antes de modificar codigo.
5. No duplicar decisiones arquitectonicas en archivos especificos de cada agente.
6. Si existe una contradiccion entre este archivo, el codigo actual y otros documentos, reportarla antes de asumir cual es correcta.

## Proposito del Proyecto

`appbrain-stdlog-spring-boot-starter` es un starter Maven para logging estructurado JSON en aplicaciones Spring Boot servlet.

El proyecto provee:

- logging de requests/responses HTTP entrantes como `CONTROLLER_HTTP`;
- logging de llamadas HTTP salientes de `RestTemplate` y `RestClient` como `CLIENT_HTTP`;
- logging de queries JDBC mediante `datasource-proxy` como `CLIENT_DB`;
- API publica de eventos de negocio mediante `StdlogCustom`;
- evento extra para excepciones MVC con severidad `WARN` o `ERROR` segun status final;
- correlacion de `trace_id` y `span_id` desde MDC o, si esta disponible, OpenTelemetry por reflexion;
- configuracion Logback reusable en `classpath:stdlog/logback-spring-stdlog.xml`.

Consumidores esperados: aplicaciones Spring Boot que quieran emitir logs estructurados bajo una clave `stdlog` usando SLF4J/Logback y configuracion por properties `stdlog.*`.

## Plataforma y Dependencias

- Artefacto Maven: `appbrain:appbrain-stdlog-spring-boot-starter`.
- Version declarada en `pom.xml`: `1.0.0`.
- Java: release 17.
- BOM: Spring Boot `4.1.0`.
- Dependencias relevantes:
  - `spring-boot-autoconfigure`;
  - `spring-boot-restclient`;
  - `spring-webmvc` con scope `provided`;
  - `jakarta.servlet-api` con scope `provided`;
  - `tools.jackson.core:jackson-databind`;
  - `net.ttddyy:datasource-proxy:1.9`;
  - `net.logstash.logback:logstash-logback-encoder:9.0`;
  - `slf4j-api`.
- Publicacion configurada en `distributionManagement` hacia `file://${maven.multiModuleProjectDirectory}/release`.

Hay una inconsistencia documental vigente: el `README.md` menciona coordenadas `1.0.0-local`, mientras `pom.xml` declara `1.0.0`.

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
  - `StdlogModeResolver`;
  - `StdlogTraceCorrelation`.
- `appbrain.stdlog.web`: filtros/interceptores servlet/MVC y extractores HTTP:
  - `RequestIdMdcFilter`;
  - `ControllerBodyAndOutLoggingFilter`;
  - `StdlogMvcOperationInterceptor`;
  - `StdlogExceptionResolver`;
  - `HttpLogExtractors`;
  - `StdlogAttrs`.
- `appbrain.stdlog.restclient`: interceptacion de clientes HTTP salientes:
  - `StdlogClientHttpInterceptor`;
  - `StdlogHttpBodyDecoder`.
- `appbrain.stdlog.jdbc`: listener `datasource-proxy`:
  - `StdlogClientDbQueryListener`.
- `appbrain.stdlog.error`: utilidades de stack trace de aplicacion:
  - `AppTraceUtil`.
- `appbrain.stdlog.util`: resolucion de caller del consumidor:
  - `StdlogCallerResolver`.

### Recursos Publicos

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` registra las cinco autoconfiguraciones.
- `META-INF/spring.factories` registra `StdlogVersionEnvironmentPostProcessor`.
- `META-INF/spring/org.springframework.boot.EnvironmentPostProcessor` registra tambien `StdlogVersionEnvironmentPostProcessor`.
- `stdlog/logback-spring-stdlog.xml` define un appender JSON de consola, logger `stdlog`, root logger y campo `stdlog_lib_version`.
- `stdlog-version.properties` alimenta la version de libreria expuesta como property `stdlog.libVersion`.

## Contratos Publicos

### Configuracion `stdlog.*`

`StdlogProperties` expone el prefijo `stdlog` y cuatro secciones: `controller`, `restclient`, `jdbc` y `error`.

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

`StdlogClientHttpInterceptor` emite un unico evento por llamada saliente despues de la respuesta o excepcion. En modo productivo puede omitir exitos segun `stdlog.restclient.log-only-on-failure-in-prod`.

### JDBC

`StdlogJdbcAutoConfiguration` se activa si:

- `ProxyDataSourceBuilder` esta en classpath;
- existe un bean `DataSource`;
- `stdlog.jdbc.enabled=true` o no se configuro la propiedad.

Registra:

- `QueryExecutionListener` como `StdlogClientDbQueryListener`;
- un `DataSource` `@Primary` construido con `ProxyDataSourceBuilder`.

El listener emite `CLIENT_DB direction=OUT` despues de la query, con tiempo, outcome, flag `slow`, pool, SQL truncado, tipo de sentencia inferido, parametros opcionales y respuesta opcional. En modo productivo puede omitir queries exitosas no lentas segun `stdlog.jdbc.log-only-slow-or-failure-in-prod`.

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
7. Todo evento emitido por la libreria pasa por `StdlogEmitter`, que agrega tracing desde MDC u OpenTelemetry y usa logger SLF4J `stdlog`.

## Principios Arquitectonicos Vigentes

- El starter evita dependencias fuertes innecesarias: Spring MVC y servlet son `provided`; OpenTelemetry se consulta por reflexion.
- El contrato de configuracion esta centralizado en `StdlogProperties`.
- La emision esta centralizada en `StdlogEmitter`; los modulos construyen payloads y delegan el logging final.
- La correlacion transversal usa MDC y se enriquece lo mas tarde posible, dentro del emitter.
- Las politicas anti-ruido dependen de `StdlogModeResolver` y se aplican por modulo antes de emitir.
- Cambios en `StdlogProperties`, `StdlogCustom`, `StdlogExcluded`, archivos `META-INF/spring/*` o `stdlog/logback-spring-stdlog.xml` deben tratarse como cambios de contrato publico.
- Cambios en autoconfiguraciones pueden afectar aplicaciones consumidoras por registrar filtros, interceptores, customizers o reemplazar `DataSource` con un bean `@Primary`.

## Decisiones Tecnicas Actuales

- La linea actual apunta a Spring Boot 4.1.0 y Java 17.
- El starter esta orientado a aplicaciones servlet, no WebFlux.
- Logback/logstash encoder es el mecanismo de salida JSON provisto.
- `stdlog.mode=AUTO` cae a no productivo cuando `STDLOG_MODE` no esta definido.
- `RestTemplate` se envuelve con `BufferingClientHttpRequestFactory` para poder leer bodies.
- `RestClient` recibe el mismo `StdlogClientHttpInterceptor` mediante customizer.
- JDBC se instrumenta reemplazando el `DataSource` del consumidor por un proxy `@Primary`.
- `TRACE`, `DEBUG` e `INFO` pueden suprimirse por path/anotacion, pero `WARN` y `ERROR` no.
- La version de libreria se expone via environment post processor y se agrega al JSON como `stdlog_lib_version`.

## Tests y Cobertura Funcional

Existen tests bajo `src/test/java` para:

- autoconfiguraciones principales;
- propiedades y resolucion de modo;
- filtro controller y extractores HTTP;
- interceptor MVC y exclusion;
- resolver de excepciones MVC;
- emitter y custom logs;
- interceptor HTTP saliente y decoder de body;
- listener JDBC;
- resolver de caller;
- post-procesador de version.

No se ejecuto la suite durante esta actualizacion de contexto.

## Limitaciones Actuales

- El starter esta acotado a stack servlet/MVC; no hay evidencia de soporte WebFlux.
- El soporte de HTTP saliente se limita a `RestTemplate` y `RestClient`; no hay evidencia de WebClient.
- `RestTemplate` queda con request factory buffering, con posible impacto de memoria en bodies grandes.
- El proxy JDBC como `@Primary DataSource` puede interactuar con configuraciones avanzadas del consumidor, multiples datasources o wrapping previo.
- Las politicas de exclusion basadas en MDC se propagan solo dentro del mismo thread.
- La captura de source en HTTP saliente usa stacktrace-walk y depende de `consumerBasePackage`; tiene costo de CPU por llamada cuando se habilita.
- Bodies, headers y parametros SQL pueden contener datos sensibles; la configuracion segura depende del consumidor.
- Los directorios `release` y `target` no estan indexados ni deben tratarse como fuente estructural del codigo.
- No hay ADRs vigentes que documenten formalmente las decisiones arquitectonicas ya implementadas.

## Decisiones Pendientes

- Resolver la version publica del artefacto: `pom.xml` declara `1.0.0`, mientras `README.md` documenta `1.0.0-local`.
- Definir politica de mantenimiento/compatibilidad entre la linea Spring Boot 3 y la rama actual de migracion a Spring Boot 4.
- Decidir si el doble registro de `StdlogVersionEnvironmentPostProcessor` en `spring.factories` y `META-INF/spring/org.springframework.boot.EnvironmentPostProcessor` es intencional para compatibilidad o debe simplificarse.
- Definir si el reemplazo `@Primary DataSource` es el contrato definitivo para JDBC o si debe existir una alternativa menos invasiva.
- Definir politica formal de soporte para multiples datasources.
- Definir si se soportara WebFlux/WebClient o si el alcance queda explicitamente limitado a servlet/MVC, `RestTemplate` y `RestClient`.
- Definir criterios de seguridad por defecto para headers, bodies y parametros sensibles.
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

Prioridad:

1. Codigo actual y Codebase Memory para el estado real de implementacion.
2. `AI_CONTEXT.md` para contexto compartido vigente.
3. ADRs para decisiones arquitectonicas.
4. Documentacion adicional como contexto complementario.

Si existe contradiccion entre estas fuentes, reportarla antes de modificar el sistema.

## Decisiones Arquitectonicas Candidatas a ADR

- Alcance oficial del starter: servlet/MVC solamente vs soporte futuro WebFlux/WebClient.
- Estrategia JDBC: reemplazo `@Primary DataSource` con `datasource-proxy` y politica para multiples datasources.
- Estrategia de logging JSON basada en Logback/logstash encoder y archivo `logback-spring-stdlog.xml` provisto por el starter.
- Politica de modo `AUTO` y default a `NON_PROD` cuando `STDLOG_MODE` no esta definido.
- Politica de exclusion: suprimir solo `TRACE/DEBUG/INFO` y nunca `WARN/ERROR`.
- Contrato de instrumentacion HTTP saliente para `RestTemplate`/`RestClient`, incluyendo buffering en `RestTemplate`.
- Estrategia de correlacion: MDC primero y OpenTelemetry opcional por reflexion.
- Politica de seguridad para bodies, headers y parametros SQL.
- Politica de versionado/publicacion del artefacto durante la migracion Spring Boot 4.
