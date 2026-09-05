# ADR-0011: El logging nunca rompe el request, y nunca falla en silencio

## Estado

Aceptado

> Implementado.

## Restricción previa: no perder funcionalidad

Este ADR queda sujeto al principio de que **el esquema emitido es un contrato**: ningún cambio
puede dejar de emitir un campo que ya emitía. Un cambio que mejora un aspecto a costa de perder
`operation`, `route`, `request_id`, `trace_id` o cualquier otro campo del esquema **no es
aceptable**: o se encuentra otra forma, o no se hace.

Aquí ese principio tiene una consecuencia concreta y poco obvia: **capturar una excepción de
logging y no decir nada también es perder datos**, sólo que sin avisar. Un evento que no se
emite por un fallo silencioso es indistinguible de un evento que no debía emitirse. Por eso
este ADR no trata sólo de no romper el request, sino también de no callar cuando algo se pierde.

## Contexto

- La auditoría técnica (hallazgo F-07) encontró que la protección existe **en la mitad de los
  módulos**:
  - `StdlogWebFilter.emitAll(...)` y `StdlogR2dbcQueryListener.afterQuery(...)` envuelven la
    emisión en `catch (RuntimeException loggingFailure)`, con el comentario «nunca romper el
    request por un fallo de logging»;
  - `ControllerBodyAndOutLoggingFilter`, `StdlogClientDbQueryListener` y
    `StdlogClientHttpInterceptor` **no la tienen**.
- Es el mismo modo de fallo que ya se vio en F-04 con el enmascaramiento: una invariante que hay
  que recordar en cada módulo acaba faltando en alguno. `ADR-0010` lo resolvió llevándola al
  punto único de emisión.
- El punto más expuesto es el bloque `finally` de `ControllerBodyAndOutLoggingFilter`, que corre
  **después** de que la respuesta se haya generado: una excepción allí se propaga y convierte un
  request correcto en un error del cliente. Ejemplos plausibles de causa: un `Content-Type` con
  un charset inválido llegando a `Charset.forName`, un extractor que recibe algo inesperado, un
  `ObjectMapper` en un estado no previsto.
- Los dos `catch` que ya existen **descartan la excepción en silencio**: sólo dejan un
  comentario. Si el logging falla de forma sistemática, nadie se entera nunca.
- Restricción de diseño relevante: `StdlogEmitter.emit(...)` recibe el payload **ya construido**.
  Un `try/catch` dentro del emitter protege la emisión, pero **no** la construcción del payload,
  que ocurre antes en el módulo. Ahí está justamente el riesgo del filtro servlet.

## Alternativas Consideradas

### Alternativa 1 — Añadir el `try/catch` a los tres módulos que faltan

Ventajas:

- Cubre construcción y emisión, que es donde está el riesgo real.
- Cambio pequeño y localizado.

Desventajas:

- Sigue siendo una invariante que hay que recordar. Un módulo nuevo puede volver a olvidarla,
  que es exactamente cómo se llegó al estado actual.
- Cinco sitios que mantener coherentes.

### Alternativa 2 — Sólo en `StdlogEmitter`

Ventajas:

- Un único punto, aplicado por construcción a todos los módulos presentes y futuros.
- Mismo patrón que `ADR-0010`.

Desventajas:

- **Insuficiente por sí sola**: no protege la construcción del payload, que es donde está el
  caso más probable (el `finally` del filtro servlet). Daría una falsa sensación de cobertura.

### Alternativa 3 — Emitter guardado *más* bloque guardado en cada punto de instrumentación

El emitter protege la emisión como red de seguridad universal; además, cada punto de
instrumentación envuelve su bloque completo de «construir payload + emitir».

Ventajas:

- Cubre las dos mitades del riesgo: construcción y emisión.
- La red del emitter garantiza un mínimo incluso si un módulo futuro olvida su envoltorio.
- Un módulo que sólo use la API estándar queda protegido sin hacer nada.

Desventajas:

- Dos capas de protección: hay que documentar qué cubre cada una para que no se confundan.

### Alternativa 4 — API diferida: `emit(logger, level, Supplier<Map>)`

Pasar el payload como `Supplier` para que su construcción ocurra dentro de la región protegida
del emitter.

Ventajas:

- Un solo punto que cubre construcción y emisión.

Desventajas:

- Cambia la forma de llamar en los cinco módulos, con el riesgo de tocar código estable que
  `ADR-0006`/`0007`/`0008` se comprometieron a no modificar.
- El coste de una lambda por evento en el camino de todos los eventos.
- Se puede adoptar más adelante sin romper nada; no hace falta para cerrar F-07.

## Decisión

Se adopta la **Alternativa 3**.

### Reglas derivadas

**1. Invariante.** Ningún fallo del logging puede alterar el resultado de la operación
instrumentada: ni el request HTTP, ni la query, ni la llamada saliente. Es una propiedad de la
librería, no una cortesía de cada módulo.

**2. Red de seguridad en `StdlogEmitter`.** La emisión se envuelve en el punto único. Cubre a
todos los módulos por construcción, incluidos los futuros.

**3. Bloque guardado en cada punto de instrumentación.** Cada módulo envuelve su bloque de
«construir payload + emitir», porque la construcción ocurre antes de llegar al emitter y la red
del punto 2 no la alcanza. Afecta a `ControllerBodyAndOutLoggingFilter`,
`StdlogClientDbQueryListener` y `StdlogClientHttpInterceptor`; `StdlogWebFilter` y
`StdlogR2dbcQueryListener` ya lo tienen.

**4. Qué se captura.** `RuntimeException` y `Error` de los que tenga sentido recuperarse. **No**
se capturan ni `ThreadDeath` ni `OutOfMemoryError` ni `StackOverflowError`: tragarse un
`OutOfMemoryError` para salvar un log es peor que el fallo que se intenta evitar. Se capturará
`RuntimeException` y `LinkageError` (classpath incompleto, que es un fallo de configuración
recuperable), y se dejarán pasar el resto de `Error`.

**5. Nunca en silencio.** Todo fallo capturado se registra en un logger propio,
`appbrain.stdlog.internal`, a nivel `WARN`, con la excepción. **No** se usa el logger `stdlog`,
para no arriesgar recursión si el fallo está en la propia ruta de emisión.

**6. Con freno.** Si el logging falla de forma sistemática, el aviso del punto 5 se convertiría
en la inundación que se quería evitar. Se emite el **primer** fallo y después uno cada
potencia de diez (1.º, 10.º, 100.º…), incluyendo el total acumulado. Así un fallo permanente es
visible sin ser ruidoso.

**7. Los `catch` silenciosos existentes se corrigen.** Los de `StdlogWebFilter` y
`StdlogR2dbcQueryListener` pasan a usar el mecanismo de los puntos 5 y 6. Hoy descartan la
excepción sin dejar rastro, y eso es perder datos sin avisar.

### Relación con el principio de no perder funcionalidad

Este ADR **no puede** dejar de emitir ningún campo: sólo añade protección alrededor de la
emisión. Su efecto sobre el esquema es nulo. Y en la dirección contraria, mejora el
cumplimiento del principio: hoy un fallo de logging hace desaparecer un evento entero sin
dejar rastro, y con el punto 5 eso pasa a ser visible.

## Consecuencias

### Positivas

- Un fallo de logging deja de poder convertir un request correcto en un error del cliente.
- La invariante se cumple por construcción para cualquier módulo futuro.
- Un fallo sistemático de logging pasa de invisible a visible y acotado.
- Elimina la asimetría entre módulos antiguos y nuevos que F-07 documentó.

### Negativas

- Dos capas de protección que hay que entender para no duplicarlas ni confiar en la equivocada.
- Un bloque `try` más en tres módulos: algo de ruido en el código.
- El contador del punto 6 es estado estático mutable, como ya ocurre con la configuración de
  `StdlogMasker`. Refuerza un patrón que `AI_CONTEXT.md` lista como pendiente de revisar.

### Riesgos

- **Enmascarar un bug real de la librería.** Si el logging falla siempre, el request funciona
  pero no hay observabilidad. Mitigación: exactamente los puntos 5 y 6; el aviso existe para eso.
- **Capturar demasiado.** Un `catch` demasiado amplio podría ocultar un problema del entorno.
  Mitigación: la lista explícita del punto 4, y no capturar los `Error` no recuperables.
- **Recursión** si el propio aviso falla. Mitigación: logger distinto y un mensaje sin formateo
  que dependa del payload.

## Impacto

- **Módulos afectados:** `StdlogEmitter` (red de seguridad), `ControllerBodyAndOutLoggingFilter`,
  `StdlogClientDbQueryListener`, `StdlogClientHttpInterceptor` (bloque guardado), y los `catch`
  existentes de `StdlogWebFilter` y `StdlogR2dbcQueryListener` (dejar de ser silenciosos).
- **Contratos públicos:** sin cambios en `StdlogProperties` ni en la forma del JSON. Aparece un
  logger nuevo, `appbrain.stdlog.internal`, que el consumidor puede silenciar; conviene
  documentarlo en el `README`.
- **Observabilidad:** mejora. Un fallo de logging pasa a ser observable.
- **Rendimiento:** un bloque `try` sin excepción tiene coste nulo en la JVM. El contador sólo se
  toca en el camino de fallo.
- **Compatibilidad:** aditiva.

## Validación

`StdlogFailsafeTest` (11 tests): se traga `RuntimeException` y `LinkageError`; **no** se traga
`OutOfMemoryError` ni `StackOverflowError`, y esos ni siquiera cuentan como fallo capturado; la
variante con valor devuelve el fallback; el aviso lleva la excepción adjunta; **no** usa el
logger `stdlog` (comprobado explícitamente, porque ahí estaría la recursión); el freno registra
sólo el 1.º, 10.º, 100.º y 1000.º de mil fallos, y el aviso incluye el total acumulado.

`LoggingFailureDoesNotBreakTheOperationTest` (4 tests): fuerza una `RuntimeException` durante la
**construcción** del payload en cada punto de instrumentación —con una lista que revienta al
iterarla, colocada donde cada módulo la recorre— y comprueba que la operación termina bien: el
request no se convierte en error, la query del consumidor no se rompe, y la llamada saliente
devuelve su respuesta original y legible.

**Los tests se verificaron en A/B**: desactivando la red, los 4 fallan; restaurándola, los 4
pasan. Merece la pena anotar por qué se hizo esa comprobación: en la primera versión, dos de
esos tests pasaban **con y sin** la red, porque el detonante no llegaba a dispararse
—`headersFrom(...)` sale antes si el request no tiene cabeceras, y con `logAllRequestHeaders=true`
la allowlist ni se mira—. Un test verde que no prueba nada es peor que no tenerlo.

Suite completa: **284 tests, 0 fallos**, `mvn clean verify` con el trinquete de cobertura de
`ADR-0016`, en JDK 17 y JDK 25.

Portado a `spring-boot-3.x` (`ADR-0005`): código idéntico, no depende de APIs que difieran
entre majors.

## Relación con Otros ADR

- **Resuelve el hallazgo F-07** de la auditoría técnica.
- **Sigue el patrón de `ADR-0010`**: una invariante transversal se implementa en el punto único
  de emisión en lugar de repetirse por módulo. Este ADR añade el matiz de que el punto único
  **no basta** cuando el riesgo está en la construcción del payload.
- Relacionado con `ADR-0012`, que trata la otra asimetría servlet/reactivo detectada en la misma
  auditoría, y con `ADR-0005`, cuya paridad funcional ambos restauran.
- Relacionado con `ADR-0016`: la CI verifica la no-regresión en las dos ramas y los dos JDK.
