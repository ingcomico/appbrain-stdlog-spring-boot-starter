# ADR-0003: Salida JSON estructurada vía Logback + logstash-logback-encoder, con configuración provista por el starter

> **Nota de rama (`spring-boot-3.x`):** la decisión de fondo aplica igual. Esta rama usa `logstash-logback-encoder` **8.1** (no 9.0) y `logback-spring-stdlog.xml` conserva `globalCustomFields` + el bloque `stackTrace`; la forma del JSON emitido es la misma que en `main` (verificado: salida equivalente entre encoder 8.1 y 9.0).

## Estado

Aceptado

## Contexto

- El starter emite eventos de negocio y de observabilidad a través del logger SLF4J `stdlog`. Necesita una forma de serializar esos eventos a JSON de una línea, apta para ingestión por un stack de logs centralizado.
- Históricamente el starter resuelve esto de dos formas combinadas:
  1. Los payloads se construyen como `Map<String,Object>` y se adjuntan como marker estructurado (`net.logstash.logback.marker`) en `StdlogEmitter`.
  2. Se provee un archivo Logback listo para usar en `classpath:stdlog/logback-spring-stdlog.xml`, con un appender de consola que usa `LoggingEventCompositeJsonEncoder` de `logstash-logback-encoder`.
- La migración a Spring Boot 4 (ADR-0001) obliga a subir `logstash-logback-encoder` de `8.1` a `9.0` (la 9.x requiere Java 17, migra internamente a Jackson 3 y es la línea compatible con el stack de Boot 4). El commit `d3b61f3 fix: update logback config for logstash encoder 9` reescribió `logback-spring-stdlog.xml`, pero **se verificó que ese ajuste es cosmético**: la config previa (`globalCustomFields` + `<nestedField>` en `stackTrace`) también arranca sin error sobre el encoder 9.0 y produce un JSON de estructura idéntica (ver Validación).
- No existía ADR que documentara por qué el starter acopla la salida a Logback + logstash-encoder y por qué envía un archivo de configuración, en lugar de dejarlo al consumidor. `AI_CONTEXT.md` lo lista como candidato: "Estrategia de logging JSON basada en Logback/logstash encoder y archivo `logback-spring-stdlog.xml` provisto por el starter".

## Alternativas Consideradas

### Alternativa 1 — Logback + logstash-logback-encoder, con `logback-spring-stdlog.xml` incluido en el starter (estado actual)

Ventajas:

- Logback es el backend SLF4J por defecto de Spring Boot; cero fricción para la mayoría de consumidores.
- `logstash-logback-encoder` ya resuelve markers estructurados, shortening de stack traces y campos custom.
- El archivo incluido da una salida correcta "de fábrica"; el consumidor solo hace `<include resource="stdlog/logback-spring-stdlog.xml"/>`.

Desventajas:

- Acopla el starter a Logback: consumidores con Log4j2 u otro backend no obtienen la salida JSON sin reescribir la config.
- El archivo provisto impone decisiones (appender de consola, nivel `INFO`, nombres de campos) que el consumidor podría querer distintas.
- Cada major del encoder puede requerir tocar el archivo (como ocurrió con la 9.x).

### Alternativa 2 — Serializar el JSON dentro del starter (Jackson) y loguear un String plano

Ventajas:

- Independiente del backend de logging y de su versión.

Desventajas:

- Reimplementa lo que el encoder ya hace (escape, shortening de stacktrace, merge de campos).
- Pierde integración con el pipeline de providers de Logback del consumidor (MDC, markers de terceros).

### Alternativa 3 — No incluir archivo; solo documentar la configuración recomendada

Ventajas:

- El consumidor tiene control total; el starter no se rompe por cambios del encoder.

Desventajas:

- Mala experiencia inicial; alta probabilidad de configuraciones incompletas o inconsistentes entre servicios.

## Decisión

Se mantiene y se formaliza la **Alternativa 1**.

Reglas derivadas:

- El starter provee `src/main/resources/stdlog/logback-spring-stdlog.xml` como recurso público, pensado para `<include>` desde el `logback-spring.xml` del consumidor.
- El backend soportado oficialmente para la salida JSON es **Logback + `net.logstash.logback:logstash-logback-encoder`**. La versión objetivo es **9.0** (alineada con Spring Boot 4 / Java 17). Este archivo y este par de dependencias se tratan como **contrato público** (`AI_CONTEXT.md`, "Principios Arquitectónicos Vigentes").
- Forma de la configuración en `logback-spring-stdlog.xml` (tras el cleanup de `d3b61f3`):
  - El campo `stdlog_lib_version` se inyecta mediante el provider `pattern` (`{"stdlog_lib_version":"${STDLOG_LIB_VERSION}"}`), alimentado por `springProperty` desde `stdlog.libVersion` con `defaultValue="unknown"`. El provider `globalCustomFields` anterior seguía funcionando en el encoder 9.0 y producía el mismo campo; el cambio a `pattern` es preferencia de estilo, no un requisito.
  - El provider `stackTrace` escribe el campo en la raíz del evento (`fieldName = stack_trace`) con `ShortenedThrowableConverter` (`maxDepthPerThrowable=200`, `maxLength=30000`, `rootCauseFirst=true`). Se eliminó la línea `<nestedField>stdlog.error</nestedField>`: `StackTraceJsonProvider` no tiene esa propiedad, por lo que **siempre fue configuración muerta y `stack_trace` siempre estuvo en la raíz**. Eliminarla no cambia la salida.
  - Se conservan los providers `logLevel`, `timestamp`, `logstashMarkers` (transporta el payload `stdlog`) y `message`.
  - El logger `stdlog` queda con `level=INFO`, `additivity=false` y el appender `JSON`; el `root` también usa el appender `JSON` a `INFO`.
- El `stdlog_lib_version` proviene de `StdlogVersionEnvironmentPostProcessor`, que expone `stdlog.libVersion` desde `stdlog-version.properties`.

## Consecuencias

### Positivas

- Salida JSON consistente entre servicios que usan el starter, con mínima configuración.
- El shortening de stack traces y el merge de markers los maneja una librería probada.
- El campo `stack_trace` en la raíz es más simple de mapear en el pipeline de ingestión que un campo anidado.

### Negativas

- El starter sigue acoplado a Logback; consumidores con otro backend (Log4j2, etc.) quedan fuera del soporte de salida JSON y deben replicar la configuración manualmente.
- El archivo provisto impone decisiones (appender de consola, nivel `INFO`, nombres de campos) que el consumidor podría querer distintas.
- Futuras majors de `logstash-logback-encoder` pueden requerir nuevos ajustes del archivo y una nueva revisión de este ADR.

### Riesgos

- Si `${STDLOG_LIB_VERSION}` no se resuelve (fallo del `EnvironmentPostProcessor`), el campo `stdlog_lib_version` queda con el `defaultValue` `unknown`. Verificado: con la property resuelta el valor sale correcto.
- El encoder 9.x cambió su API interna de Jackson 2 a 3 (`JsonFactoryAware` → `ObjectMapperAware`); providers de terceros compilados contra el encoder 8.x podrían no cargar.

## Impacto

- **Módulos afectados:** recursos (`stdlog/logback-spring-stdlog.xml`), indirectamente `autoconfig` (`StdlogVersionEnvironmentPostProcessor` alimenta `stdlog.libVersion`).
- **Contratos públicos:** `logback-spring-stdlog.xml` (recurso incluido), el par de dependencias Logback + logstash-encoder, y la forma del JSON emitido (campo `stack_trace` reubicado).
- **Dependencias:** `logstash-logback-encoder` 8.1 → 9.0.
- **Compatibilidad:** sin ruptura en la forma del JSON emitido (verificado: salida idéntica entre la config previa y la actual sobre el encoder 9.0).
- **Observabilidad:** el contenido y la estructura de los eventos no cambian.
- **Seguridad:** sin cambios; el shortening del stack trace limita el tamaño pero no filtra datos sensibles del payload (responsabilidad del consumidor, ya documentada en `AI_CONTEXT.md`).
- **Despliegue:** los consumidores que sobreescriban el archivo o mapeen campos deben revisar el cambio de `stack_trace`.
- **Migraciones necesarias:** ninguna.

## Validación

- `AI_CONTEXT.md` revisado: "Recursos Públicos" y "Decisiones Técnicas Actuales" ya citan `logback-spring-stdlog.xml`, el encoder 9.0 y el campo `stdlog_lib_version`.
- Estado real del código: `src/main/resources/stdlog/logback-spring-stdlog.xml` en la rama usa el provider `pattern` para `stdlog_lib_version` y `stackTrace` sin `nestedField`; `pom.xml` declara `logstash-logback-encoder` 9.0.
- Salida JSON verificada (2026-09-02) ejecutando el appender real con el encoder 9.0. Un evento de error produce:
  `{"level":"ERROR","@timestamp":"...","stdlog":{"event":"CONTROLLER_HTTP","request_id":"r1"},"message":"stdlog","stdlog_lib_version":"1.2.3","stack_trace":"..."}`.
  La config previa de `main` (`globalCustomFields` + `<nestedField>`) sobre el mismo encoder 9.0 produce una estructura byte-a-byte equivalente. `stack_trace` y `stdlog_lib_version` siempre están en la raíz.
- Inspección de `StackTraceJsonProvider` (encoder 8.1 y 9.0): sólo expone `fieldName` y `throwableConverter`; no existe `setNestedField`, luego `<nestedField>` nunca tuvo efecto.
- Suite de tests: 177 tests, 0 fallos (JDK 17).

## Relación con Otros ADR

- Relacionado con: `ADR-0001` (el bump del encoder a 9.0 es consecuencia de la migración a Spring Boot 4), `ADR-0002` (los campos `trace_id`/`span_id` se emiten a través de esta misma salida).
- Sustituye: nada.
