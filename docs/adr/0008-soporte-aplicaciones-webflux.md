# ADR-0008: Soporte de aplicaciones WebFlux (entrada HTTP reactiva) como stack de primera clase

## Estado

Aceptado

> El **alcance** (soportar apps WebFlux) queda decidido con este ADR. La implementación se
> hace **por fases** (ver "Plan de fases"); cada fase es una PR revisable y puede refinar
> detalles de diseño.
>
> **Estado de fases:** Fase 1 **hecha** (`StdlogWebFilter`). Fase 2 **hecha en parte** (WebClient lee el Reactor Context; `StdlogWebClientAutoConfiguration` abierta a cualquier tipo de app). Pendiente en Fase 2: correlación de R2DBC en apps WebFlux (bloqueada por r2dbc-proxy, ver abajo). Fase 3 pendiente.

## Contexto

- El starter instrumenta la **entrada HTTP** sólo para aplicaciones servlet/MVC: `RequestIdMdcFilter`, `ControllerBodyAndOutLoggingFilter`, `StdlogMvcOperationInterceptor` y `StdlogExceptionResolver` son servlet/Spring MVC, gated por `@ConditionalOnWebApplication(SERVLET)` (o por dependencias de `spring-webmvc`).
- `ADR-0006` (WebClient) y `ADR-0007` (R2DBC) cubrieron **clientes salientes reactivos**, pero explícitamente **no** el stack de entrada. Su correlación (`request_id`, `operation`) en una app WebFlux pura depende hoy de que el consumidor active Micrometer context-propagation, porque **nada en el starter puebla esos valores** en una app reactiva.
- En una aplicación **WebFlux pura** hoy, con el starter (+ ADR-0006/0007):
  - **no se emite `CONTROLLER_HTTP`** (entrada/salida de los propios endpoints);
  - **no se emite el evento extra de error** (WARN 4xx / ERROR 5xx) — las excepciones de los controllers reactivos y las respuestas 4xx/5xx son **invisibles**;
  - **no se genera `request_id`** ni se resuelve `operation`/`route`;
  - **no funciona la exclusión** (`@StdlogExcluded`, `excluded-path-patterns`) — no se puede silenciar `/actuator/**`, health checks, etc.;
  - `CLIENT_DB` / `CLIENT_HTTP` se emiten (ADR-0006/0007) pero **huérfanos**, sin el request que los originó.
- `AI_CONTEXT.md` tiene esto como candidato a ADR: "Alcance oficial del starter para aplicaciones WebFlux completas / entrada reactiva".

## Alternativas Consideradas

### Alternativa 1 — Mantener servlet-only para la entrada; documentar el límite

Ventajas:

- Cero trabajo; sin superficie nueva que mantener.

Desventajas:

- Deja el hueco descrito arriba. Los `CLIENT_DB`/`CLIENT_HTTP` reactivos que ya añadimos quedan a medias (sin correlación ni contexto de request).
- Si hay demanda real de apps WebFlux, sólo se pospone.

### Alternativa 2 — Soporte de entrada WebFlux en un paquete nuevo `appbrain.stdlog.webflux`, `@ConditionalOnWebApplication(REACTIVE)`, sin tocar el código servlet, por fases

Ventajas:

- **Los dos stacks son mutuamente excluyentes en runtime** (`SERVLET` vs `REACTIVE`) → el código servlet no se modifica y su comportamiento no cambia. La suite servlet (205 tests) es la prueba viva de no-regresión en cada PR.
- Paridad de eventos entre servlet y WebFlux (mismo `CONTROLLER_HTTP`, mismo evento de error, misma `stdlog.controller.*` / `stdlog.error.*`).
- Completa la correlación de `ADR-0006`/`ADR-0007` en apps reactivas.

Desventajas:

- Duplica la superficie de la capa de entrada web (un `WebFilter` que hace lo de tres componentes servlet juntos, pero reactivo).
- Doble mantenimiento (dos stacks, dos ramas).
- El starter deja de ser "servlet-only" — cambia su descripción y su matriz de test.

### Alternativa 3 — Reescribir el `core` para ser agnóstico del stack de entrada

Desventajas:

- El `core` ya es casi agnóstico (`StdlogEmitter`, builders de payload). Lo único stack-específico es **de dónde sale la correlación** (MDC en servlet). Reescribir es desproporcionado frente a añadir un adaptador reactivo.

## Decisión

Se adopta la **Alternativa 2**: **el starter soporta aplicaciones WebFlux como stack de entrada HTTP de primera clase**, implementado por fases en un paquete nuevo `appbrain.stdlog.webflux`.

### Principios

- **Nuevo paquete `appbrain.stdlog.webflux`** con toda la lógica reactiva de entrada. **`appbrain.stdlog.web` (servlet) NO se modifica.**
- **`StdlogWebFluxAutoConfiguration`** — `@ConditionalOnWebApplication(type = REACTIVE)` + `@ConditionalOnClass(org.springframework.web.server.WebFilter)`. No puede co-activar con las auto-configs servlet.
- **Columna de correlación en reactivo = Reactor Context.** El `WebFilter` escribe `request_id`, `operation`, `route` y el marcador de exclusión en el `Context` de Reactor (`contextWrite`). Los puntos de emisión reactivos (`StdlogWebClientExchangeFilter`, `StdlogR2dbcQueryListener`) leen el `ContextView` como fuente primaria en reactivo (hoy: MDC-first; cambio **aditivo**).
- **Restauración de ThreadLocals/MDC entre hilos reactivos** = Micrometer context-propagation. El starter registra los `ThreadLocalAccessor` necesarios (o usa las keys MDC estándar que `Slf4jThreadLocalAccessor` ya maneja) cuando `io.micrometer:context-propagation` está en classpath. **El starter NO llama `Hooks.enableAutomaticContextPropagation()`** — eso es un switch global que decide la aplicación; se documenta como requisito para correlación completa en pipelines 100% reactivos.
- **`StdlogEmitter` y `StdlogTraceCorrelation` no se modifican**, o sólo de forma aditiva (`StdlogTraceCorrelation.enrich(Map, ContextView)` como overload; el existente intacto).
- **Builders de payload compartidos** (`CONTROLLER_HTTP`, evento de error): extraer a un helper compartido o duplicar en el filtro reactivo — misma política que `ADR-0006` (se decide en cada PR; la opción conservadora es duplicar y no tocar el filtro servlet).
- **Configuración**: se reutiliza `stdlog.controller.*` y `stdlog.error.*`. Se añade `stdlog.controller.webflux.enabled` (default `true`) para apagar sólo la vía reactiva — mismo patrón que `restclient.webclient.enabled` y `jdbc.r2dbc.enabled`.
- **Dependencias**: `spring-webflux` y `reactor-core` ya están `provided` (desde `ADR-0006`). Opcionalmente `io.micrometer:context-propagation` `provided`.
- **Paridad de ramas** (`ADR-0005`): cada fase se porta a `spring-boot-3.x`. El código reactivo es agnóstico de la versión de Spring Boot en su mayoría; delta esperado mínimo.

### Plan de fases

Cada fase es una PR independiente a `main`, portada a `spring-boot-3.x`. La suite servlet (205 tests) se mantiene verde en cada PR como prueba de no-regresión; se añade una suite reactiva con `WebTestClient`.

**Fase 1 — `StdlogWebFilter`** (`@ConditionalOnWebApplication(REACTIVE)`) — **HECHA**:

- Genera/lee `request_id` del header `x-request-id` (lo devuelve en la respuesta), igual que `RequestIdMdcFilter`.
- Resuelve `operation` (`Controller#method`) y `route` (`METHOD patrón`) tras completar la cadena, desde `HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE`/`BEST_MATCHING_PATTERN_ATTRIBUTE` del `ServerWebExchange`.
- Aplica `excluded-path-patterns` (`AntPathMatcher`); en un path excluido, envuelve la emisión con `MDC_EXCLUDED` para que `StdlogEmitter` suprima TRACE/DEBUG/INFO (`WARN`/`ERROR` siempre salen), igual que la vía servlet.
- Emite `CONTROLLER_HTTP IN` y `OUT` (ambos tras completar la cadena, como el filtro servlet) con headers allowlist, `queryParams`, y body capturado con `ServerHttpRequestDecorator`/`ServerHttpResponseDecorator` tee-ados, acotado por `maxRequestBodyBytes`/`maxResponseBodyBytes` y filtrado por `allowedContentTypes`.
- Emite el **evento extra de error** por el status final de `exchange.getResponse()` (4xx → WARN, 5xx → ERROR). La excepción se captura vía `doOnError`; si un `WebExceptionHandler` la convirtió antes, el evento sale con status + mensaje sintético (mejora en Fase 3).
- Escribe `request_id` y exclusión en el Reactor Context (`operation`/`route` aún no, porque se resuelven al final; la Fase 2 los propaga si hace falta).
- Emisión en hilo del event-loop: restaura el MDC con los valores capturados alrededor de cada `StdlogEmitter.emit(...)` (patrón del filtro WebClient). `StdlogEmitter` / `StdlogTraceCorrelation` sin cambios.
- Config: reutiliza `stdlog.controller.*` / `stdlog.error.*` + `stdlog.controller.webflux.enabled` (default `true`).
- `StdlogWebFluxAutoConfiguration` registrada en `AutoConfiguration.imports`; el paquete servlet no se toca.

**Fase 2 — Correlación downstream** (parcialmente hecha):

- **HECHO** — `StdlogWebClientExchangeFilter` envuelve su lógica en `Mono.deferContextual` y lee `request_id`/`operation`/exclusión del `ContextView` de Reactor cuando el MDC está vacío. En una app WebFlux, la llamada `WebClient` hereda el `Context` que puebla `StdlogWebFilter` (misma cadena reactiva), así que **no hace falta context-propagation** para correlacionar `CLIENT_HTTP` con el request. MDC-first: la vía servlet no cambia.
- **HECHO** — `StdlogWebClientAutoConfiguration` deja de estar gated a `SERVLET`; se activa con sólo `@ConditionalOnClass(WebClient)` (servlet, WebFlux o no-web).
- **HECHO** — `StdlogReactorContext` movido a `appbrain.stdlog.core` (compartido por el `WebFilter` que escribe y los clientes que leen).
- **PENDIENTE** — Correlación de **R2DBC** en apps WebFlux: `r2dbc-proxy` 1.1.x **no expone el `ContextView`** a sus `ProxyExecutionListener` (sólo `ValueStore` por query/conexión). El listener sigue leyendo del MDC en `beforeQuery`; en una app WebFlux eso requiere que el consumidor propague el MDC (Micrometer context-propagation con un accessor de MDC + `Hooks.enableAutomaticContextPropagation()`). Opciones a evaluar: (a) el starter provee y registra un `ThreadLocalAccessor` para `request_id` (el consumidor sólo activa el hook); (b) esperar soporte de Context en r2dbc-proxy; (c) documentarlo como límite. Se decide en una PR aparte.
- **PENDIENTE** — Propagación de `operation`/`route` a los clientes salientes en WebFlux (se resuelven tarde en el `WebFilter`; hoy sólo `request_id` viaja por el Context).

**Fase 3 — Refinamientos**:

- `@StdlogExcluded` en handler methods reactivos.
- `StdlogCustom` para código reactivo (variante que lee el Context, o helper `StdlogCustom.with(contextView)`), ya que la fachada estática lee MDC.
- Evento de error de mayor fidelidad: registrar un `WebExceptionHandler` de alta precedencia para capturar la excepción real antes de que `ExceptionHandlingWebHandler` la convierta en respuesta (si se decide que vale la invasividad).

## Consecuencias

### Positivas

- Cobertura completa para apps WebFlux: `CONTROLLER_HTTP`, evento de error, correlación de `CLIENT_DB`/`CLIENT_HTTP`, exclusión.
- Paridad de eventos y de configuración con la vía servlet.
- El código servlet no se toca: cero riesgo de regresión en esa vía.

### Negativas

- Duplica la superficie de la capa de entrada web y su suite de tests.
- Doble mantenimiento (dos stacks de entrada × dos ramas).
- El starter deja de ser "servlet-only"; cambia su descripción, su matriz de CI y las expectativas de los consumidores.

### Riesgos

- **Correlación en pipelines 100% reactivos** depende de que el consumidor active Micrometer context-propagation. Sin eso, `request_id`/`operation` pueden no llegar a `CLIENT_DB`/`CLIENT_HTTP` ni a los logs propios del consumidor.
- **Evento de error**: `ExceptionHandlingWebHandler` de WebFlux suele "tragarse" la excepción y convertirla en respuesta antes de que llegue al `WebFilter`. La Fase 1 emite el evento por status code; la excepción real es best-effort hasta la Fase 3.
- **`StdlogCustom` estático** no encaja bien en reactivo (lee MDC/ThreadLocal). Necesita una variante o depender de context-propagation.
- **Orden de `WebFilter`s**: si el consumidor tiene filtros que cortocircuitan la cadena, el `WebFilter` de stdlog debe ir suficientemente externo.

## Impacto

- **Módulos afectados:** nuevo `appbrain.stdlog.webflux`; nueva `StdlogWebFluxAutoConfiguration` + `AutoConfiguration.imports`; `config` (`stdlog.controller.webflux.enabled`); `core` sólo aditivo; en Fase 2, cambios aditivos en `StdlogWebClientExchangeFilter` / `StdlogR2dbcQueryListener` / `StdlogWebClientAutoConfiguration`.
- **Contratos públicos:** el starter pasa a soportar dos stacks de entrada; nuevos eventos en apps reactivas (mismo shape); nueva auto-config listada; nueva key de config.
- **Dependencias:** `spring-webflux` / `reactor-core` ya `provided`; opcional `io.micrometer:context-propagation` `provided`.
- **Compatibilidad:** aditivo para apps servlet (auto-config no activa). Para apps WebFlux, aparecen eventos nuevos.
- **Alcance del starter:** cambia de "servlet/MVC" a "servlet/MVC y WebFlux". `AI_CONTEXT.md` y `README.md` se actualizan.

## Validación

Antes de cerrar cada fase:

- Suite servlet **verde** — prueba de no-regresión de la vía servlet (el código servlet no se modifica).
- Nueva suite reactiva con `WebTestClient` para la fase correspondiente.
- Portado a `spring-boot-3.x`, verde en JDK 17 y JDK 25.
- `AI_CONTEXT.md` / `README.md` actualizados en la misma PR (`ADR-0004`).

**Fase 1**: `StdlogWebFilterTest` (7 tests, `WebTestClient.bindToController` + `.webFilter(...)`): `webflux.enabled=false`, `CONTROLLER_HTTP IN/OUT` de un GET (method/fullPath/operation/route/request_id/queryParams), reutilización del header `x-request-id`, captura de body request+response, evento `ERROR` en un controller que tira (excepción + throwable), evento `WARN` en 404, supresión de INFO en path excluido. `StdlogWebFluxAutoConfigurationTest` (4 tests). Código servlet sin cambios.

**Fase 2 (parcial)**: `StdlogWebClientExchangeFilterTest` — nuevos: `request_id`/`operation` tomados del Reactor Context cuando el MDC está vacío; MDC gana sobre el Context si ambos están. `StdlogWebClientAutoConfigurationTest` — la auto-config se activa también fuera de apps servlet. Suite completa: **218 tests, 0 fallos** en JDK 17 y JDK 25.

## Relación con Otros ADR

- **Completa `ADR-0006` y `ADR-0007`**: sus clientes reactivos (`WebClient`, R2DBC) obtienen correlación de request en apps WebFlux gracias al `WebFilter` de la Fase 1 y a los cambios de la Fase 2.
- Relacionado con: `ADR-0005` (se porta a las dos líneas), `ADR-0004` (doc por fase), `ADR-0001` (la entrada servlet asumía `HandlerExceptionResolver` de `spring-webmvc`, que en WebFlux no existe).
- Sustituye: la afirmación de `AI_CONTEXT.md` de que "el starter está orientado a aplicaciones servlet, no WebFlux" en lo relativo a la entrada HTTP.
