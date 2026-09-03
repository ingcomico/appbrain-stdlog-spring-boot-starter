# Instrucciones para Claude

## Fuente Canónica

`AI_CONTEXT.md` es la fuente canónica de contexto compartido para los agentes de IA que trabajan en este repositorio.

Antes de analizar, diseñar o modificar código:

1. Leer `AI_CONTEXT.md`.
2. Usar documentación adicional solo como contexto complementario.
3. Reportar cualquier contradicción entre documentación, código y ADRs antes de asumir cuál es correcta.

## Uso de Codebase Memory

Usar primero las herramientas MCP directas de `codebase-memory-mcp`.

Preferir Codebase Memory para entender:

- estructura,
- módulos,
- dependencias,
- callers,
- implementaciones,
- relaciones,
- impacto potencial de cambios.

No usar CLI, shell, búsquedas masivas o lectura indiscriminada de archivos como primera opción.

Usar esas alternativas únicamente si las herramientas MCP directas no están disponibles o no pueden responder.

## Política de Frescura

Antes de usar el grafo para decisiones importantes:

1. Consultar `check_index_coverage`.
2. Si la frescura es `metadata_match`, continuar normalmente.
3. Si es `metadata_changed`, `missing` o `not_tracked`:
  - esperar primero a `auto_watch`,
  - volver a comprobar,
  - reindexar manualmente solo si continúa desactualizado.

Evitar reindexados completos innecesarios.

## Cambios Arquitectónicos

Antes de realizar cambios arquitectónicos, estructurales o que afecten contratos públicos:

1. Leer `AI_CONTEXT.md`.
2. Consultar Codebase Memory.
3. Revisar ADR relevantes en `docs/adr/`.
4. Identificar consumidores, implementaciones y dependencias afectadas.
5. Evaluar compatibilidad e impacto.
6. Determinar si el cambio requiere actualizar `AI_CONTEXT.md` o crear/sustituir un ADR.

## Mantenimiento del Contexto

Regla vigente (`ADR-0004`): **la actualización de documentación viaja en el mismo commit/PR que el cambio que la afecta.** No es "proponer" ni "al cerrar la tarea": si el cambio toca uno de los puntos de abajo, el diff de la PR incluye la doc correspondiente (o una justificación explícita de por qué no aplica).

Obligan a actualizar documentación en el mismo cambio:

- límites entre módulos o responsabilidades;
- contratos públicos (`StdlogProperties`, `StdlogCustom`, `StdlogExcluded`, `META-INF/spring/*`, `stdlog/logback-spring-stdlog.xml`, forma del JSON emitido);
- alta/baja/bump mayor de dependencias;
- comportamiento observable (orden de filtros, modo, correlación, exclusión);
- plataforma soportada (versión de Spring Boot, nivel de Java, toolchain).

Qué se actualiza: `AI_CONTEXT.md` si cambia el estado vigente; un ADR nuevo o sustituido si cumple "Cuándo crear un ADR"; `README.md` si cambia algo visible para el consumidor; `CLAUDE.md` / `AGENTS.md` si cambia el proceso.

Antes de aplicar la actualización:

1. Comparar el estado final del código con `AI_CONTEXT.md`.
2. Usar Codebase Memory para detectar drift.
3. Reportar contradicciones antes de asumir cuál fuente es correcta.

No modificar documentación arquitectónica únicamente para hacerla coincidir con una implementación accidental: primero decidir si la correcta es la implementación o la documentación.

## Modelo de Ramas

Dos ramas permanentes con paridad funcional (`ADR-0005`):

- `main` — Spring Boot 4.x, desarrollo activo, referencia de diseño.
- `spring-boot-3.x` — Spring Boot 3.5.x, soporte para consumidores Boot 3.

Toda feature o fix transversal entra por `main` y se porta a `spring-boot-3.x` inmediatamente tras el merge (cherry-pick, o reimplementación equivalente). Un cambio no está "cerrado" hasta estar en ambas ramas cuando aplica a las dos. Las ramas de trabajo se borran al mergear.
