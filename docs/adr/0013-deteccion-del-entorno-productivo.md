# ADR-0013: Detección del entorno productivo

## Estado

Aceptado

## Restricción previa: no perder funcionalidad

Sujeto al principio de que **el esquema emitido es un contrato**. Este ADR no toca el esquema:
cambia *cuántos* eventos se emiten en producción, no *qué campos* lleva cada uno. Y lo hace en
la dirección de emitir menos ruido, que es el propósito declarado del modo desde el principio.

## Contexto

- `stdlog.mode` tiene tres valores y `AUTO` es el default. `AUTO` consulta **únicamente** la
  variable de entorno `STDLOG_MODE` y, si no está definida, resuelve **no productivo**:

  ```java
  private static boolean isProdAuto() {
      String forced = System.getenv(ENV_STDLOG_MODE);
      ...
      return false;   // sin señal -> NO productivo
  }
  ```

- Consecuencia (hallazgo F-10 de la auditoría): un despliegue a producción en el que nadie
  exportó `STDLOG_MODE` corre con `logOnlyOnFailureInProd` y `logOnlySlowOrFailureInProd`
  **inertes**, con bodies completos y volumen máximo de log. El fallo es **silencioso** —nada
  avisa— y va en la dirección cara: más coste de ingesta, más superficie de datos sensibles.
- No se consulta el perfil de Spring, que es donde la práctica totalidad de las aplicaciones
  declara en qué entorno están. La librería ignora la señal que todo el mundo ya configura y
  exige una variable propia que nadie sabe que existe.
- **`StdlogModeResolver.isProd(props)` se invoca por evento**, en cuatro puntos calientes: el
  interceptor HTTP saliente, el filtro de `WebClient` y los listeners JDBC y R2DBC. Cada
  invocación hace un `System.getenv(...)`. El modo no puede cambiar durante la vida del
  proceso, así que resolverlo una vez es además la optimización que la auditoría anotó aparte.
- Circunstancia relevante: **la librería todavía no está en producción**. Cambiar un default es
  gratis ahora y deja de serlo en cuanto haya consumidores.

## Alternativas Consideradas

### Alternativa 1 — Consultar los perfiles de Spring

`AUTO` mira `spring.profiles.active` contra una lista configurable de perfiles productivos.

Ventajas:

- Usa la señal que las aplicaciones ya declaran; no hay nada nuevo que recordar.
- Distingue correctamente desarrollo (`dev`, `local`) de producción.

Desventajas:

- No resuelve el caso en que **no hay ninguna señal**: sin perfiles activos y sin variable, hay
  que decidir igualmente, y ahí sigue el agujero de F-10.

### Alternativa 2 — Mantener sólo `STDLOG_MODE`, pero fallar al arrancar si falta

Ventajas:

- Imposible desplegar sin decidirlo.

Desventajas:

- Un starter de **logging** que impide arrancar la aplicación es una cura peor que la
  enfermedad: convierte un problema de observabilidad en una caída.
- Rompe a todo consumidor existente y a cualquier arranque local.

### Alternativa 3 — Invertir el default: sin señal, productivo

Ventajas:

- Falla del lado seguro. El coste de equivocarse pasa de «fuga de datos y factura de ingesta» a
  «me faltan logs en local», que se detecta en segundos y se corrige con una property.

Desventajas:

- Sola, degrada la experiencia local: quien arranca sin perfil ve menos logs y puede pensar que
  la librería no funciona.

### Alternativa 4 — Componer la 1 y la 3

Los perfiles como señal positiva; el default seguro sólo cuando **no hay ninguna señal**.

Ventajas:

- Cubre la cadena entera sin huecos: cada caso tiene una respuesta y ninguna es un accidente.
- La desventaja de la 3 casi desaparece: el arranque local típico declara un perfil (`dev`,
  `local`), que la regla de perfiles resuelve como no productivo antes de llegar al default.

Desventajas:

- Más reglas que documentar y que entender.

## Decisión

Se adopta la **Alternativa 4**: las alternativas 1 y 3 no compiten, se complementan. La 1 aporta
la señal; la 3 decide cuando no la hay.

### Cadena de resolución de `AUTO`

Se evalúa en orden y la primera que responde gana:

1. **`stdlog.mode`** con valor explícito (`PROD` / `NON_PROD`) — decisión del consumidor, manda
   siempre.
2. **`STDLOG_MODE`** en el entorno (`PROD`, `NON_PROD` o `NONPROD`) — se conserva por
   compatibilidad y porque es útil para forzar el modo sin tocar la configuración.
3. **Perfiles activos de Spring** que coincidan con `stdlog.prod-profiles`
   (default: `prod`, `production`, `prd`, `pro`; comparación sin distinguir mayúsculas)
   → **productivo**.
4. **Hay perfiles activos y ninguno coincide** → **no productivo**. Es el caso de `dev`,
   `local`, `test`, `qa`: la aplicación dijo dónde está y no es producción.
5. **No hay ninguna señal** —ni property, ni variable, ni perfiles activos— → **productivo**.
   Es el cambio de default respecto al estado anterior, y es el que cierra F-10.

**El modo resuelto se anuncia al arrancar**, con el motivo, en el logger
`appbrain.stdlog.internal` que introdujo `ADR-0011`. Un default que decide por ti no puede ser
además invisible; la regla 5 sólo es aceptable si se ve.

### Resolución una sola vez

El modo no cambia durante la vida del proceso, así que se resuelve al arrancar y se instala en
`StdlogModeResolver`, con el mismo patrón de fachada estática que ya usan `StdlogMasker`
(`ADR-0010`) y `StdlogFailsafe` (`ADR-0011`). Deja de hacerse un `System.getenv(...)` por
evento en los cuatro puntos calientes.

Antes de que la autoconfiguración corra —durante el arranque— rige la lógica anterior basada
sólo en la variable de entorno, de modo que nunca hay un estado sin definir.

### Por qué la regla 5 y no lo contrario

El error tiene coste asimétrico. Equivocarse hacia «no productivo» en producción significa
bodies completos, volumen máximo y más superficie de datos sensibles, y **no se nota**.
Equivocarse hacia «productivo» en local significa ver menos logs, se nota de inmediato y se
corrige con una línea. Cuando un default puede fallar en dos direcciones, se elige la que avisa.

## Consecuencias

### Positivas

- Desaparece el modo de fallo silencioso de F-10.
- La librería usa la señal que las aplicaciones ya declaran, en lugar de exigir una propia.
- Se elimina un `System.getenv(...)` por evento en cuatro puntos calientes.
- El modo deja de ser invisible: se anuncia al arrancar con su motivo.

### Negativas

- **Cambio de comportamiento observable**: una aplicación sin perfiles ni variable pasa de no
  productivo a productivo, y por tanto de todo el volumen a sólo fallos y queries lentas. Es el
  objetivo del ADR, pero hay que anunciarlo.
- Una regla más que documentar en el `README`.
- `StdlogModeResolver` gana estado estático mutable, como `StdlogMasker` y `StdlogFailsafe`.
  Refuerza un patrón que `AI_CONTEXT.md` sigue listando como pendiente de revisar.

### Riesgos

- **Un perfil productivo con nombre no previsto** (`produccion`, `live`, `prd-eu`) haría caer en
  la regla 4 y resolver no productivo. Mitigación: `stdlog.prod-profiles` es configurable, y el
  aviso de arranque dice qué modo se resolvió y por qué, así que el error es visible.
- **Ruido local reducido** para quien arranca sin perfil. Mitigación: la regla 4 cubre el caso
  habitual, y el aviso de arranque explica qué pasó.

## Impacto

- **Módulos afectados:** `StdlogModeResolver` y una autoconfiguración nueva que instala el modo
  resuelto. Los cuatro puntos de llamada no se tocan.
- **Contratos públicos:** `StdlogProperties` gana `stdlog.prod-profiles`. `stdlog.mode` y
  `STDLOG_MODE` conservan su significado.
- **Compatibilidad:** aditiva en configuración; el comportamiento por defecto cambia en el caso
  «ninguna señal».
- **Observabilidad:** en producción baja el volumen, que es el propósito del modo.
- **Seguridad:** menos superficie de datos sensibles en producción por defecto.

## Validación

- Tests de la cadena completa: cada una de las cinco reglas, incluida la precedencia entre
  ellas, y la comparación de perfiles sin distinguir mayúsculas.
- Test de que el modo resuelto se anuncia una vez al arrancar, con su motivo.
- Test de que antes de la autoconfiguración rige la lógica anterior, sin estado indefinido.
- Suite completa en verde en JDK 17 y JDK 25 (`ADR-0016`).
- Portado a `spring-boot-3.x` (`ADR-0005`).

## Relación con Otros ADR

- **Resuelve el hallazgo F-10** de la auditoría técnica, e incorpora la optimización que la
  auditoría anotó aparte (resolver el modo una vez en lugar de por evento).
- Sigue el patrón de fachada estática configurada al arrancar de `ADR-0010` y `ADR-0011`, y
  reutiliza el logger `appbrain.stdlog.internal` que introdujo `ADR-0011`.
- Relacionado con `ADR-0016` (la CI verifica la no-regresión en las dos ramas y los dos JDK).
