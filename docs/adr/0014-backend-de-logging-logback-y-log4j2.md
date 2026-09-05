# ADR-0014: Backend de logging — soportar Logback y Log4j2 con salida equivalente

## Estado

Aceptado

> Implementado. La viabilidad se verificó **antes** de escribir el ADR (ver "Spike de
> viabilidad") y la equivalencia de salida está cubierta por un test que se comprobó capaz de
> detectar una divergencia real.

## Restricción previa: no perder funcionalidad

Sujeto al principio de que **el esquema emitido es un contrato**. Aquí el principio no es una
restricción lateral sino el motivo del ADR: hoy, bajo un backend que no sea Logback, **se
pierde el evento entero**, y en silencio.

## Contexto

- La superficie de acoplamiento a Logback es **una sola línea**, en `StdlogEmitter`:

  ```java
  import static net.logstash.logback.marker.Markers.append;
  ```

- Pero el acoplamiento es total en cuanto al contenido, porque **el payload viaja íntegro en el
  `Marker`**:

  ```java
  logger.info(append("stdlog", out), "stdlog");
  //          └─ el evento completo    └─ el mensaje es la cadena literal "stdlog"
  ```

- Consecuencia: con cualquier backend que no entienda los markers de logstash —Log4j2 el
  primero— el starter **no falla**. Emite, por cada evento, una línea que dice `stdlog` y nada
  más. Es visible en cualquier build del propio proyecto: `INFO stdlog -- stdlog`.
- Es el modo de fallo que `ADR-0011` declaró inaceptable —perder datos sin avisar—, sólo que a
  nivel de backend en lugar de por evento. Un equipo puede montar Log4j2 y tardar semanas en
  descubrir que sus logs estructurados no existen.
- **Corrección a la auditoría técnica**: el hallazgo F-13 afirmaba que el starter «arrastra
  Logback a todo el mundo». Es inexacto. `logstash-logback-encoder` está en scope `compile` y sí
  llega al consumidor, pero **no arrastra `logback-classic`**: en el árbol de dependencias
  aparece sólo en scope `test`, y proviene de `spring-boot-starter-test`. El coste real de la
  dependencia es menor de lo reportado; el problema serio es el otro, la pérdida silenciosa.
- La librería todavía no está en producción, así que un cambio estructural en el punto de
  emisión es más barato ahora que nunca.

## Spike de viabilidad

Antes de decidir se comprobó, con Log4j2 2.25.4 y `JsonTemplateLayout` reales y en un classpath
aislado, si Log4j2 puede producir una salida **equivalente** —no degradada— a la de logstash.

Payload de prueba: el mismo anidamiento que usa el starter (`http.status`, `request.headers.*`).

| Mecanismo | Resultado |
|---|---|
| `ObjectMessage(Map)` | ✅ JSON anidado equivalente, **conserva el orden de inserción** |
| `MapMessage` | ⚠️ JSON anidado, pero **reordena las claves alfabéticamente** |

```json
{"@timestamp":"…","level":"INFO","stdlog":{"event":"CONTROLLER_HTTP","direction":"IN",
 "request_id":"abc-123","http":{"method":"POST","status":200},
 "request":{"headers":{"x-routing":"MCO"},"body":{"a":1}}}}
```

Dos conclusiones que ordenan la decisión:

1. **La equivalencia es alcanzable**, con `ObjectMessage` y una plantilla de `JsonTemplateLayout`.
   No hay que conformarse con degradar el payload a una cadena.
2. **No se alcanza desde SLF4J.** Un `ObjectMessage` no se puede pasar por la API de SLF4J, así
   que la vía Log4j2 exige usar `org.apache.logging.log4j.Logger` directamente. Esto es lo que
   convierte el cambio en estructural y no en una línea.

La detección del backend también se verificó:

| Backend | `LoggerFactory.getILoggerFactory()` |
|---|---|
| Logback | `ch.qos.logback.classic.LoggerContext` |
| Log4j2 | `org.apache.logging.slf4j.Log4jLoggerFactory` |

## Alternativas Consideradas

### Alternativa 1 — Declarar Logback como requisito y documentarlo

Ventajas:

- Coste casi nulo; sólo hay que añadir la detección y el aviso.

Desventajas:

- No cumple el requisito: se pidió que la librería funcione igual en los dos caminos.
- Deja fuera a los consumidores sobre Log4j2, que en el ecosistema Spring no son marginales.

### Alternativa 2 — Respaldo degradado: serializar el JSON dentro del mensaje

Cuando el backend no es Logback, se escribe el payload como texto JSON en el mensaje.

Ventajas:

- Nada se pierde y la implementación es sencilla.

Desventajas:

- La salida **no es equivalente**: el consumidor recibe una cadena escapada en vez de campos
  anidados, así que su pipeline de ingesta necesita un parseo distinto por backend. Rompe la
  promesa de que el JSON emitido es un contrato estable.
- El spike demuestra que no hace falta conformarse con esto.

### Alternativa 3 — Módulo Log4j2 aparte, como segundo artefacto

Ventajas:

- Separación limpia de dependencias.

Desventajas:

- Duplica la matriz de build, de tests y de portes entre las dos ramas permanentes de
  `ADR-0005`, que ya cuesta mantener.
- El consumidor tiene que elegir artefacto, cuando la librería puede averiguarlo sola.

### Alternativa 4 — Detección en runtime y dos escritores en el mismo artefacto

Se abstrae la escritura del evento; hay una implementación para Logback (la actual) y otra para
Log4j2 (`ObjectMessage`), y se elige al arrancar según el backend enlazado.

Ventajas:

- Cumple el requisito: **misma salida por los dos caminos**, sin que el consumidor configure nada.
- Un solo artefacto, una sola matriz de build.
- La superficie nueva está acotada: el punto de emisión ya es único (`ADR-0010`, `ADR-0011`).

Desventajas:

- Una abstracción más en el camino de todos los eventos.
- Hay que mantener dos recursos de configuración equivalentes (`logback-spring-stdlog.xml` y su
  homólogo de Log4j2) y probar que producen el mismo JSON.

## Decisión

Se adopta la **Alternativa 4**.

### Reglas derivadas

**1. Abstracción del escritor.** Se introduce en `appbrain.stdlog.core` una interfaz mínima que
recibe el logger, el nivel, el payload y el `Throwable` opcional. `StdlogEmitter` deja de
conocer logstash y delega en ella. Es el único punto que cambia, porque ya es el único punto por
el que pasan todos los eventos.

**2. Dos implementaciones.**

- *Logback*: la actual, `Markers.append("stdlog", payload)` vía SLF4J. Sin cambios de salida.
- *Log4j2*: `org.apache.logging.log4j.Logger` con `ObjectMessage(payload)`. Se usa
  `ObjectMessage` y no `MapMessage` porque el segundo reordena las claves, y el orden del
  esquema es parte de lo que se lee.

**3. Detección al arrancar, una sola vez**, por `LoggerFactory.getILoggerFactory()`. Mismo
patrón de fachada estática configurada al arrancar que `ADR-0010`, `ADR-0011` y `ADR-0013`.

**4. Dependencias.** `log4j-api` entra en scope `provided`, igual que `spring-webflux` o
`r2dbc-proxy`: sólo se usa si el consumidor ya lo tiene. `logstash-logback-encoder` se mantiene
en `compile` mientras Logback sea el camino por defecto; revisarlo es materia de otro cambio.

**5. Recurso de configuración para Log4j2.** Se publica un `stdlog/log4j2-stdlog.xml` con una
plantilla de `JsonTemplateLayout` equivalente en campos al `logback-spring-stdlog.xml` actual:
mismo `stdlog`, mismo `stack_trace`, mismo `stdlog_lib_version`.

**6. El backend detectado se anuncia al arrancar**, en `appbrain.stdlog.internal`, igual que el
modo de `ADR-0013`.

**7. Si no hay ningún backend soportado, se avisa fuerte.** Con un backend desconocido el
starter seguirá sin romper la aplicación —`ADR-0011` no se negocia—, pero **no puede callarse**:
un `WARN` explícito al arrancar diciendo que los eventos no se van a renderizar. Es lo que hoy
falta y lo que hace el problema difícil de descubrir.

### Fuera de alcance

- Otros backends (`java.util.logging`, `tinylog`). La abstracción los admitiría, pero no se
  implementan sin un consumidor real que los pida.
- Mover `logstash-logback-encoder` fuera de `compile`. Es una decisión de empaquetado separable,
  y mezclarla aquí ampliaría el radio de un cambio que ya toca el punto de emisión.

## Consecuencias

### Positivas

- La librería funciona igual sobre Logback y sobre Log4j2, sin configuración del consumidor.
- Desaparece la pérdida total y silenciosa de eventos bajo Log4j2.
- La elección de backend deja de ser una suposición no escrita.

### Negativas

- Una indirección más en el camino de todos los eventos; hay que medir que no cueste.
- Dos recursos de configuración equivalentes que mantener sincronizados.
- `StdlogEmitter`, que `ADR-0006`/`0007`/`0008` se comprometieron a no tocar, se modifica. Está
  justificado —es el único punto donde vive el acoplamiento— pero conviene decirlo.

### Riesgos

- **Divergencia silenciosa entre las dos salidas.** Es el riesgo principal. Mitigación: un test
  que emita el mismo evento por los dos caminos y **compare el JSON resultante**; sin él, este
  ADR no se puede dar por cumplido.
- **Coste por evento** de la indirección. Mitigación: medirlo, como en `ADR-0010`.
- **Un consumidor con los dos backends en el classpath.** Gana el que SLF4J haya enlazado, que
  es exactamente lo que reporta la detección; el aviso de arranque lo hace visible.

## Impacto

- **Módulos afectados:** `StdlogEmitter` y una abstracción nueva en `appbrain.stdlog.core`, más
  una autoconfiguración que instala el escritor detectado. Ningún módulo de instrumentación se
  toca.
- **Contratos públicos:** el JSON emitido no cambia bajo Logback. Bajo Log4j2 pasa de no existir
  a existir. Se añade un recurso `stdlog/log4j2-stdlog.xml`.
- **Dependencias:** `log4j-api` en `provided`.
- **Compatibilidad:** aditiva para quien use Logback.

## Validación

**`BackendOutputEquivalenceTest` — la condición de aceptación central.** Compara salidas
**reales**, no aproximaciones: el lado Logback emite de verdad y se serializa el `Marker` con el
encoder de logstash; el lado Log4j2 construye un `LogEvent` con el mismo `ObjectMessage` y lo
renderiza con un `JsonTemplateLayout` real. Verifica el JSON completo, el anidamiento, los tipos
y el orden de claves.

**Se comprobó que ese test detecta una divergencia real.** Sustituyendo `ObjectMessage` por
`MapMessage` en el escritor de Log4j2, el test falla y muestra exactamente el reordenamiento
alfabético que el spike había anticipado:

```
expected: {"event":"CONTROLLER_HTTP","direction":"IN","request_id":"abc-123",...}
but was:  {"direction":"IN","elapsedMs":12,"event":"CONTROLLER_HTTP",...}
```

Sin esa comprobación, el test podría haber estado pasando sin verificar nada — que es
precisamente el modo de fallo que este ADR identificó como su riesgo principal.

**`StdlogBackendTest`** (7 tests): detección de Logback en la suite; el escritor se resuelve una
sola vez; el backend se anuncia al arrancar; con un backend no soportado el aviso es `WARN` y no
`INFO`, dice «no soportado» y remite a este ADR; y sobre todo, **el respaldo no pierde el
evento** —degrada el formato pero conserva `event` y `request_id`— y sigue llevando el
`Throwable`.

Suite completa: **309 tests, 0 fallos**, `mvn clean verify` con el trinquete de cobertura, en
JDK 17 y JDK 25. `log4j-api` verificado en scope `provided`: no llega al consumidor.

Portado a `spring-boot-3.x` (`ADR-0005`).

## Relación con Otros ADR

- **Corrige el hallazgo F-13** de la auditoría en su parte inexacta (el arrastre de Logback) y
  atiende la parte de fondo que ese hallazgo no llegó a nombrar: la pérdida silenciosa de
  eventos bajo otro backend.
- **Extiende `ADR-0003`**, que decidió Logback + logstash-encoder como mecanismo de salida JSON;
  este ADR lo mantiene como camino por defecto y le añade un segundo camino equivalente.
- Aplica el principio de `ADR-0011` (no perder datos en silencio) al nivel del backend.
- Sigue el patrón de fachada estática configurada al arrancar de `ADR-0010`, `ADR-0011` y
  `ADR-0013`.
