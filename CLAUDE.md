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

Antes de cerrar una tarea que modifique arquitectura, contratos públicos, dependencias importantes o responsabilidades:

1. Comparar el estado final del código con `AI_CONTEXT.md`.
2. Usar Codebase Memory para detectar drift.
3. Reportar contradicciones o documentación desactualizada.
4. Proponer la actualización correspondiente cuando sea necesaria.

No modificar documentación arquitectónica únicamente para hacerla coincidir con una implementación accidental.
