# ADR-0005: Dos líneas de mantenimiento (Spring Boot 3.x y Spring Boot 4) con paridad funcional

## Estado

Aceptado

## Contexto

- `ADR-0001` decidió migrar la línea principal a Spring Boot 4.1 y dejó la última build Boot 3 "como referencia… no se le añaden features nuevas". Descartó explícitamente la alternativa de "mantener dos líneas en paralelo" por coste de mantenimiento.
- Tras la migración, el requisito real cambió: se necesita seguir dando soporte a consumidores en Spring Boot 3 **con las mismas capacidades** que la línea Boot 4, no sólo parches de seguridad.
- Se creó la rama permanente `spring-boot-3.x` (prevista en `docs/spring-boot-4-migration-plan/tasks/T02`) y se le llevó, sin usar APIs de Spring Boot 4:
  - la correlación de tracing (`StdlogTraceCorrelation`, `trace_id`/`span_id`) — `ADR-0002`;
  - el respeto de `stdlog.controller.maxRequestBodyBytes` y el orden `LOWEST_PRECEDENCE` del filtro;
  - Spring Boot `3.5.16` y toolchain JDK 25 con bytecode Java 17.
- Se verificó que el código de negocio (emitter, correlación, builders de payload) no tiene acople a APIs exclusivas de Boot 4: las dos líneas difieren sólo en `pom.xml` y en líneas `import` de 5 clases, más recursos `META-INF` y `logback-spring-stdlog.xml`.
- Esto contradice la letra de `ADR-0001`, que hay que corregir.

## Alternativas Consideradas

### Alternativa 1 — Dos ramas permanentes con paridad funcional, cherry-pick como mecanismo

`main` = línea Spring Boot 4 (desarrollo activo). `spring-boot-3.x` = línea Spring Boot 3 (Spring Boot 3.5.x). Toda feature o fix que aplique a ambas se implementa en `main` y se porta a `spring-boot-3.x` (cherry-pick, o reimplementación equivalente si divergieron). Se publican dos artefactos.

Ventajas:

- Los consumidores en Boot 3 no pierden capacidades.
- Cada rama usa el "dialecto" de su versión de Spring Boot sin trucos de reflection/perfiles.
- El coste real hoy es bajo: la superficie que difiere son ~5 archivos.

Desventajas:

- Cada cambio transversal se hace (y se prueba) dos veces.
- Riesgo de divergencia si una rama acumula cambios que la otra no.

### Alternativa 2 — Sólo `main` (Boot 4); Boot 3 congelada (lo que decía `ADR-0001`)

Ventajas:

- Una sola rama de desarrollo.

Desventajas:

- Los consumidores en Boot 3 se quedan sin tracing y sin mejoras futuras; fuerza una migración que puede no estar en su control inmediato.

### Alternativa 3 — Una sola base de código con perfiles Maven / reflection

Ventajas:

- Sin duplicación de commits.

Desventajas:

- `pom` y wiring más complejos; reflection frágil para ocultar diferencias de major.
- Coste desproporcionado para ~5 archivos de diferencia (ver análisis en la conversación de diseño).

## Decisión

Se adopta la **Alternativa 1**, que **sustituye** la regla de `ADR-0001` "a la línea Boot 3 no se le añaden features nuevas".

Reglas derivadas:

- **Ramas permanentes:**
  - `main` — Spring Boot 4.x, desarrollo activo. Es la referencia de diseño.
  - `spring-boot-3.x` — Spring Boot 3.5.x. No se borra mientras existan consumidores Boot 3.
  - No hay otras ramas permanentes. Las ramas de trabajo se borran al mergear.
- **Paridad funcional:** las dos líneas ofrecen las mismas capacidades y la misma configuración `stdlog.*`, el mismo JSON de salida y el mismo comportamiento observable. Sólo difieren en la versión de Spring Boot, el binding Jackson (3 vs 2), la versión de `logstash-logback-encoder` (9 vs 8), y las líneas `import` / recursos que eso implica.
- **Flujo de cambios:**
  - toda PR de feature o de fix transversal entra por `main`;
  - inmediatamente después del merge se porta a `spring-boot-3.x` (cherry-pick; si hay conflicto de `import`/dependencia se resuelve a mano; si divergieron mucho, reimplementación equivalente);
  - un cambio no se considera "cerrado" hasta estar en las dos ramas cuando aplica a ambas.
- **Cambios específicos de una línea** (p. ej. un bump de Spring Boot 4.x, o un fix que sólo afecta a Jackson 3) no se portan; se documenta el porqué en el commit.
- **Toolchain común:** ambas ramas compilan con `<release>17>` (bytecode Java 17) y build en JDK 17–25.
- **Publicación:** dos artefactos/coordenadas, uno por línea (la política de versionado/coordenadas queda pendiente, ver `AI_CONTEXT.md` "Decisiones Pendientes").
- Este flujo se rige además por `ADR-0004` (la documentación de cada cambio viaja en su PR, en las dos ramas).

## Consecuencias

### Positivas

- Los consumidores en Boot 3 tienen las mismas capacidades que los de Boot 4.
- La línea Boot 3 puede seguir subiendo dentro de 3.5.x (seguridad, fixes de Spring).
- Sin complejidad de perfiles/reflection en el `pom`.

### Negativas

- Doble trabajo de porte y de CI para cada cambio transversal.
- La política depende de disciplina: si un cambio no se porta, las líneas divergen en silencio.

### Riesgos

- Divergencia acumulada que haga los cherry-pick inviables. Mitigación: portar inmediatamente tras cada merge, no en lote.
- Que la línea Boot 3 quede sin fecha de fin y se mantenga indefinidamente. Mitigación: revisar en cada release si sigue habiendo consumidores Boot 3 y fijar un EOL cuando deje de haberlos.

## Impacto

- **Módulos afectados:** ninguno directamente; afecta cómo se propaga cualquier cambio futuro.
- **Contratos públicos:** se garantiza que son idénticos entre líneas.
- **Dependencias:** `spring-boot-3.x` fija Spring Boot 3.5.x, Jackson 2, `logstash-logback-encoder` 8.x; `main` fija Spring Boot 4.x, Jackson 3, encoder 9.x.
- **Compatibilidad:** un consumidor elige la línea según su propia versión de Spring Boot.
- **Despliegue:** dos publicaciones.
- **Proceso:** `AI_CONTEXT.md` incorpora la sección "Modelo de Ramas"; `docs/spring-boot-4-migration-plan/tasks/T11` queda subsumido por este ADR.

## Validación

- Estado real: existen `main` (`spring.boot.version = 4.1.0`) y `spring-boot-3.x` (`spring.boot.version = 3.5.16`), ambas con `StdlogTraceCorrelation` idéntica y suites en verde (177 tests).
- `ADR-0001` actualizado en su sección "Relación con Otros ADR" para apuntar aquí.
- `AI_CONTEXT.md` actualizado: sección "Modelo de Ramas" y limpieza de referencias a ramas de trabajo ya borradas.

## Relación con Otros ADR

- Sustituye: la regla de `ADR-0001` de que la línea Boot 3 se congela y no recibe features. El resto de `ADR-0001` (la migración de `main` a Boot 4) sigue vigente.
- Relacionado con: `ADR-0002` y `ADR-0003` (las capacidades que se mantienen en paridad), `ADR-0004` (cómo se documenta cada porte).
