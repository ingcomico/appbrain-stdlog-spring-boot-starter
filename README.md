# appbrain-stdlog-spring-boot-starter — README (v1)

Starter de logging estructurado (JSON) para aplicaciones Spring Boot. Emite logs bajo el schema `stdlog` para:

- **Controller HTTP**: `CONTROLLER_HTTP` (IN / OUT)
- **HTTP Client** (`RestTemplate`, `RestClient` y `WebClient`): `CLIENT_HTTP` (single-log `direction=IN`)
- **Base de datos** (`datasource-proxy` para JDBC, `r2dbc-proxy` para R2DBC): `CLIENT_DB` (single-log `direction=OUT`)
- **Custom / negocio**: eventos definidos por la app (`StdlogCustom`)
- **Evento extra por excepción MVC**: `event=WARN|ERROR` (según status final 4xx/5xx)

Todos los eventos emitidos por la librería pasan por `StdlogEmitter` y, si existe
contexto de tracing activo, incluyen `trace_id` y `span_id` dentro de `stdlog`.
La extracción intenta primero MDC (`traceId`/`spanId`) y luego OpenTelemetry API
si está disponible en el classpath. Si no hay tracing activo, esos campos se
omiten sin afectar el log.

---

## Tabla de contenido

- [1. Instalación](#1-instalación)
- [2. Logback (obligatorio)](#2-logback-obligatorio)
- [3. Configuración completa (ejemplo)](#3-configuración-completa-ejemplo)
- [4. stdlog.mode](#4-stdlogmode)
- [5. Controller logs (CONTROLLER_HTTP)](#5-controller-logs-controller_http)
- [6. Custom logs (StdlogCustom)](#6-custom-logs-stdlogcustom)
- [7. HTTP Client logs (CLIENT_HTTP)](#7-http-client-logs-client_http)
- [8. JDBC logs (CLIENT_DB)](#8-jdbc-logs-client_db)
- [9. Evento extra por excepción MVC (WARN/ERROR)](#9-evento-extra-por-excepción-mvc-warnerror)
- [10. Recomendaciones de seguridad y performance](#10-recomendaciones-de-seguridad-y-performance)
  - [10.1 Enmascaramiento de datos sensibles](#101-enmascaramiento-de-datos-sensibles)
- [11. Troubleshooting](#11-troubleshooting)

---

## 1. Instalación

### 1.1 Dependencia Maven

```xml
<dependency>
  <groupId>appbrain</groupId>
  <artifactId>appbrain-stdlog-spring-boot-starter</artifactId>
  <version>4.0.0-local</version>
</dependency>
```

Todas las dependencias del starter son públicas y se resuelven desde Maven Central (repositorio por defecto de Maven); no se requiere ningún repositorio privado adicional.

### 1.2 Publicación local de prueba

El proyecto está configurado para publicar una versión local en un repositorio Maven
dentro de la carpeta `release/` de la raíz del proyecto. Para construir y publicar:

```bash
mvn clean deploy
```

El artefacto queda disponible en:

```text
release/appbrain/appbrain-stdlog-spring-boot-starter/4.0.0-local/
```

Coordenadas Maven:

```text
appbrain:appbrain-stdlog-spring-boot-starter:4.0.0-local
```

Para consumir esta versión desde otro proyecto Maven, agrega el repositorio local:

```xml
<repositories>
  <repository>
    <id>appbrain-stdlog-local-release</id>
    <url>file:///Users/ingcomico/IdeaProjects/appbrain-stdlog-spring-boot-starter/release</url>
  </repository>
</repositories>
```

y declara la dependencia:

```xml
<dependency>
  <groupId>appbrain</groupId>
  <artifactId>appbrain-stdlog-spring-boot-starter</artifactId>
  <version>4.0.0-local</version>
</dependency>
```

La carpeta `release/` no se versiona en Git; se regenera ejecutando `mvn clean deploy`.

---

## 2. Logback (obligatorio)

El starter provee `classpath:stdlog/logback-spring-stdlog.xml` para emitir JSON (logstash encoder).

En el consumidor:

```yaml
logging:
  config: classpath:stdlog/logback-spring-stdlog.xml
```

### 2.1 Nivel del logger stdlog

El "gating" final de qué se imprime lo gobierna `logging.level.stdlog`:

```yaml
logging:
  level:
    stdlog: DEBUG
```

- **DEBUG**: habilita body en restclient (porque se loguea bajo `logger.isDebugEnabled()`).
- **INFO**: imprime eventos INFO/WARN/ERROR pero no bodies.
- **ERROR**: imprime solo errores.

> **Nota:** los niveles configurados en `stdlog.*.*Level` definen la severidad del evento (INFO/WARN/ERROR), pero lo que se imprime finalmente queda sujeto a `logging.level.stdlog`.

---

## 3. Configuración completa (ejemplo)

Este perfil está pensado para desarrollo o diagnóstico: emite todos los eventos en
`NON_PROD`, captura bodies JSON, parámetros SQL y el origen de cada llamada HTTP.
No usarlo sin revisión en producción, porque los bodies y los parámetros pueden
contener datos sensibles.

```yaml
logging:
  config: classpath:stdlog/logback-spring-stdlog.xml
  level:
    root: INFO
    stdlog: DEBUG
    com.example.myapp: INFO
    org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver: ERROR

stdlog:
  mode: NON_PROD
  consumer-base-package: com.example.myapp

  controller:
    enabled: true
    in-level: INFO
    out-level-success: INFO
    out-level-failure-4xx: WARN
    out-level-failure-5xx: ERROR
    log-request-body: true
    log-response-body: true
    max-request-body-bytes: 4096
    max-response-body-bytes: 4096
    allowed-headers: []
    excluded-path-patterns:
      - /actuator/**
      - /ping
      - /health
    allowed-content-types:
      - application/json

  restclient:
    enabled: true
    in-level-success: INFO
    in-level-failure-4xx: WARN
    in-level-failure-5xx: ERROR
    log-only-on-failure-in-prod: false
    max-body-chars: 2000
    request-headers-allowlist: []
    response-headers-allowlist: []
    log-all-request-headers: false
    log-all-response-headers: false
    capture-source: true
    capture-call-id: true

  jdbc:
    enabled: true
    pool-name: db
    level-success: INFO
    level-failure: ERROR
    log-only-slow-or-failure-in-prod: false
    slow-query-threshold-ms: 50
    max-sql-chars: 1000
    log-params: true
    max-param-chars: 0
    log-response-info: true

  error:
    enabled: true
```

`logging.level.stdlog: DEBUG` es necesario para incluir bodies de restclient.
Los niveles `*-level-*` definen la severidad del evento; el logger `stdlog` es el
filtro final que determina si se imprime.

---

## 4. stdlog.mode

Controla políticas "anti-ruido" en distintos módulos (por ejemplo restclient/jdbc).

| Valor | Comportamiento |
|-------|---------------|
| `AUTO` | Infiere por la variable de entorno `STDLOG_MODE` (`PROD`, `NON_PROD` o `NONPROD`); si no está definida, default `NON_PROD` |
| `PROD` | Fuerza modo productivo |
| `NON_PROD` | Fuerza modo no productivo |

```bash
export STDLOG_MODE=PROD
# o
export STDLOG_MODE=NON_PROD
```

---

## 5. Controller logs (CONTROLLER_HTTP)

### 5.1 Qué emite

- `CONTROLLER_HTTP direction=IN` por request
- `CONTROLLER_HTTP direction=OUT` por response

Incluye:

- `request_id` (filtro `RequestIdMdcFilter`)
- `operation` y `route` (interceptor MVC)
- `http.method` y `http.fullPath`
- `request.queryParams` + headers allowlist
- body request/response condicionado por content-type allowlist + truncamiento

### 5.2 Ejemplo — IN

```json
{
  "stdlog": {
    "event": "CONTROLLER_HTTP",
    "direction": "IN",
    "operation": "TagsController#searchTags",
    "route": "GET /configcases/v1/tags",
    "request_id": "uuid",
    "http": { "method": "GET", "fullPath": "/configcases/v1/tags?site_id=MCO&id=10" },
    "request": {
      "inputType": "QUERY",
      "queryParams": { "site_id": "MCO", "id": "10" },
      "headers": { "x-routing": "beta,1092" }
    }
  }
}
```

### 5.3 Ejemplo — OUT

```json
{
  "stdlog": {
    "event": "CONTROLLER_HTTP",
    "direction": "OUT",
    "elapsedMs": 45,
    "outcome": "SUCCESS",
    "http": { "status": 200 },
    "response": { "bodyCapture": "EMPTY" }
  }
}
```

> **WebFlux:** en apps reactivas la instrumentación de entrada la hace un `WebFilter`
> equivalente (ver 5.6). Misma config, mismos eventos.

### 5.4 Variables `stdlog.controller.*`

| Variable | Descripción |
|----------|-------------|
| `enabled` | Habilita/deshabilita logs de controller |
| `in-level` | Nivel del evento IN |
| `out-level-success` | Nivel del OUT para status < 400 |
| `out-level-failure-4xx` | Nivel del OUT para 400 <= status < 500 |
| `out-level-failure-5xx` | Nivel del OUT para status >= 500 |
| `log-request-body` | Si `true`, intenta capturar body request (solo si content-type permitido) |
| `log-response-body` | Si `true`, intenta capturar body response (solo si content-type permitido) |
| `max-request-body-bytes` | Máximo de bytes a loguear del body request |
| `max-response-body-bytes` | Máximo de bytes a loguear del body response |
| `excluded-path-patterns` | Lista de patrones Ant para silenciar eventos INFO/DEBUG/TRACE de ese request (WARN/ERROR igual se loguean — ver [5.5](#55-exclusión-de-logging-regla-general)). Se comparan contra el path sin `contextPath`; admite valores exactos como `/health` y prefijos como `/actuator/**`. Default: vacío |
| `allowed-headers` | Allowlist de headers del request |
| `allowed-content-types` | Allowlist de content-types para capturar body (recomendado JSON y texto). Una lista vacía deshabilita toda captura de body, aunque `log-request-body` o `log-response-body` sea `true`. Default: `[application/json, text/plain, application/*+json]` |
| `webflux.enabled` | Si `false`, no se instrumenta la entrada HTTP reactiva (apps servlet no se ven afectadas). Default `true`. Ver [5.6](#56-aplicaciones-webflux-entrada-reactiva) |

### 5.4.1 Qué se loguea cuando el request no llega al controller

Desde **ADR-0012** el filtro se registra **por fuera de la cadena de Spring Security**, así que
también se registran los requests que nunca llegan a un handler:

| Caso | Antes | Ahora |
|---|---|---|
| `401`/`403` de Spring Security | sin ningún evento | `CONTROLLER_HTTP` IN/OUT + evento de error |
| Rechazo de CORS | sin ningún evento | igual que arriba |
| `ResponseEntity.status(403)` de un controller | sólo `OUT` | `OUT` + evento de error |
| `404`/`405` resueltos por Spring MVC | sólo `OUT` | `OUT` + evento de error |

Dos aclaraciones sobre esos eventos:

- **`route` se rellena** con `método + URI` cuando no hay patrón de `HandlerMapping`.
- **`operation` no aparece**, y es correcto: el request nunca seleccionó un handler, así que no
  hay operación que nombrar. En un request normal `operation` sigue exactamente igual que antes.

El evento extra de error ya no depende de que haya una excepción, sino del **status final**: que
la aplicación no falle no significa que no haya nada que registrar. Cuando hay excepción se
adjunta con su `app_trace` y su stack trace; cuando no, el nodo `error` lo indica.

> **Cambio de volumen:** un endpoint público bajo un ataque de credenciales pasará a generar
> eventos que antes no existían. `excluded-path-patterns` reduce el ruido INFO, pero los
> `WARN`/`ERROR` nunca se suprimen (ver [5.5](#55-exclusión-de-logging-regla-general)).

### 5.5 Exclusión de logging: regla general

Hay dos formas de excluir tráfico del logging — por path (5.5.1) o por clase/método
anotado (5.5.2) — y **ambas se comportan igual**: no apagan el logging por completo,
sino que silencian únicamente el ruido de bajo nivel. La regla exacta es:

> **Se suprime cualquier evento de nivel `TRACE`, `DEBUG` o `INFO`. Un evento
> `WARN` o `ERROR` nunca se suprime, sin importar la exclusión.**

Esta regla es única y se evalúa en un solo lugar (`StdlogEmitter`), así que aplica
por igual a **todos** los tipos de evento — no solo `CONTROLLER_HTTP`, también
`CLIENT_DB`, `CLIENT_HTTP` y `StdlogCustom` — mientras ocurran dentro del mismo
request/handler excluido. En la práctica, con los niveles default del starter esto
significa:

| Evento | Nivel default | ¿Se suprime si el request/handler está excluido? |
|---|---|---|
| `CONTROLLER_HTTP` IN | INFO | Sí |
| `CONTROLLER_HTTP` OUT, status 2xx/3xx | INFO | Sí |
| `CONTROLLER_HTTP` OUT, status 4xx | WARN | **No** |
| `CONTROLLER_HTTP` OUT, status 5xx | ERROR | **No** |
| Evento extra de excepción MVC | WARN/ERROR | **No** |
| `CLIENT_DB` exitoso, no lento | INFO | Sí |
| `CLIENT_DB` lento | WARN | **No** |
| `CLIENT_DB` con error | ERROR | **No** |
| `CLIENT_HTTP` exitoso | INFO | Sí |
| `CLIENT_HTTP` 4xx/5xx | WARN/ERROR | **No** |
| `StdlogCustom.info/debug/success()` | INFO/DEBUG | Sí |
| `StdlogCustom.warn/failure/error()` | WARN/ERROR | **No** |

Si configurás niveles no-default (ej. `controller.out-level-failure4xx: INFO`), la
visibilidad sigue esa configuración: la exclusión respeta el nivel efectivo del
evento, no un chequeo especial por tipo de evento u `outcome`.

#### 5.5.1 Por path (`excluded-path-patterns`)

```yaml
stdlog:
  controller:
    excluded-path-patterns:
      - /actuator/**
      - /health
      - /internal/metrics/**
```

Los patrones usan sintaxis Ant y se evalúan al entrar al filtro, **antes** de que
Spring MVC resuelva el handler. La coincidencia no incluye el `contextPath`: con un
contexto `/my-app`, el request `/my-app/actuator/health` coincide con `/actuator/**`.
Como el filtro envuelve todo lo que sigue (interceptor, controller, queries JDBC,
llamadas salientes) en el mismo request, la exclusión aplica a todo eso — no solo al
propio `CONTROLLER_HTTP`.

#### 5.5.2 Por clase o método (`@StdlogExcluded`)

Si en cambio querés excluir un controller o un método puntual — independientemente
de qué path lo dispare, o para no tener que mantener el path sincronizado con el
código— usá la anotación `@StdlogExcluded`:

```java
import appbrain.stdlog.StdlogExcluded;

@StdlogExcluded
@RestController
public class InternalDiagnosticsController {
    // ningún endpoint de este controller emite eventos INFO/DEBUG/TRACE
}

@RestController
public class OrdersController {

    @StdlogExcluded
    @GetMapping("/orders/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/orders")
    public List<Order> list() {
        // este sí se loguea normalmente
    }
}
```

Puede aplicarse a nivel de clase (excluye todos los métodos del controller) o a
nivel de método (excluye solo ese endpoint). Se resuelve en
`StdlogMvcOperationInterceptor` (que ya conoce el `HandlerMethod` real), no en el
filtro, y soporta anotaciones compuestas/meta-anotadas (por ejemplo, tu propio
`@InternalEndpoint` meta-anotado con `@StdlogExcluded`).

En **WebFlux** la anotación también funciona: `StdlogWebFilter` resuelve
`@StdlogExcluded` desde el `HandlerMethod` tras la cadena (mismo `AnnotatedElementUtils`).
Como el handler se conoce recién al final del request, la exclusión por anotación
sólo suprime `CONTROLLER_HTTP`/error de ese endpoint; los `CLIENT_*` aguas abajo ya
se emitieron. Para silenciar también los `CLIENT_*`, usá exclusión por path.

### 5.6 Aplicaciones WebFlux (entrada reactiva)

En una aplicación **WebFlux** (Netty, controllers reactivos), el starter registra
un `WebFilter` (`StdlogWebFilter`, `@ConditionalOnWebApplication(REACTIVE)`) que
emite el **mismo `CONTROLLER_HTTP IN/OUT`** y el **mismo evento extra de error**
(`WARN` 4xx / `ERROR` 5xx), resuelve `operation`/`route` y aplica
`excluded-path-patterns`. Reutiliza toda la config `stdlog.controller.*` y
`stdlog.error.*`.

- No requiere ningún paso manual: el `WebFilter` se autoconfigura.
- Escribe `request_id` (header `x-request-id`, lo devuelve) en el **Reactor Context**.
- Captura de body con decoradores reactivos, acotada por
  `maxRequestBodyBytes`/`maxResponseBodyBytes`.
- Para apagar sólo esta vía: `stdlog.controller.webflux.enabled: false`.
- **Correlación con el request** en una app WebFlux:
  - `CLIENT_HTTP` (`WebClient`): funciona sin configuración — el filtro lee
    `request_id` y `operation` del Reactor Context que puebla este `WebFilter`
    (misma cadena reactiva).
  - `CLIENT_DB` (R2DBC): `r2dbc-proxy` no expone el Reactor Context a sus listeners.
    El starter **provee y registra** un `ThreadLocalAccessor` para `request_id`; para
    activarlo, el consumidor debe llamar una vez a
    `reactor.core.publisher.Hooks.enableAutomaticContextPropagation()` (switch global
    de la app; el starter no lo hace). Con eso, `request_id` llega al `CLIENT_DB`.
    `operation` no llega al `CLIENT_DB` en WebFlux.
- **Evento de error de alta fidelidad**: el starter registra
  `StdlogWebExceptionHandler` (`WebExceptionHandler`, `@Order(HIGHEST_PRECEDENCE)`)
  que captura la excepción real y la guarda en un atributo del exchange **sin
  consumirla** (la re-propaga para que el manejo normal de WebFlux siga su curso).
  `StdlogWebFilter` la lee al cerrar el request, así el evento de error lleva `type`,
  `message` y `app_trace` reales aunque `ExceptionHandlingWebHandler` ya haya
  convertido la excepción en respuesta. `doOnError` queda como respaldo.
- **Eventos custom desde código reactivo**: `StdlogCustom` es una fachada estática
  que lee el MDC, que en una cadena reactiva puede estar vacío. Para código reactivo
  usá `StdlogCustomReactive` (paquete `appbrain.stdlog.webflux`): cada método
  devuelve un `Mono<Void>` que lee la correlación del Reactor Context y la restaura
  en el MDC alrededor de la emisión. Se compone con tu cadena:

  ```java
  import appbrain.stdlog.webflux.StdlogCustomReactive;

  return pagosService.pagar(req)
          .flatMap(res -> StdlogCustomReactive
                  .success("PAGO_OK", Map.of("id", res.id()))
                  .thenReturn(res));
  ```

  Mismos métodos que `StdlogCustom` (`info/warn/debug/success/failure/error`);
  `StdlogCustom` no se modifica.

Aplicaciones servlet no se ven afectadas: los dos stacks son mutuamente excluyentes.

---

## 6. Custom logs (StdlogCustom)

### 6.1 Uso

```java
import appbrain.stdlog.StdlogCustom;

StdlogCustom.info("TAG_CREATED", Map.of("id", 10, "site", "MCO"));
StdlogCustom.warn("RETRY_ATTEMPT", Map.of("attempt", 2));
StdlogCustom.debug("TRACE_STEP", Map.of("step", "validate"));
StdlogCustom.success("OP_OK", Map.of("result", "ok"));
StdlogCustom.failure("OP_FAIL", Map.of("result", "error"), exception);
StdlogCustom.error("UPSTREAM_CALL", "TIMEOUT", Map.of("peer", "users"), exception);
```

### 6.2 Ejemplo esperado

```json
{
  "stdlog": {
    "event": "TAG_CREATED",
    "operation": "TagsController#searchTags",
    "request_id": "uuid",
    "custom": { "id": 10, "site": "MCO" }
  }
}
```

> `operation` y `request_id` aparecen si existe MDC (por ejemplo dentro de un request HTTP).
> `trace_id` y `span_id` aparecen automáticamente cuando hay contexto de tracing activo.

> **WebFlux:** en código reactivo el MDC suele estar vacío. Usá `StdlogCustomReactive`
> (paquete `appbrain.stdlog.webflux`), que lee la correlación del Reactor Context.
> Ver [5.6](#56-aplicaciones-webflux-entrada-reactiva).

---

## 7. HTTP Client logs (CLIENT_HTTP)

> **Nivel visible mínimo:** los valores `in-level-*` definen la severidad del
> evento, pero el log solo se imprime si `logging.level.stdlog` lo permite. Por
> ejemplo, un evento configurado como `DEBUG` será invisible con `stdlog: INFO`.

### 7.1 Estrategia (single-log)

Se emite un único log por llamada HTTP saliente hecha con `RestTemplate`,
`RestClient` o `WebClient`:
- `event=CLIENT_HTTP`, `direction=IN`
- Incluye request + response:
  - request headers y (si stdlog está en DEBUG) request body
  - response headers y (si stdlog está en DEBUG) response body
- En PROD puede filtrar para emitir solo failures (>=400)

`StdlogClientHttpInterceptor` implementa `ClientHttpRequestInterceptor`, interfaz
pública compartida por `RestTemplate` y `RestClient`. Para `WebClient` (reactivo)
hay un `StdlogWebClientExchangeFilter` aparte que produce el mismo payload vía el
helper compartido `StdlogClientHttpPayload`. Los tres clientes usan la misma
configuración `stdlog.restclient.*` (ver ADR-0006).

### 7.2 Ejemplo esperado

```json
{
  "stdlog": {
    "event": "CLIENT_HTTP",
    "direction": "IN",
    "elapsedMs": 120,
    "outcome": "SUCCESS",
    "request_id": "uuid",
    "operation": "TagsController#searchTags",
    "http": { "method": "GET", "url": "https://...", "status": 200 },
    "peer": { "host": "api.example.com" },
    "request": { "headers": { "x-admin-id": "fraudMP" } },
    "response": { "headers": { "content-type": "application/json" } }
  }
}
```

### 7.3 Variables `stdlog.restclient.*`

| Variable | Descripción |
|----------|-------------|
| `enabled` | Habilita logs de restclient |
| `in-level-success` | Nivel para status < 400 |
| `in-level-failure-4xx` | Nivel para 400-499 |
| `in-level-failure-5xx` | Nivel para >=500 o excepción |
| `log-only-on-failure-in-prod` | Si `true` y `mode=PROD`: solo loguea cuando status >=400 o excepción. En NON_PROD loguea todo |
| `max-body-chars` | Body solo se loguea si `logging.level.stdlog=DEBUG`. `0` = sin límite |
| `request-headers-allowlist` | Allowlist de headers request (si `log-all-request-headers=false`) |
| `response-headers-allowlist` | Allowlist de headers response (si `log-all-response-headers=false`) |
| `log-all-request-headers` | Si `true`, loguea todos los headers request (cuidado con datos sensibles) |
| `log-all-response-headers` | Si `true`, loguea todos los headers response |
| `capture-source` | Si `true`, captura caller (stacktrace-walk) — costo CPU |
| `capture-call-id` | Si `true`, genera `call_id` por llamada |
| `webclient.enabled` | Si `false`, no se instrumenta `WebClient` (RestTemplate/RestClient siguen). Default `true` |
| `webclient.max-capture-bytes` | Tope de bufferización por body de `WebClient` para el log. Default `262144` (256 KiB). `0` = sin tope |

### 7.4 Integración con `RestClient`

**Caso recomendado — `RestClient` construido via `RestClient.Builder`:**
el starter registra un `RestClientCustomizer` que agrega automáticamente el
interceptor a cualquier `RestClient` construido a partir del builder
autoconfigurado por Spring Boot:

```java
@Bean
public RestClient restClient(RestClient.Builder builder) {
    return builder.build();
}
```

La captura del body de respuesta no impide que luego la aplicación lea el body
normalmente con `.retrieve().body(...)`; el starter devuelve internamente una
respuesta re-leíble cuando necesita capturar el body para el log.

**Caso manual — `RestClient` construido sin el builder de Spring Boot:**
inyectá el interceptor y registralo vos mismo:

```java
@Autowired
private StdlogClientHttpInterceptor stdlogClientHttpInterceptor;

@Bean
public RestClient restClient() {
    return RestClient.builder()
            .requestInterceptor(stdlogClientHttpInterceptor)
            .build();
}
```

Sin este registro, `CLIENT_HTTP` no aparece aunque `stdlog.restclient.enabled=true`.

### 7.5 Integración con `RestTemplate`

**Caso recomendado — `RestTemplate` construido via `RestTemplateBuilder`:**
el starter registra un `RestTemplateCustomizer` que agrega automáticamente el
interceptor y envuelve el request factory con
`BufferingClientHttpRequestFactory` (para que el body de la respuesta pueda
leerse tanto en el log como en tu código). No requiere ningún paso manual:

```java
@Bean
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}
```

**Caso manual — `RestTemplate` construido con `new RestTemplate()`:**
inyectá el interceptor y registralo vos mismo, más el buffering request
factory si querés poder leer el body de la respuesta en tu código:

```java
@Autowired
private StdlogClientHttpInterceptor stdlogClientHttpInterceptor;

@Bean
public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(restTemplate.getRequestFactory()));
    restTemplate.getInterceptors().add(stdlogClientHttpInterceptor);
    return restTemplate;
}
```

Sin este registro, `CLIENT_HTTP` no aparece aunque `stdlog.restclient.enabled=true`.

### 7.6 Integración con `WebClient`

Si `WebClient` está en el classpath, el starter registra un
`ExchangeFilterFunction` y lo añade automáticamente a cualquier
`WebClient.Builder` del contexto — en apps servlet, WebFlux o no-web. El evento
`CLIENT_HTTP` tiene el mismo formato y usa la misma configuración
`stdlog.restclient.*` que `RestTemplate`/`RestClient`.

```java
@Bean
public WebClient webClient(WebClient.Builder builder) {
    return builder.baseUrl("https://api.example.com").build();
}
```

- **Correlación** (`request_id`, `operation`): MDC primero (app servlet + `.block()`);
  si el MDC está vacío, del **Reactor Context** (app WebFlux — lo puebla el
  `WebFilter` de entrada, ver [5.6](#56-aplicaciones-webflux-entrada-reactiva)).
  `operation` se resuelve del `ServerWebExchange` presente en el Context.
- El body se captura sólo con `logging.level.stdlog=DEBUG`, bufferizado hasta
  `stdlog.restclient.webclient.max-capture-bytes` (256 KiB por defecto); tu código
  recibe siempre el body completo.
- Para apagar sólo esta vía: `stdlog.restclient.webclient.enabled: false`.

**Caso manual — `WebClient` construido sin un `WebClient.Builder` del contexto:**

```java
@Autowired
private StdlogWebClientExchangeFilter stdlogWebClientExchangeFilter;

@Bean
public WebClient webClient() {
    return WebClient.builder()
            .filter(stdlogWebClientExchangeFilter)
            .build();
}
```

---

## 8. JDBC logs (CLIENT_DB)

> **Nivel visible mínimo:** `level-success` y `level-failure` indican la
> severidad del evento; `logging.level.stdlog` controla finalmente si se emite.

### 8.1 Estrategia

Un log por query ejecutada:
- `event=CLIENT_DB`, `direction=OUT`
- Incluye:
  - `db.statement` (truncado)
  - `db.type` (SELECT/INSERT/UPDATE/DELETE/OTHER)
  - `db.params` (si `logParams=true`, formato explícito por operación)
  - `slow` (según threshold)
  - `db.response` (si `logResponseInfo=true`)
  - `error{type,message}` si falla

### 8.2 Ejemplo esperado (FAILURE)

```json
{
  "stdlog": {
    "event": "CLIENT_DB",
    "direction": "OUT",
    "elapsedMs": 14,
    "outcome": "FAILURE",
    "slow": false,
    "request_id": "uuid",
    "operation": "TagsController#searchTags",
    "peer": { "pool": "db" },
    "db": {
      "statement": "SELECT ...",
      "type": "SELECT",
      "params": [
        {
          "params": [
            [
              { "m": "setString", "i": 1, "t": "VARCHAR", "v": "MCO" },
              { "m": "setNull",   "i": 2, "t": "OBJECT",  "v": null }
            ]
          ]
        }
      ],
      "response": { "type": "RESULT_SET" }
    },
    "error": { "type": "java.sql.SQLSyntaxErrorException", "message": "..." }
  }
}
```

### 8.3 Variables `stdlog.jdbc.*`

| Variable | Descripción |
|----------|-------------|
| `enabled` | Habilita proxy del DataSource y logs |
| `pool-name` | Nombre lógico del pool (`peer.pool`) |
| `log-only-slow-or-failure-in-prod` | Si `true` y `mode=PROD`: loguea solo cuando `slow=true` o `outcome=FAILURE`. En NON_PROD loguea todo |
| `slow-query-threshold-ms` | Si > 0, marca `slow=true` cuando `elapsedMs >= threshold` |
| `level-success` | Nivel para success "normal" (ej `INFO`) |
| `level-failure` | Nivel para failures (ej `ERROR`) |
| `max-sql-chars` | Truncamiento del SQL |
| `log-params` | Si `true`, incluye params (cuidado con PII) |
| `max-param-chars` | Truncamiento de strings en params |
| `log-response-info` | Si `true`, incluye `db.response` (por v1: `RESULT_SET` vs `UPDATE_COUNT` si aplica). Solo JDBC |
| `r2dbc.enabled` | Si `false`, no se instrumenta R2DBC (JDBC sigue). Default `true` |

### 8.4 R2DBC (base de datos reactiva)

Si el classpath tiene `r2dbc-proxy` y existe un bean `io.r2dbc.spi.ConnectionFactory`,
el starter registra un `ProxyExecutionListener` y reemplaza el `ConnectionFactory`
por uno proxeado `@Primary`. Emite el **mismo evento `CLIENT_DB`** y usa la misma
configuración `stdlog.jdbc.*`.

```java
@Bean
public ConnectionFactory connectionFactory() {
    return ConnectionFactories.get("r2dbc:postgresql://host/db");
}
```

- **No** está limitado a apps WebFlux: R2DBC en una app servlet (con `.block()`) es un
  caso válido, y ahí la correlación con el request (`request_id`, `operation`) es completa.
- En una app **WebFlux completa**, el evento `CLIENT_DB` se emite igual, pero
  `request_id`/`operation` sólo aparecen si el consumidor tiene Micrometer
  context-propagation activo (el starter no instrumenta la entrada HTTP reactiva).
- `db.response` (filas devueltas/afectadas) no se emite para R2DBC: en R2DBC el
  conteo es best-effort y asíncrono.
- Para apagar sólo esta vía: `stdlog.jdbc.r2dbc.enabled: false`.

Dependencias: `io.r2dbc:r2dbc-proxy` + `io.r2dbc:r2dbc-spi` (`provided` en el starter;
las trae tu app si usa R2DBC).

---

## 9. Evento extra por excepción MVC (WARN/ERROR)

### 9.1 Qué emite

Cuando hay una excepción real en MVC, se emite un evento adicional:

- status `4xx` → `stdlog.event="WARN"` y log level `WARN`
- status `5xx` → `stdlog.event="ERROR"` y log level `ERROR`

Incluye:

- `request_id`, `operation`, `route`
- `trace_id`, `span_id` cuando hay contexto de tracing activo
- `http.status`
- `error.app_trace`, `error.type`, `error.message`
- `error.stack_trace` (texto clásico cliqueable en IDE)

### 9.2 Variables `stdlog.error.*`

| Variable | Descripción |
|----------|-------------|
| `enabled` | Habilita/deshabilita el módulo completo. Default: `true` |

> **Nota:** `MethodArgumentNotValidException` (errores de validación de beans) nunca genera evento extra, para evitar ruido en flujos normales de validación.

> **Logging duplicado:** si un `@ControllerAdvice` también registra la excepción
> con `LOGGER.warn/error`, habrá un log adicional al evento estructurado del
> starter. Revisá esos logs caso por caso: podés conservarlos si cumplen una
> necesidad operativa distinta, o eliminar/suavizar los redundantes. El nivel
> `ERROR` del `ExceptionHandlerExceptionResolver` del ejemplo reduce el ruido
> informativo del framework, sin afectar el evento `stdlog`.

---

## 10. Recomendaciones de seguridad y performance

### 10.1 Enmascaramiento de datos sensibles

Desde **ADR-0010** el starter enmascara valores sensibles **por defecto, sin configurar nada**.
Se aplica en `StdlogEmitter`, el punto único por el que pasan todos los eventos, así que
cubre a la vez las cinco superficies por las que podía salir un dato:

- bodies de request y response del controller (servlet y WebFlux),
- bodies de `CLIENT_HTTP`,
- `queryParams`,
- `db.params`,
- headers, incluido `Authorization` cuando se activa `logAllRequestHeaders`.

```json
{
  "stdlog": {
    "request": {
      "headers": { "authorization": "***" },
      "body": "{\"user\":\"ana\",\"password\":\"***\"}"
    }
  }
}
```

**Cómo decide qué enmascarar.** Por *nombre de clave*. La clave se normaliza —minúsculas y
sin `_`, `-`, `.`— y se compara por igualdad exacta, así que `card_number`, `cardNumber` y
`Card-Number` coinciden entre sí. No se compara por subcadena, a propósito: si no,
`shipping` activaría la regla `pin`.

Claves incorporadas: `password`, `passwd`, `pwd`, `secret`, `clientSecret`, `token`,
`accessToken`, `refreshToken`, `idToken`, `authorization`, `apiKey`, `apiToken`,
`credential(s)`, `privateKey`, `sessionId`, `cookie`, `cardNumber`, `pan`, `cvv`, `cvc`,
`pin`, `otp`, `ssn`, `taxId`.

```yaml
stdlog:
  masking:
    enabled: true              # default
    additional-keys: [iban, nss]   # añade a la lista incorporada
    # keys: [iban]             # REEMPLAZA la lista incorporada
    placeholder: "***"         # default
```

> **Los bodies siguen capturándose igual.** El enmascaramiento reduce el riesgo sin quitar
> funcionalidad: no hubo que apagar `logRequestBody` ni `logResponseBody`.

**Dos límites que conviene conocer:**

1. **Es por nombre de clave, no por contenido.** Un campo sensible con un nombre que no está
   en la lista sigue saliendo en claro. Añadilo con `additional-keys`.
2. **Sobre bodies que llegan como texto es best-effort.** Ahí se enmascaran los pares
   `"clave": valor` y `clave=valor` operando sobre el texto y no sobre un árbol —así funciona
   también con bodies truncados o con JSON inválido, que es cuando un parser fallaría—, pero
   una estructura muy exótica podría escapársele.

No sustituye a no enviar el dato: si algo no debe salir nunca del proceso, lo correcto sigue
siendo no capturarlo.

### 10.2 Configuraciones a vigilar

| Configuración | Riesgo |
|---------------|--------|
| `masking.enabled=false` | Desactiva todo el enmascaramiento — sólo para depurar en local |
| `restclient.logAllRequestHeaders=true` | Incluye todas las cabeceras; las sensibles van enmascaradas, las de nombre no previsto no |
| `jdbc.logParams=true` | Puede exponer datos sensibles con nombres de parámetro no previstos |
| `restclient.captureSource=true` | Hace stacktrace-walk por llamada (costo CPU) |
| `restclient.maxBodyChars=0` + `logging.level.stdlog=DEBUG` | Puede generar logs muy grandes |
| `controller.allowedContentTypes` con binarios/multipart | Puede llenar heap/logs — evitar |

## 11. Troubleshooting

| Síntoma | Causa probable | Solución |
|---------|----------------|----------|
| `CLIENT_HTTP` no aparece aunque `restclient.enabled: true` | El cliente no se construyó via `RestClient.Builder`/`RestTemplateBuilder` autoconfigurado por Spring Boot, o el interceptor no fue registrado a mano | Usar los builders autoconfigurados o registrar `StdlogClientHttpInterceptor` manualmente como se muestra en las secciones 7.4 y 7.5 |
| `bodyCapture: SKIPPED_CONTENT_TYPE` | El content type no está permitido o `allowed-content-types` está vacío | Agregar explícitamente el content type esperado, por ejemplo `application/json` |
| Logs de restclient o JDBC no aparecen | El nivel del evento está por debajo de `logging.level.stdlog` | Subir la severidad a `INFO` o configurar `logging.level.stdlog: DEBUG` |
| El body de restclient no aparece | El logger `stdlog` no está en `DEBUG` | Configurar `logging.level.stdlog: DEBUG` |
| Hay varios logs por la misma excepción | El `@ControllerAdvice`, el starter y/o Spring registran la excepción | Revisar y conservar solo los logs que aporten información operacional distinta |
| Falta `stdlog.event` en un log de la aplicación | Se emitió con `LOGGER.xxx()` en lugar de `StdlogCustom` | Usar `StdlogCustom` para eventos de negocio que deban cumplir el schema |
| `Logging system failed to initialize` o falta `LoggingEventCompositeJsonEncoder` | El starter o sus dependencias no fueron resueltos correctamente | Recargar Maven y verificar el árbol de dependencias; `logstash-logback-encoder` ya es una dependencia del starter |
