# ADR-0004: La documentación se actualiza en el mismo commit/PR que el cambio que la afecta

## Estado

Aceptado

## Contexto

- La documentación arquitectónica del repositorio (`AI_CONTEXT.md`, `docs/adr/`, `README.md`, `CLAUDE.md`, `AGENTS.md`) es la fuente compartida de contexto para personas y agentes de IA. `CLAUDE.md` la declara "fuente canónica".
- `CLAUDE.md` y `AI_CONTEXT.md` ya piden, en su sección "Mantenimiento del Contexto", comparar el código con la documentación "antes de cerrar una tarea" y "proponer" la actualización. En la práctica esto ha fallado:
  - `ADR-0001` quedó afirmando que la línea Spring Boot 3 se congela y que "mantener dos líneas en paralelo" fue descartado, cuando la decisión real terminó siendo dos líneas con paridad funcional (ver `ADR-0005`).
  - `AI_CONTEXT.md` mantuvo referencias a la rama `feature/spring-boot-4-migration` después de que se mergeó a `main` y se borró.
- El problema de raíz: la regla actual (a) está atada a "cerrar la tarea", un momento difuso; (b) dice "proponer", no "aplicar"; (c) no está formalizada como decisión con su porqué, así que se relaja con facilidad.
- Un commit o PR es la unidad natural de cambio y de revisión. Atar la actualización de documentación a esa unidad la hace verificable.

## Alternativas Consideradas

### Alternativa 1 — La actualización de documentación viaja en el mismo commit/PR que el cambio

Todo commit/PR que modifique arquitectura, contratos públicos, dependencias, comportamiento observable o responsabilidades incluye, en el mismo cambio, la actualización de la documentación afectada (o, si amerita, el ADR nuevo/sustituido). La revisión de PR lo verifica explícitamente.

Ventajas:

- La documentación nunca queda más de un commit por detrás del código.
- El "porqué" se captura mientras está fresco.
- Es verificable en review: el diff muestra código y doc juntos.
- El historial de git enlaza cambio y doc en el mismo punto.

Desventajas:

- PRs algo más grandes.
- Requiere criterio para decidir qué cambios "cuentan" (se mitiga reutilizando la taxonomía de "Cuándo crear un ADR").

### Alternativa 2 — Actualizar la documentación en lote antes de cada release

Ventajas:

- PRs de código más pequeños.

Desventajas:

- Drift acumulado entre releases; el estado documentado no es confiable en el día a día.
- Reconstruir el porqué de varios cambios a la vez es arqueología.
- En un repo sin cadencia de release fija, "antes del release" nunca llega.

### Alternativa 3 — Statu quo (criterio individual, "antes de cerrar la tarea")

Ventajas:

- Cero proceso nuevo.

Desventajas:

- Ya falló de forma medible (ver Contexto).

## Decisión

Se adopta la **Alternativa 1**.

Reglas derivadas:

1. **Cambios que obligan a actualizar documentación en el mismo commit/PR** (misma taxonomía que "Cuándo crear un ADR" de `AI_CONTEXT.md`):
   - cambios en límites entre módulos o responsabilidades;
   - cambios en contratos públicos (`StdlogProperties`, `StdlogCustom`, `StdlogExcluded`, `META-INF/spring/*`, `stdlog/logback-spring-stdlog.xml`, forma del JSON emitido);
   - alta/baja/bump mayor de dependencias tecnológicas;
   - cambios de comportamiento observable (orden de filtros, políticas de modo, correlación, exclusión);
   - cambios en la plataforma soportada (versión de Spring Boot, nivel de Java, toolchain).
2. **Qué se actualiza**, según el cambio:
   - `AI_CONTEXT.md` si cambia el estado vigente (plataforma, dependencias, contratos, arquitectura, límites);
   - un ADR nuevo (o sustitución de uno existente) si la decisión cumple los criterios de "Cuándo crear un ADR";
   - `README.md` si cambia algo visible para el consumidor;
   - `CLAUDE.md` / `AGENTS.md` si cambia el proceso de trabajo.
3. **No obligan** a actualizar documentación: bugfixes sin impacto arquitectónico, refactors internos, cambios cosméticos, cambios en tests que no reflejan un cambio de contrato.
4. **Verificación en review**: toda PR declara si toca alguno de los puntos de (1); si sí, el diff debe incluir la doc correspondiente o una justificación explícita de por qué no aplica. Un revisor no aprueba una PR que incumpla esto.
5. **Excepción operativa**: si por tamaño conviene separar la doc en su propio commit, ese commit va en la **misma PR** y referencia al commit de código.
6. La documentación no se edita sólo para que coincida con una implementación accidental (regla ya vigente en `CLAUDE.md`): primero se decide si la implementación o la documentación es la correcta.

## Consecuencias

### Positivas

- El estado documentado del repo es confiable en cualquier commit de las ramas permanentes.
- Menos arqueología para reconstruir decisiones.
- La revisión de PR tiene un criterio objetivo sobre documentación.

### Negativas

- PRs más grandes y con más superficie de revisión.
- Fricción adicional en cambios que están en la frontera de "¿esto cuenta?".

### Riesgos

- Que la verificación en review se vuelva un trámite formal sin sustancia. Mitigación: la lista de (1) es corta y concreta.
- Que se creen ADRs de más para cambios menores. Mitigación: el punto (3) y los criterios de "Cuándo crear un ADR".

## Impacto

- **Módulos afectados:** ninguno (decisión de proceso).
- **Contratos públicos:** ninguno.
- **Dependencias:** ninguna.
- **Compatibilidad / observabilidad / seguridad / despliegue:** sin impacto.
- **Proceso:** `CLAUDE.md` y `AGENTS.md` incorporan la regla operativa y referencian este ADR. Aplica a las dos ramas permanentes (`main`, `spring-boot-3.x`).

## Validación

- `CLAUDE.md` y `AGENTS.md` actualizados con la regla y el puntero a este ADR.
- Este ADR se porta a `spring-boot-3.x` junto con el resto de `docs/adr/`.
- Evidencia del problema que motiva la decisión: correcciones aplicadas a `ADR-0001` y `AI_CONTEXT.md` en la misma tanda que este ADR.

## Relación con Otros ADR

- Relacionado con: todos los ADR (define cómo se mantienen).
- Se apoya en: la sección "Cuándo crear un ADR" y "Mantenimiento del Contexto Compartido" de `AI_CONTEXT.md`.
- Sustituye: nada (formaliza y refuerza una práctica que ya estaba enunciada de forma débil).
