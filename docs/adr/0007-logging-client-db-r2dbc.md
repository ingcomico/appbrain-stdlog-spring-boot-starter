# ADR-0007: Logging del evento `CLIENT_DB` para R2DBC (base de datos reactiva)

## Estado

Aceptado

## Contexto

- El logging de queries a base de datos (`CLIENT_DB`) lo hace hoy `StdlogClientDbQueryListener`, un `QueryExecutionListener` de `datasource-proxy` registrado por `StdlogJdbcAutoConfiguration`. Es puramente **JDBC**: `@ConditionalOnClass(ProxyDataSourceBuilder)` + `@ConditionalOnBean(DataSource.class)`, y reemplaza el `DataSource` por un proxy `@Primary`.
- Una aplicación que usa **R2DBC** (Spring Data R2DBC, `DatabaseClient`, `R2dbcEntityTemplate`) no tiene un `javax.sql.DataSource` — tiene un `io.r2dbc.spi.ConnectionFactory`. `datasource-proxy` no sabe nada de R2DBC. **Ninguna condición de `StdlogJdbcAutoConfiguration` se cumple → cero eventos `CLIENT_DB`.** Las queries a la base son invisibles.
- Este ADR cubre R2DBC como **cliente de base de datos**, análogo a como `ADR-0006` cubrió `WebClient` como cliente HTTP saliente. **No** depende del soporte de aplicaciones WebFlux completas (que es una decisión de alcance aparte): la auto-config **no** se limita a apps reactivas.
- Existe `io.r2dbc:r2dbc-proxy` (mismo autor que `datasource-proxy`, gestionado por el BOM de Spring Boot), con una API casi calcada: `ProxyConnectionFactory.builder(cf)`, `ProxyExecutionListener` (`beforeQuery`/`afterQuery`), `QueryExecutionInfo` (`getQueries()`, `getExecuteDuration()`, `getThrowable()`, `isSuccess()`, `getType()`, `getConnectionInfo()`, `getValueStore()`).
- `ADR-0005` obliga a que esto llegue a las dos ramas. R2DBC / r2dbc-proxy existen en el stack de Spring 6 y 7.

### El problema de correlación

`ProxyExecutionListener` corre `afterQuery` en el hilo que completa el `Publisher` de la query (event-loop del driver), donde el MDC está vacío. `r2dbc-proxy 1.1.x` **no expone el `ContextView` de Reactor** a los listeners. Pero `QueryExecutionInfo.getValueStore()` es un mapa mutable **compartido entre `beforeQuery` y `afterQuery`** de la misma query, y `beforeQuery` corre en el hilo que suscribe (para una llamada `.block()` desde un controller servlet, ese es el hilo de request, con el MDC poblado por `RequestIdMdcFilter`).

## Alternativas Consideradas

### Alternativa 1 — `ProxyExecutionListener` + `ConnectionFactory` `@Primary`, snapshot de MDC en `beforeQuery`

Nueva `StdlogR2dbcAutoConfiguration` que envuelve el `ConnectionFactory` con `ProxyConnectionFactory` y lo registra `@Primary` (mismo patrón que el `@Primary DataSource` de JDBC). Nuevo `StdlogR2dbcQueryListener` que en `beforeQuery` copia el MDC al `ValueStore` de la query y en `afterQuery` lo restaura alrededor de la emisión, produciendo el mismo evento `CLIENT_DB`.

Ventajas:

- Cobertura de `CLIENT_DB` para R2DBC con el mismo formato que JDBC.
- **Funciona en apps servlet + R2DBC** (con `.block()`): correlación completa vía el snapshot del MDC.
- En apps WebFlux: emite `CLIENT_DB` igual; la correlación depende de que el consumidor tenga Micrometer context-propagation (o del futuro soporte WebFlux del starter).
- Mismo patrón arquitectónico que `datasource-proxy` — bajo riesgo conceptual.

Desventajas:

- Dependencia nueva (`r2dbc-proxy`, `r2dbc-spi`), aunque `provided` y `@ConditionalOnClass`.
- El `@Primary ConnectionFactory` puede interactuar con configuraciones avanzadas del consumidor (múltiples `ConnectionFactory`, pooling propio) — mismo caveat que el `@Primary DataSource` de JDBC.
- La suposición "`beforeQuery` corre en el hilo que suscribe" se valida con un test contra R2DBC H2 real; si no se cumpliera, la correlación en servlet sería parcial.

### Alternativa 2 — Sólo el paquete `observation` de r2dbc-proxy (Micrometer)

Registrar el `ObservationProxyExecutionListener` de r2dbc-proxy y dejar que Micrometer maneje la propagación de contexto.

Desventajas:

- Obliga al consumidor a tener Micrometer Observation configurado.
- Produce observaciones/spans, no el evento `CLIENT_DB` del schema `stdlog`.
- No cubre el caso servlet + `.block()` sin más.

### Alternativa 3 — No soportar R2DBC

Descartada: deja un hueco total de observabilidad de base de datos para cualquier app R2DBC.

## Decisión

Se adopta la **Alternativa 1**.

### Componentes

1. **`StdlogR2dbcQueryListener implements io.r2dbc.proxy.listener.ProxyExecutionListener`** (`appbrain.stdlog.r2dbc`, nuevo paquete):
   - `beforeQuery(QueryExecutionInfo)`: `MDC.getCopyOfContextMap()` → `queryInfo.getValueStore().put(MDC_KEY, copia)`.
   - `afterQuery(QueryExecutionInfo)`: lee el `ValueStore`, restaura ese MDC en el hilo actual (con `finally` que restaura el previo), construye el evento `CLIENT_DB` y llama a `StdlogEmitter.emit(...)`. Cualquier excepción de logging se traga.
   - Evento con el mismo shape que el de JDBC: `event=CLIENT_DB`, `direction=OUT`, `elapsedMs`, `outcome`, `slow`, `request_id`/`operation` (del MDC restaurado), `peer.pool` (connection id, o `poolName` si no hay), `db.statement` (SQL truncado a `maxSqlChars`), `db.type` (SELECT/INSERT/...), `db.params` opcional (de `Bindings`, si `logParams`), `error` en fallo. **`db.response` no se emite** para R2DBC (el conteo de filas es best-effort y asíncrono; `logResponseInfo` sólo aplica a JDBC).
   - Política anti-ruido: en `PROD` + `logOnlySlowOrFailureInProd`, omite queries exitosas no lentas. `slow` según `slowQueryThresholdMs`. Nivel `WARN` si lenta, `levelSuccess`/`levelFailure` según corresponda.
   - **`StdlogClientDbQueryListener` (JDBC) NO se modifica.** El armado del evento se duplica (~30 líneas de scaffold); la extracción de params y `db.response` es distinta entre JDBC y R2DBC de todos modos.

2. **`StdlogR2dbcAutoConfiguration`** (`appbrain.stdlog.autoconfig`):
   - `@AutoConfiguration`, `@ConditionalOnClass(io.r2dbc.proxy.ProxyConnectionFactory)`, `@ConditionalOnBean(io.r2dbc.spi.ConnectionFactory.class)`, `@ConditionalOnProperty(prefix = "stdlog.jdbc", name = "enabled", matchIfMissing = true)`, y un sub-flag `stdlog.jdbc.r2dbc.enabled` (default `true`).
   - **Sin `@ConditionalOnWebApplication`**: R2DBC en una app servlet es un caso válido.
   - Bean `StdlogR2dbcQueryListener` + bean `@Primary ConnectionFactory` construido con `ProxyConnectionFactory.builder(real).listener(listener).build()`.
   - Registrada en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

3. **Configuración**: se reutiliza `stdlog.jdbc.*` completo (es configuración de "logging de queries a base de datos", no específica de JDBC). Se añade `stdlog.jdbc.r2dbc.enabled` (default `true`) para apagar sólo la vía R2DBC.

4. **Dependencias** (`pom.xml`): `io.r2dbc:r2dbc-proxy` e `io.r2dbc:r2dbc-spi` en scope `provided`. `io.r2dbc:r2dbc-h2` + `com.h2database:h2` en scope `test`.

### Paridad de ramas (`ADR-0005`)

Se implementa en `main` y se porta a `spring-boot-3.x`. El código del listener y de la auto-config es agnóstico de la versión de Spring Boot (usa `io.r2dbc.*`, `org.slf4j.MDC`). Delta esperado: mínimo o nulo.

## Consecuencias

### Positivas

- `CLIENT_DB` para R2DBC con el mismo formato que JDBC → observabilidad de base de datos también en apps reactivas.
- Apps **servlet + R2DBC** obtienen correlación completa (`request_id`/`operation`) sin configuración extra.
- Mismo patrón que el módulo JDBC — fácil de razonar y mantener.

### Negativas

- Dependencia nueva (`r2dbc-proxy` / `r2dbc-spi`, `provided`).
- Duplicación acotada del scaffold del evento `CLIENT_DB` entre el listener JDBC y el R2DBC.
- `@Primary ConnectionFactory`: puede chocar con setups avanzados del consumidor (misma limitación que el `@Primary DataSource` de JDBC).

### Riesgos

- **Correlación**: **validado** con el test H2 — `beforeQuery` captura el MDC del hilo que suscribe y `request_id`/`operation` llegan al evento tras un `.block()`. En apps WebFlux la correlación depende de Micrometer context-propagation del consumidor hasta que exista soporte WebFlux nativo.
- **`db.response`**: no se emite para R2DBC (ver Decisión).
- **Pooling** (`r2dbc-pool`): el orden de wrapping importa; se documenta el caso recomendado (`ConnectionFactory` del contexto → proxy `@Primary`).

## Impacto

- **Módulos afectados:** nuevo `appbrain.stdlog.r2dbc`, nueva auto-config en `autoconfig` + `AutoConfiguration.imports`, `config` (sub-flag `jdbc.r2dbc.enabled`), `pom.xml`.
- **Contratos públicos:** nuevo evento `CLIENT_DB` para R2DBC (mismo shape); `stdlog.jdbc` gana `r2dbc.enabled`; nueva auto-config listada.
- **Dependencias:** `r2dbc-proxy` + `r2dbc-spi` `provided`.
- **Compatibilidad:** aditivo. Consumidores sin R2DBC no se ven afectados (`@ConditionalOnClass`).
- **Observabilidad:** cubre un cliente de base de datos antes no instrumentado.
- **Seguridad:** los parámetros SQL de R2DBC entran al log bajo las mismas reglas que JDBC (`logParams`, `maxParamChars`).
- **Alcance del starter:** no cambia. R2DBC es un add-on de cliente de base de datos; el soporte de apps WebFlux completas sigue pendiente (candidato a ADR aparte).

## Validación

- `StdlogR2dbcQueryListenerTest` (7 tests) contra `r2dbc-h2` real: SELECT, INSERT con `request_id`/`operation` del MDC, fallo por SQL inválido (evento `FAILURE` + throwable), query lenta con nivel `WARN`, `logOnlySlowOrFailureInProd` en PROD (se omite), `r2dbc.enabled=false` (se omite), params de `Bindings` con `logParams`.
- `StdlogR2dbcAutoConfigurationTest` (4 tests): proxy del `ConnectionFactory`, no se activa sin bean / con `stdlog.jdbc.enabled=false` / con `stdlog.jdbc.r2dbc.enabled=false`.
- `StdlogClientDbQueryListener` (JDBC) sin cambios (verificable en el diff).
- Suite completa: **205 tests, 0 fallos** en `main`, verificado en JDK 17 y JDK 25. Se porta a `spring-boot-3.x`.

## Relación con Otros ADR

- Análogo a `ADR-0006` (WebClient como cliente saliente; mismo patrón de add-on opcional y de snapshot/restauración de MDC).
- Relacionado con: `ADR-0002` (los eventos R2DBC también se enriquecen con `trace_id`/`span_id`), `ADR-0003` (misma salida JSON), `ADR-0005` (se porta a las dos líneas), `ADR-0004` (esta doc viaja en la misma PR).
- **No** depende de la futura decisión de soporte de apps WebFlux completas; sí la habilita parcialmente (los `CLIENT_DB` ya se emiten, sólo falta el contexto de entrada).
