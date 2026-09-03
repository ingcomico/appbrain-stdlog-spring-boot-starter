# ADR-0010: Enmascaramiento de datos sensibles en los eventos emitidos

## Estado

Aceptado

## Contexto

- El starter tiene *allow-lists* de headers y de content-type, que limitan **qué** se captura, pero **ninguna** forma de redactar un valor. La auditoría técnica (hallazgo F-04) identificó cinco superficies por las que sale dato sin filtrar:
  - bodies de request y response del controller —y `logRequestBody`/`logResponseBody` vienen en `true`—;
  - bodies de `CLIENT_HTTP` en cuanto `stdlog` está en `DEBUG`;
  - `queryParams`;
  - `db.params` cuando se activa `logParams`;
  - todos los headers salientes si se activa `logAllRequestHeaders`, incluido `Authorization`.
- Un test existente lo dejaba por escrito: `shouldIncludeAllRequestHeadersWhenLogAllEnabled` afirmaba que el valor `Bearer secret` aparecía **en claro** en el log.
- La postura por defecto era «loguear todo» y delegar la seguridad en el consumidor. `AI_CONTEXT.md` ya lo listaba como decisión pendiente. Para una librería transversal instalada en varios servicios, ese default está al revés.
- Restricción de rendimiento: el punto candidato para aplicarlo, `StdlogEmitter`, está en el camino de **todos** los eventos. `ADR` reciente (auditoría F-05/F-06) acaba de eliminar ~9 µs por evento de coste de reflexión; reintroducir un coste comparable por enmascarar sería cambiar un problema por otro.

## Alternativas Consideradas

### Alternativa 1 — Enmascarar en cada módulo que construye payload

Cada módulo redacta lo suyo, donde conoce la semántica del campo (parámetro SQL, header, body JSON).

Ventajas:

- Máxima precisión: se sabe qué es cada valor.
- Sin coste de recorrido genérico.

Desventajas:

- Hay que acordarse en cada módulo nuevo. Es exactamente el modo de fallo que la auditoría documentó en F-07 con el `try/catch` de fail-safety: presente en los módulos reactivos, ausente en los antiguos.
- Cinco implementaciones que mantener sincronizadas.

### Alternativa 2 — Enmascarar en `StdlogEmitter`, punto único

Recorrer el payload antes de emitirlo y sustituir los valores de las claves sensibles.

Ventajas:

- Cubre las cinco superficies de una vez y, por construcción, cualquier módulo futuro: no se puede olvidar.
- Un solo sitio que auditar y que testear.

Desventajas:

- Coste de recorrido en el camino de todos los eventos.
- El emitter es una fachada estática sin acceso al contexto de Spring: hay que transferirle la configuración al arrancar.

### Alternativa 3 — Cambiar el default a bodies desactivados

Ventajas:

- Elimina la superficie más grande sin necesidad de enmascarar.

Desventajas:

- **Quita funcionalidad en lugar de protegerla.** El valor principal del starter es ver el body del request; apagarlo por defecto degrada el producto para resolver un problema que el enmascarado resuelve sin pérdida.
- Es un cambio de comportamiento observable que rompe a todos los consumidores actuales.

## Decisión

Se adopta la **Alternativa 2**, con los bodies **manteniendo su default actual** (`true`): el enmascaramiento reduce el riesgo sin quitar funcionalidad, que es justo lo que la Alternativa 3 no lograba.

### Reglas derivadas

**1. Punto único.** `StdlogEmitter` invoca `StdlogMasker.mask(...)` antes de emitir. Ningún módulo enmascara por su cuenta.

**2. Orden dentro del emitter.** Comprobación de nivel → enmascarado → enriquecimiento de tracing → log. El enmascarado va **después** del chequeo de nivel para no pagarlo en eventos que no se van a emitir; es la misma lección de la auditoría (F-06) que ya se aplicó al enriquecimiento.

**3. Dos pasadas, porque los bodies no llegan homogéneos.** De los seis puntos donde un body entra al payload, **sólo uno** —el body JSON parseado de la vía servlet— llega como `Map`; los otros cinco llegan como `String`. Por eso:

- *estructural*: recorre `Map` y `List` a cualquier profundidad y sustituye el valor de toda clave sensible;
- *textual*: sobre los valores de las claves `body`, `statement`, `url` y `fullPath`, enmascara además los pares `"clave": valor` (JSON) y `clave=valor` (query o formulario).

La pasada textual es **best-effort declarado**: opera sobre texto y no sobre un árbol, precisamente para funcionar también con bodies truncados o con JSON inválido, que es cuando un parser fallaría.

**4. Comparación por igualdad sobre la clave normalizada**, no por inclusión. La clave se pasa a minúsculas y se le quitan `_ - . espacio`, de modo que `card_number`, `cardNumber` y `Card-Number` coinciden entre sí. Comparar por subcadena estaba descartado: `shipping` contiene `pin`.

**5. Lista incorporada y ampliable.** El starter trae claves habituales (`password`, `secret`, `token`, `authorization`, `apiKey`, `cvv`, `cardNumber`, `cookie`, `pin`, `otp`, `ssn` y variantes). `stdlog.masking.additional-keys` añade sobre ella; `stdlog.masking.keys` la reemplaza. `stdlog.masking.placeholder` cambia el sustituto (`***` por defecto) y `stdlog.masking.enabled=false` lo desactiva por completo.

**6. Activo desde el primer evento.** La configuración por defecto está instalada en el campo estático, no en la autoconfiguración, así que también rige durante el arranque, antes de que exista el contexto de Spring.

**7. Cribado obligatorio antes de la pasada textual.** Se comprueba en una sola pasada, indexando por los tres primeros caracteres de cada clave, si el texto menciona alguna. Sin este cribado el coste medido era de ~118 µs por evento sobre un body de 1,2 KB, que habría sido inaceptable. El cribado puede sobre-coincidir (`car` dispara con «carrier»); es deliberado y seguro: la decisión autoritativa la sigue tomando la comparación de clave normalizada, así que una falsa alarma sólo cuesta trabajo de más y nunca puede provocar una fuga.

### Fuera de alcance

- **Detección por patrón sobre el valor** (formato de tarjeta, JWT, email). Añade falsos positivos y coste por campo; se puede revisar más adelante si la lista por nombre se queda corta.
- **Enmascarado semántico de SQL** (distinguir literales de identificadores en `db.statement`). Hoy se cubre con la pasada textual de pares.

## Consecuencias

### Positivas

- Las cinco superficies quedan cubiertas de una vez, y cualquier módulo futuro lo hereda sin hacer nada.
- El starter deja de tener una postura por defecto insegura, sin perder capacidad de diagnóstico.
- El coste está acotado y medido (ver Validación).

### Negativas

- Un campo sensible con un nombre no previsto sigue saliendo: la protección es por nombre de clave, no por contenido. El consumidor debe añadir sus propias claves.
- **Cambio de comportamiento observable**: un consumidor que hoy ve `Authorization` en claro pasará a ver `***`. Es intencionado y es el objetivo del ADR, pero debe anunciarse.
- `StdlogMasker` mantiene configuración en estado estático mutable, consistente con la fachada estática que ya usan `StdlogCustom` y `StdlogEmitter`. Refuerza un patrón que `AI_CONTEXT.md` lista como decisión pendiente de revisar.

### Riesgos

- **Falsa sensación de seguridad**: creer que el log está saneado porque hay enmascaramiento. Mitigación: documentar explícitamente que es por nombre de clave y best-effort en texto.
- **Sobre-enmascarado** por una clave de negocio que coincide con la lista. Mitigación: `stdlog.masking.keys` permite reemplazar la lista entera.
- **Coste en bodies grandes**. Mitigación: el cribado, más los topes de `maxRequestBodyBytes`/`maxResponseBodyBytes` que ya existían.

## Impacto

- **Módulos afectados:** ninguno se modifica. El cambio vive en `appbrain.stdlog.core.StdlogMasker` (nuevo), un gancho en `StdlogEmitter` y una autoconfiguración nueva.
- **Contratos públicos:** `StdlogProperties` gana la sección `stdlog.masking.*`. El JSON emitido cambia de contenido —no de forma— para las claves sensibles.
- **Dependencias:** ninguna nueva. Sólo JDK.
- **Seguridad:** es el objeto del ADR.
- **Compatibilidad:** aditiva en configuración; el comportamiento observable cambia para valores sensibles, que es lo pretendido.

## Validación

- `StdlogMaskerTest` (18 tests): pasada estructural, anidamiento en mapas y listas, normalización de grafías, el falso positivo `shipping`/`pin`, no mutación del payload del llamante, conservación del orden de claves, pares JSON y de formulario, JSON truncado, variantes con separador, y que la sobre-coincidencia del cribado no altera el resultado.
- `StdlogEmitterTest` +3: el enmascarado ocurre en la emisión real, sobre headers, body, `db.params` y `db.statement`.
- `StdlogMaskingAutoConfigurationTest` (5 tests): lista incorporada sin configurar, `additional-keys` suma, `keys` reemplaza, `placeholder` y `enabled=false`.
- `StdlogClientHttpInterceptorTest.shouldIncludeAllRequestHeadersWhenLogAllEnabled` **se actualizó**: antes afirmaba `Bearer secret` en claro y ahora afirma `***`. Ese cambio de aserción es la prueba directa de que F-04 queda cerrado.
- **Coste medido** (JDK 25, 200.000 iteraciones, A/B contra la implementación sin cribado):

  | escenario | sin cribado | con cribado |
  |---|---:|---:|
  | payload típico, nada que enmascarar | 5.110 ns | **842 ns** |
  | body JSON de 1,2 KB | 118.650 ns | **3.258 ns** |
  | sólo recorrido estructural | 679 ns | 514 ns |
  | enmascarado desactivado | — | 6 ns |

  Para dimensionarlo: la misma auditoría eliminó ~9.100 ns por evento de reflexión de OpenTelemetry (F-05), así que el balance neto de los dos cambios sigue siendo muy favorable.
- Suite completa: 259 tests, 0 fallos, en JDK 17 y JDK 25.

## Relación con Otros ADR

- **Resuelve el hallazgo F-04** de la auditoría técnica y completa lo iniciado con F-02 (que eliminó la fuga de campos de formulario hacia `queryParams`).
- Relacionado con `ADR-0016` (la CI verifica los umbrales de cobertura de este código nuevo) y con `ADR-0005` (se porta a `spring-boot-3.x`: el código es agnóstico del major).
- Deja pendiente la decisión sobre la fachada estática que `AI_CONTEXT.md` ya registra, y a la que este ADR añade un caso más.
