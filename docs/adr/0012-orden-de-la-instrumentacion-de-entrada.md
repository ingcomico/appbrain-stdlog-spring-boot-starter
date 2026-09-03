# ADR-0012: Orden de la instrumentación de entrada HTTP y paridad servlet/reactivo

## Estado

Propuesto

> Decisión pendiente de ratificar. La implementación no ha empezado: este ADR se escribe
> **antes** de tocar código, a petición explícita, para que la decisión quede discutida y no
> derivada de la implementación.

## Restricción previa: no perder funcionalidad

Este ADR queda sujeto al principio de que **el esquema emitido es un contrato**: ningún cambio
puede dejar de emitir un campo que ya emitía. `operation`, `route`, `request_id` y la
correlación de tracing son el núcleo del valor de la librería; un cambio que los degrade para
ganar otra cosa **no es aceptable**.

Ese principio es el que ordena este ADR: la primera formulación del problema —en el informe de
auditoría— daba por supuesto un intercambio («se ganan los rechazos de seguridad, pero
`operation` no estará resuelto en esos requests»), y **ese supuesto era falso**. Al medirlo se
comprobó que no hay intercambio ninguno. Ver "Validación".

## Contexto

- Los dos filtros de entrada están en **extremos opuestos** de su cadena, y nadie lo decidió:

  | Filtro | Orden | Posición efectiva |
  |---|---|---|
  | `ControllerBodyAndOutLoggingFilter` (servlet) | `Ordered.LOWEST_PRECEDENCE` (`Integer.MAX_VALUE`) | el más **interno** |
  | `StdlogWebFilter` (WebFlux) | `Ordered.HIGHEST_PRECEDENCE + 10` | el más **externo** |

- Consecuencia medida (hallazgo F-08): en la vía servlet, cuando algo corta la cadena **antes**
  de llegar al filtro de stdlog, **no se emite ningún evento**. Ni `CONTROLLER_HTTP`, ni el
  evento extra de error. Quedan invisibles:
  - los `401` y `403` de Spring Security,
  - los rechazos de CORS,
  - cualquier fallo de un filtro que el consumidor haya puesto por fuera.

  En WebFlux, los mismos casos **sí** se emiten, porque su filtro es el más externo.

- Es justo el tipo de evento que más importa en producción, y contradice la paridad funcional
  que `ADR-0005` da por establecida entre las dos líneas.
- `RequestIdMdcFilter` ya está en `Integer.MIN_VALUE`, o sea el más externo de todos: el
  `request_id` **sí** se genera para un request rechazado por seguridad. Sólo falta el evento.

## Alternativas Consideradas

### Alternativa 1 — Dejarlo como está y documentar la asimetría

Ventajas:

- Riesgo cero de regresión.

Desventajas:

- Los rechazos de seguridad siguen invisibles en servlet, que es el stack mayoritario.
- Deja `ADR-0005` incumplido en un punto observable, y `ADR-0016` no puede verificar una paridad
  que la propia librería rompe a propósito.

### Alternativa 2 — Mover el filtro servlet a la posición más externa posible

Que el filtro de logging envuelva la cadena de seguridad, igual que hace el reactivo.

Ventajas:

- Restaura la paridad real entre las dos vías.
- Hace visibles los rechazos de seguridad y de CORS.
- `elapsedMs` pasa a incluir el tiempo de la cadena de filtros, que es lo que el cliente
  realmente espera.
- **Medido: no pierde ningún campo** en los requests normales (ver "Validación").

Desventajas:

- `ContentCachingRequestWrapper` pasa a envolver también la cadena de seguridad. Es el riesgo
  real de este cambio y hay que validarlo: el *form login* de Spring Security lee parámetros del
  body, y ese wrapper cambia cómo se consume el stream.
- `ContentCachingResponseWrapper` pasa a envolver la cadena de seguridad, así que cambia quién
  confirma la respuesta y cuándo se copia el body.

### Alternativa 3 — Añadir un segundo filtro, externo, sólo para lo que no llega a la cadena

Ventajas:

- No toca el filtro actual ni sus wrappers.

Desventajas:

- Dos filtros que deben coordinarse para no emitir dos eventos por el mismo request.
- Más piezas para el mismo resultado que la Alternativa 2 consigue con un cambio de orden.
- Duplica la lógica de exclusión por path.

## Decisión

Se adopta la **Alternativa 2**.

### Reglas derivadas

**1. Regla de paridad.** En las dos vías, la instrumentación de entrada se coloca **lo más
externa posible** dentro de su cadena, justo por dentro del filtro que establece el
`request_id`. Cualquier divergencia futura de esta regla entre las dos vías exige un ADR.

**2. Orden concreto en servlet.** `ControllerBodyAndOutLoggingFilter` pasa de
`Ordered.LOWEST_PRECEDENCE` a un orden inmediatamente posterior a `RequestIdMdcFilter`
(`Integer.MIN_VALUE`), de modo que el `request_id` ya esté en el MDC cuando el filtro de
logging empiece. Se usará `Integer.MIN_VALUE + 100`, dejando hueco intermedio deliberado.

**3. `operation` y `route` no se degradan.** Se mantienen exactamente como hoy en los requests
que llegan al handler, porque el filtro los lee de atributos del request en su bloque `finally`,
que corre después del `DispatcherServlet` sea cual sea la posición del filtro respecto a la
cadena de seguridad. Está medido, no supuesto.

**4. `route` con respaldo en los requests que nunca llegan al handler.** En un request rechazado
por seguridad no hay patrón de `HandlerMapping`, así que `route` se rellena con
`método + URI del request`, que es lo que ya hace `StdlogWebFilter.resolveRoute(...)` en la vía
reactiva. El evento nuevo llega con `route`, no vacío. Esto **añade** un dato donde antes no
había evento; no quita ninguno.

**5. `operation` ausente en esos requests es correcto, y no es una pérdida.** Un request que
Spring Security rechaza nunca seleccionó un handler: no existe ninguna `operation` que informar,
e inventarla sería peor. No hay pérdida respecto al estado actual porque hoy **esos requests no
emiten nada en absoluto**. El campo pasa de «no existe el evento» a «el evento existe y no
incluye `operation`», que es estrictamente más información.

**6. La exclusión por path sigue evaluándose en el filtro** y por tanto también aplica a los
requests rechazados por seguridad: un `401` en `/actuator/**` no generará ruido de nivel INFO,
pero sí el evento `WARN` correspondiente, porque `WARN`/`ERROR` nunca se suprimen
(`StdlogEmitter`).

### Condición de aceptación de la implementación

El riesgo de esta decisión no está en el orden sino en los wrappers. La implementación **no se
considera válida** sin verificar, con tests, que al mover el filtro:

- un *form login* de Spring Security (`application/x-www-form-urlencoded` leído por la cadena de
  seguridad) sigue autenticando correctamente con `ContentCachingRequestWrapper` por fuera;
- la respuesta se confirma y se copia correctamente cuando la cadena de seguridad la escribe
  directamente sin llegar al `DispatcherServlet`;
- los requests asíncronos (`DeferredResult`, `CompletableFuture`) siguen emitiendo un único par
  IN/OUT.

Si alguna de las tres no se cumple, la decisión se revisa: la Alternativa 3 queda como plan de
contingencia, porque no toca los wrappers.

## Consecuencias

### Positivas

- Los `401`/`403` de Spring Security y los rechazos de CORS pasan a ser observables en servlet.
- Las dos vías se comportan igual, y `ADR-0005` deja de estar incumplido en este punto.
- `elapsedMs` mide lo que el cliente percibe, no sólo la parte interna.
- Ningún campo del esquema se pierde.

### Negativas

- Aumenta el volumen de eventos en aplicaciones con mucho tráfico rechazado (por ejemplo, un
  endpoint público bajo ataque de credenciales). Mitigable con `excluded-path-patterns`, aunque
  el `WARN` no se suprime por diseño.
- Los wrappers de body pasan a envolver más código del consumidor, lo que amplía la superficie
  de interacción con filtros de terceros.

### Riesgos

- **El *form login* de Spring Security con el request envuelto.** Es el riesgo principal.
  Mitigación: es condición de aceptación explícita, con test.
- **Un filtro de terceros que dependa de recibir el request original** y no un wrapper.
  Mitigación: el modo sin captura de body (`logRequestBody=false` y `logResponseBody=false`) no
  envuelve nada, así que existe una salida sin wrappers para quien la necesite.
- **Cambio de volumen de logs inesperado** para consumidores actuales. Mitigación: anunciarlo
  como cambio de comportamiento observable en el `README`.

## Impacto

- **Módulos afectados:** `StdlogAutoConfiguration` (el orden del `FilterRegistrationBean`) y
  `ControllerBodyAndOutLoggingFilter` (respaldo de `route`). La vía reactiva **no se toca**: ya
  cumple la regla.
- **Contratos públicos:** sin cambios en `StdlogProperties` ni en la forma del JSON. Cambia el
  **comportamiento observable**: aparecen eventos que antes no existían.
- **Compatibilidad:** aditiva en datos; el volumen de logs puede subir.
- **Observabilidad:** es el objeto del ADR.
- **Seguridad:** los rechazos de autenticación y autorización pasan a dejar rastro, lo que es
  deseable para auditoría. Los bodies de esos requests quedan sujetos al enmascaramiento de
  `ADR-0010`, que es justo lo que hace seguro emitirlos.

## Validación

**Medición previa a la decisión.** Se ejecutó la misma cadena de filtros en las dos posiciones,
con un servlet que simula el `DispatcherServlet` poblando los atributos `operation`/`route`, y
un filtro que simula a Spring Security cortando con `401`:

| posición del filtro | request | resultado |
|---|---|---|
| interno (estado actual) | normal | `status=200`, `operation=PagosController#pagar`, `route=POST /pay` |
| interno (estado actual) | `401` de seguridad | **sin evento** |
| externo (propuesto) | normal | `status=200`, `operation=PagosController#pagar`, `route=POST /pay` |
| externo (propuesto) | `401` de seguridad | `status=401`, `operation=null`, `route=null` |

Las dos conclusiones que ordenan este ADR:

1. **En los requests normales el resultado es idéntico.** No hay degradación de `operation` ni
   de `route`. El supuesto contrario, que aparecía en el informe de auditoría, era falso: el
   filtro lee esos valores de atributos del request en su `finally`, que corre después del
   `DispatcherServlet` independientemente de dónde esté el filtro respecto a la cadena de
   seguridad.
2. **En el request rechazado se gana un evento donde no había ninguno.** El `route=null` de la
   última fila es lo que motiva la regla 4: se rellenará con `método + URI`.

**Antes de aceptar la implementación**, además de las tres condiciones de aceptación:

- suite servlet completa en verde como prueba de no-regresión, en JDK 17 y JDK 25 (`ADR-0016`);
- un test que compruebe que un `401` cortado por un filtro externo emite `CONTROLLER_HTTP` y el
  evento `WARN`, con `route` relleno;
- un test que compruebe que un request normal sigue emitiendo `operation` y `route` idénticos;
- portado a `spring-boot-3.x` (`ADR-0005`).

## Relación con Otros ADR

- **Resuelve el hallazgo F-08** de la auditoría técnica, y **corrige el análisis** que ese
  informe hacía del intercambio: no existe.
- **Da cumplimiento a `ADR-0005`** en un punto donde la paridad estaba declarada pero no se
  cumplía, y establece la regla de colocación que `ADR-0008` no fijó al introducir la vía
  reactiva.
- Relacionado con `ADR-0010`: emitir los bodies de requests rechazados por seguridad sólo es
  aceptable porque el enmascaramiento ya está en su sitio.
- Relacionado con `ADR-0011`, la otra asimetría servlet/reactivo de la misma auditoría, y con
  `ADR-0016`, que verifica la no-regresión en las dos ramas y los dos JDK.
