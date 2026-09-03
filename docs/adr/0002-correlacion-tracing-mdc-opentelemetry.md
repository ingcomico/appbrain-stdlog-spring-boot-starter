# ADR-0002: Estrategia de correlación de tracing (MDC primero, OpenTelemetry por reflexión)

> **Nota de rama (`spring-boot-3.x`):** la decisión aplica igual. `StdlogTraceCorrelation` es byte a byte idéntica a la de `main` (sólo usa `org.slf4j.MDC`, reflexión y `record`). Las menciones a la migración Boot 4 son contexto histórico; aquí llegó por backport.

## Estado

Aceptado

## Contexto

- Los eventos que emite el starter (`CONTROLLER_HTTP`, `CLIENT_HTTP`, `CLIENT_DB`, error MVC, eventos custom) no incluían identificadores de traza distribuida. En un entorno con tracing, los logs `stdlog` no se podían correlacionar con spans ni entre servicios.
- El starter mantiene un principio explícito (`AI_CONTEXT.md`, "Principios Arquitectónicos Vigentes"): evitar dependencias fuertes innecesarias; Spring MVC y servlet son `provided`, y las integraciones opcionales se consultan de forma defensiva.
- El ecosistema de tracing en aplicaciones Spring es heterogéneo: unas usan Micrometer Tracing (que publica `traceId`/`spanId` en MDC), otras el SDK de OpenTelemetry directamente, otras un agente que instrumenta sin tocar el MDC de la aplicación.
- La emisión de todos los eventos ya está centralizada en `StdlogEmitter`, lo que da un único punto para enriquecer el payload lo más tarde posible.
- `AI_CONTEXT.md` ya listaba esto como candidato a ADR: "Estrategia de correlación: MDC primero y OpenTelemetry opcional por reflexión".

## Alternativas Consideradas

### Alternativa 1 — MDC primero, con fallback a OpenTelemetry API por reflexión, enriquecido en el emitter

`StdlogTraceCorrelation.current()` lee `traceId`/`spanId` del MDC; si no hay, intenta `io.opentelemetry.api.trace.Span.current()` vía reflexión. `StdlogEmitter` llama a `StdlogTraceCorrelation.enrich(...)` antes de loguear y añade `trace_id`/`span_id` si faltan.

Ventajas:

- Cero dependencias nuevas en el `pom.xml`; funciona sin OpenTelemetry en el classpath.
- Cubre el caso más común (Micrometer Tracing / cualquier setup que publique en MDC) sin reflexión.
- Cubre el caso del SDK OTel sin propagación a MDC mediante el fallback.
- Un solo punto de enriquecimiento; los módulos no cambian.

Desventajas:

- La reflexión es frágil ante cambios de API de OpenTelemetry y tiene coste (mitigable, se ejecuta solo si el MDC está vacío).
- No cubre backends de tracing que no sean ni MDC ni OTel API (por ejemplo Brave/Zipkin directo sin bridge).

### Alternativa 2 — Dependencia directa (compile/optional) a `opentelemetry-api`

Ventajas:

- Código type-safe, sin reflexión.

Desventajas:

- Aunque sea `optional`, empuja una versión concreta de la API a los consumidores y crea acoplamiento de versión.
- Contradice el principio de dependencias mínimas del starter.

### Alternativa 3 — Dependencia a Micrometer Tracing (`Tracer`)

Ventajas:

- Abstracción neutral sobre varios backends.

Desventajas:

- Micrometer Tracing no siempre está presente; añadirlo como dependencia fuerte es invasivo y como opcional obliga igualmente a resolver por reflexión o `@ConditionalOnClass`.

### Alternativa 4 — Solo MDC

El consumidor es responsable de poblar `traceId`/`spanId` en el MDC.

Ventajas:

- Implementación trivial, sin reflexión.

Desventajas:

- No funciona con agentes/SDK OTel que no tocan el MDC de la aplicación; deja un hueco de correlación silencioso.

## Decisión

Se adopta la **Alternativa 1**.

Reglas derivadas:

- Nueva clase `appbrain.stdlog.core.StdlogTraceCorrelation` (utilería estática, sin estado):
  - `current()` devuelve `TraceIds(traceId, spanId)` leyendo primero MDC (`traceId`, `spanId`); si ambos están en blanco, intenta OpenTelemetry (`Span.current().getSpanContext()`, comprobando `isValid()`) por reflexión. Cualquier `Throwable` en esa vía se traga y devuelve `TraceIds.empty()`.
  - `enrich(stdlog)` añade `trace_id` y/o `span_id` al mapa **solo si faltan o están en blanco**; nunca sobrescribe valores ya presentes. Devuelve el mismo mapa si no hay nada que añadir; si añade, devuelve una copia (`LinkedHashMap`).
- `StdlogEmitter.emit(...)` (ambas sobrecargas, con y sin `Throwable`) invoca `StdlogTraceCorrelation.enrich(stdlog)` antes de delegar en SLF4J.
- Los campos publicados en el JSON, bajo la clave `stdlog`, son `trace_id` y `span_id`. Si no hay contexto de tracing activo, se omiten.
- En el flujo servlet, `ControllerBodyAndOutLoggingFilter` captura la correlación de forma anticipada en atributos de request (`StdlogAttrs.TRACE_ID`, `StdlogAttrs.SPAN_ID`), tanto antes de `chain.doFilter` como en el `finally`, para no perder los ids cuando el span se cierra antes de que el filtro emita los eventos `IN`/`OUT`/error. El builder de payload del filtro usa esos atributos.
- `StdlogAttrs` expone las constantes `TRACE_ID = "stdlog.traceId"` y `SPAN_ID = "stdlog.spanId"`.
- No se añade ninguna dependencia al `pom.xml` por esta decisión.

## Consecuencias

### Positivas

- Correlación automática de todos los eventos `stdlog` con la traza distribuida cuando hay tracing activo.
- Sin coste de dependencias ni de configuración para el consumidor.
- Compatible con Micrometer Tracing y con el SDK de OpenTelemetry sin cambios de código del consumidor.

### Negativas

- La rama de reflexión OTel puede romperse si OpenTelemetry cambia la firma de `Span.current()` / `SpanContext`; el fallo es silencioso (se omiten los campos), lo que puede dificultar el diagnóstico.
- `enrich` puede crear una copia del mapa por evento cuando añade campos (coste de memoria menor, una vez por evento emitido).

### Riesgos

- La captura en el filtro y el enriquecimiento en el emitter pueden divergir si una futura ruta de emisión no pasa por `StdlogEmitter`.
- Un consumidor que ya ponga `trace_id`/`span_id` con otro significado en el payload vería su valor respetado (no se sobrescribe), pero podría chocar semánticamente.
- El nombre de las keys de MDC (`traceId`/`spanId`) es el de Micrometer Tracing por defecto; setups con otras keys no se detectan por la vía MDC.

## Impacto

- **Módulos afectados:** `core` (nueva clase `StdlogTraceCorrelation`, cambio en `StdlogEmitter`), `web` (`ControllerBodyAndOutLoggingFilter`, `StdlogAttrs`).
- **Contratos públicos:** se añaden los campos `trace_id` y `span_id` al objeto `stdlog` de todos los eventos (cambio aditivo). `StdlogTraceCorrelation` y las nuevas constantes de `StdlogAttrs` pasan a ser superficie pública del paquete `core`/`web`.
- **Dependencias:** ninguna nueva. OpenTelemetry sigue siendo opcional y consultado por reflexión.
- **Compatibilidad:** aditivo; consumidores que no parsean campos desconocidos no se ven afectados.
- **Observabilidad:** mejora directa; habilita correlación logs↔traces.
- **Seguridad:** `trace_id`/`span_id` no son datos sensibles.
- **Despliegue:** sin cambios.
- **Migraciones necesarias:** ninguna.

## Validación

- `AI_CONTEXT.md` revisado: "Propósito del Proyecto", "Flujo Principal" (punto 7) y "Decisiones Técnicas Actuales" ya mencionan la correlación por MDC/OpenTelemetry.
- `README.md` documenta el comportamiento (sección de introducción y de eventos custom / error MVC).
- Codebase Memory: confirmar que `StdlogEmitter.emit` es el único punto de salida hacia SLF4J y que todos los módulos (`web`, `restclient`, `jdbc`, `StdlogCustom`) delegan en él.
- Estado real del código: `StdlogTraceCorrelation` existe en `src/main/java/appbrain/stdlog/core/`; `StdlogEmitterTest` cubre el enriquecimiento; hay tests nuevos en `StdlogCustomTest`, `StdlogClientDbQueryListenerTest`, `StdlogClientHttpInterceptorTest`, `ControllerBodyAndOutLoggingFilterBehaviorTest`.
- Suite de tests ejecutada (2026-09-02, JDK 17): 177 tests, 0 fallos.

## Relación con Otros ADR

- Relacionado con: `ADR-0001` (se implementó en la misma rama de migración a Spring Boot 4), `ADR-0003` (los campos `trace_id`/`span_id` viajan por la salida JSON descrita ahí).
- Sustituye: nada.
