# ADR-0006: Logging del evento `CLIENT_HTTP` para `WebClient` (cliente saliente reactivo)

## Estado

Aceptado

## Contexto

- El logging de HTTP saliente (`CLIENT_HTTP`) lo hace hoy `StdlogClientHttpInterceptor`, que implementa `org.springframework.http.client.ClientHttpRequestInterceptor`. Esa interfaz la usan `RestTemplate` y `RestClient`, por lo que ambos quedan cubiertos con una sola clase.
- `WebClient` **no** usa esa interfaz: es reactivo (Project Reactor) y su punto de extensión es `org.springframework.web.reactive.function.client.ExchangeFilterFunction`. Un consumidor servlet que use `WebClient` como cliente HTTP saliente no obtiene el evento `CLIENT_HTTP`.
- El starter es y sigue siendo servlet/MVC (`@ConditionalOnWebApplication(SERVLET)` en las auto-configs). Este ADR **no** expande el alcance a aplicaciones WebFlux completas: sólo cubre `WebClient` como cliente saliente dentro de una app servlet.
- Decisiones de alcance ya tomadas para este ADR:
  - **Captura con body**: paridad completa con `RestTemplate`/`RestClient`, incluyendo body de request y response, sujeto a `stdlog.restclient.maxBodyChars` y a que `logging.level.stdlog=DEBUG`.
  - **Sólo cliente saliente**: el resto del starter (filtros de entrada, JDBC, error MVC) no cambia.
- `ADR-0005` obliga a que esto llegue a las dos líneas (`main` Boot 4 y `spring-boot-3.x` Boot 3). `ExchangeFilterFunction` y `WebClient` existen en Spring 6 y 7; el delta esperado es el paquete de `WebClientCustomizer`.

## Alternativas Consideradas

### Alternativa 1 — `ExchangeFilterFunction` con captura de body reactiva y builder de payload compartido

Nueva clase `StdlogWebClientExchangeFilter implements ExchangeFilterFunction`. Se extrae de `StdlogClientHttpInterceptor` la construcción del `Map` del evento a un helper compartido, de modo que el interceptor síncrono y el filtro reactivo produzcan exactamente el mismo JSON. El body se captura tee-ando los publishers de Reactor.

Ventajas:

- Un único formato de evento `CLIENT_HTTP` para los tres clientes.
- Reutiliza `StdlogHttpBodyDecoder`, `StdlogModeResolver`, `StdlogCallerResolver` y toda la configuración `stdlog.restclient.*`.
- El consumidor no cambia código: el filtro se añade al `WebClient.Builder` autoconfigurado vía `WebClientCustomizer`.

Desventajas:

- Código reactivo: la captura de body exige `join` de `Flux<DataBuffer>` y reconstruir la respuesta, con cuidado de backpressure y de no consumir el body dos veces.
- Riesgo de memoria en respuestas grandes o streaming.
- `request_id` / `operation` viven en MDC (ThreadLocal); en un pipeline totalmente reactivo sin context-propagation pueden no estar disponibles.

### Alternativa 2 — Sólo metadatos (sin body)

Descartada: se pidió paridad con `RestTemplate`/`RestClient`.

### Alternativa 3 — No soportar `WebClient`

Documentar que el cliente reactivo queda fuera y recomendar instrumentación OpenTelemetry. Descartada: deja un hueco de observabilidad para un cliente HTTP de uso común.

## Decisión

Se adopta la **Alternativa 1**.

### Componentes (implementados)

1. **Helper compartido** `StdlogClientHttpPayload` (package-private, `appbrain.stdlog.restclient`, **código nuevo**): recibe primitivos —método, URL, host, status, `elapsedMs`, `failure`, headers de request/response, bodies ya decodificados (`StdlogHttpBodyDecoder.Decoded`), `request_id`, `operation`, `call_id`, `source`— y devuelve el `Map<String,Object>` del evento (`event=CLIENT_HTTP`, `direction=IN`, `http`, `peer`, `request`, `response`, `outcome`, ...). Su lógica es un espejo fiel de `StdlogClientHttpInterceptor.emit()`.
   **`StdlogClientHttpInterceptor` NO se modifica** (principio abierto/cerrado). Queda ~40 líneas de armado de payload en dos sitios; consolidar el interceptor sobre el helper es un refactor aparte, fuera de alcance.

2. **`StdlogWebClientExchangeFilter implements ExchangeFilterFunction`** (`appbrain.stdlog.restclient`):
   - En `filter(request, next)`, que corre en el hilo que suscribe (el de request cuando la app hace `.block()`):
     - `Map<String,String> capturedMdc = MDC.getCopyOfContextMap()` — copia completa del MDC (incluye `request_id`, `operation`, `traceId`/`spanId` y el marcador de exclusión);
     - resuelve `call_id` (`captureCallId`) y `source` (`captureSource`, stacktrace-walk) igual que el interceptor;
     - `startNano = System.nanoTime()`.
   - **Request body** (sólo si `stdlog` en DEBUG): decora el `ClientRequest` de forma que, al escribir el body al connector, se tee-an los primeros `maxCaptureBytes` a un buffer sin consumir los `DataBuffer`. La app envía el body intacto.
   - **Response body** (sólo si DEBUG): tee del `Flux<DataBuffer>` de la respuesta — la app recibe el stream completo vía `response.mutate().body(teed)`, nosotros bufferizamos hasta `maxCaptureBytes` (lo que exceda se marca truncado) y emitimos cuando el stream de body termina (`doOnComplete` / `doOnCancel` / `doOnError`, con guardia idempotente). No hay `join` sin límite: la memoria queda acotada por `maxCaptureBytes` aunque la respuesta sea grande o chunked.
   - **Sin DEBUG**: se emite al recibir la respuesta, sin tocar el body.
   - **Emisión**: `safeEmit(...)` restaura `capturedMdc` en el hilo actual (event-loop), llama a `StdlogClientHttpPayload.build(...)` + `StdlogEmitter.emit(...)`, y restaura el MDC previo del hilo en un `finally`. Cualquier excepción de logging se traga: nunca rompe la llamada. Nivel por `status` con la familia `inLevel*` (igual que el interceptor).
   - `mode=PROD` + `logOnlyOnFailureInProd=true`: los éxitos (`status < 400`) no se emiten.
   - Errores de conexión / timeout: `outcome=FAILURE`, status `500`, nivel `inLevelFailure5xx`, la excepción se propaga.

3. **`StdlogWebClientAutoConfiguration`** (`appbrain.stdlog.autoconfig`):
   - `@AutoConfiguration`, `@ConditionalOnClass(WebClient.class)`, `@ConditionalOnWebApplication(type = SERVLET)`, `@ConditionalOnProperty(prefix = "stdlog.restclient", name = "enabled", matchIfMissing = true)`.
   - Registra `StdlogWebClientExchangeFilter` como bean y un `BeanPostProcessor` que añade el filtro (idempotente, al final de la lista) a cualquier `WebClient.Builder` del contexto. Se usa un `BeanPostProcessor` en vez de `WebClientCustomizer` para no depender del paquete de `WebClientCustomizer`, que cambió de módulo entre Spring Boot 3 y 4 — así el código es idéntico en las dos ramas.
   - Consumidores que construyen `WebClient` sin un `WebClient.Builder` del contexto deben añadir el filtro a mano.
   - Registrada en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

4. **Configuración**: se reutiliza `stdlog.restclient.*` completo. Se añaden dos claves bajo `stdlog.restclient.webclient`: `enabled` (default `true`, apaga sólo la vía WebClient) y `max-capture-bytes` (default `262144`, tope de bufferización por body; `0` = sin tope).

5. **Dependencias** (`pom.xml`): `org.springframework:spring-webflux` e `io.projectreactor:reactor-core` en scope `provided` (como `spring-webmvc`). El `@ConditionalOnClass` evita cualquier efecto si no están.

### Paridad de ramas (`ADR-0005`)

Se implementa en `main` y se porta a `spring-boot-3.x`. Delta esperado: el import de `WebClientCustomizer` (`org.springframework.boot.web.reactive.function.client` en Boot 3.5; paquete movido en Boot 4) y, si aplica, algún tipo reactor/Boot. El código del filtro y del helper es idéntico.

## Consecuencias

### Positivas

- Paridad de observabilidad para los tres clientes HTTP soportados (`RestTemplate`, `RestClient`, `WebClient`).
- El `CLIENT_HTTP` de WebClient tiene el mismo shape exacto que el de los otros clientes (helper compartido).
- `StdlogClientHttpInterceptor` no se toca: cero riesgo de regresión en la vía síncrona.

### Negativas

- `spring-webflux` + `reactor-core` como `provided`: peso de classpath sólo si el consumidor ya los usa (que es el caso si usa WebClient).
- Código reactivo con superficie de test mayor (tee, cancelación, salto de hilo).
- Duplicación acotada (~40 líneas) del armado de payload entre el interceptor y el helper nuevo.
- Dos claves de configuración nuevas (`stdlog.restclient.webclient.enabled`, `...max-capture-bytes`).

### Riesgos

- **Memoria**: acotada por `maxCaptureBytes` (tee bufferizado, sin `join` ilimitado). La respuesta pasa a la app en streaming.
- **Correlación incompleta**: si `filter()` corre en un hilo sin MDC (app que suscribe la llamada desde otro hilo, pipeline 100% reactivo sin context-propagation), `request_id`/`operation`/`trace_id`/`span_id` se omiten — mismo comportamiento que el interceptor síncrono sin MDC. En el caso objetivo (app servlet + `.block()`) el MDC está presente y se restaura alrededor de la emisión. Verificado con un test que fuerza un salto de hilo (`publishOn(boundedElastic)`).
- **Timing de emisión**: con DEBUG, el evento se emite cuando el body de la respuesta termina de consumirse, no antes. `elapsedMs` mide hasta ese punto.
- **Fragilidad de API**: el tee del request body (`ClientHttpRequestDecorator`) y `response.mutate().body(...)` dependen de Spring WebFlux; pueden cambiar entre majors.
- **Body no consumido**: si la app obtiene la respuesta con `exchange`/`exchangeToMono` y no consume el body, la emisión puede no dispararse. Con `retrieve()` / `bodyTo*` / `toBodilessEntity()` (el uso normal) siempre se dispara.

## Impacto

- **Módulos afectados:** `restclient` (nuevo filtro + helper compartido + refactor del interceptor), `autoconfig` (nueva auto-config + `AutoConfiguration.imports`), `config` (flag `webclient.enabled`), `pom.xml`.
- **Contratos públicos:** `CLIENT_HTTP` ahora también se emite para `WebClient`; `stdlog.restclient` gana `webclient.enabled`. El shape del evento no cambia. Nueva auto-config listada en `AutoConfiguration.imports`.
- **Dependencias:** `spring-webflux` y `reactor-core` en `provided`.
- **Compatibilidad:** aditivo. Consumidores sin WebClient no se ven afectados (`@ConditionalOnClass`).
- **Observabilidad:** cubre un cliente HTTP antes no instrumentado.
- **Seguridad:** los bodies/headers salientes de WebClient entran al log bajo las mismas reglas y riesgos que los de `RestTemplate`/`RestClient` (allowlists de headers, `maxBodyChars`, body sólo en DEBUG).
- **Despliegue:** sin cambios.
- **Alcance del starter:** sigue siendo servlet/MVC; `WebClient` es un add-on de cliente saliente, no un stack de entrada.

## Validación

- `AI_CONTEXT.md` actualizado: "Limitaciones Actuales" ya no dice "no hay evidencia de WebClient"; "Contratos Públicos" y "Plataforma" reflejan el módulo nuevo.
- `StdlogClientHttpInterceptor` sin cambios (verificable en el diff).
- `StdlogWebClientExchangeFilterTest` (14 tests): módulo deshabilitado, `webclient.enabled=false`, éxito sin/con DEBUG, request+response body con la app leyendo el body completo, truncado a `maxCaptureBytes`, 404/5xx/nivel, error de conexión (propagado + logueado), `logOnlyOnFailureInProd` en PROD, `request_id`/`operation` a través de un salto de hilo reactivo, `trace_id`/`span_id`, allowlist de headers, `call_id`.
- `StdlogWebClientAutoConfigurationTest` (3 tests): filtro añadido a los `WebClient.Builder` del contexto, no se registra con `stdlog.restclient.enabled=false`, no se registra fuera de apps servlet.
- Suite completa: **194 tests, 0 fallos** en `main`, verificado en JDK 17 y JDK 25. Se porta a `spring-boot-3.x`.

## Relación con Otros ADR

- Relacionado con: `ADR-0002` (los eventos de WebClient también se enriquecen con `trace_id`/`span_id`), `ADR-0003` (viajan por la misma salida JSON), `ADR-0005` (se porta a las dos líneas), `ADR-0004` (esta doc viaja en la misma PR que el código).
- Sustituye: nada.
