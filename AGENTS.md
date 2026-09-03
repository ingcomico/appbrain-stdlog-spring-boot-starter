# Instrucciones para Agentes

## Fuente Canónica

`AI_CONTEXT.md` es la fuente canónica de contexto compartido para todos los agentes de IA que trabajan en este repositorio.

Antes de analizar, diseñar o modificar código:

1. Leer `AI_CONTEXT.md`.
2. Usar la documentación adicional solo como contexto complementario.
3. Si existe una contradicción entre documentación, código o ADRs, reportarla antes de asumir cuál es correcta.

## Uso de Codebase Memory

Usar primero las herramientas MCP directas de `codebase-memory-mcp`.

Preferir Codebase Memory para descubrir:

- estructura del código,
- dependencias,
- callers,
- implementaciones,
- relaciones entre módulos,
- impacto potencial de cambios.

No usar CLI, shell, `grep` o lectura masiva de archivos como primera opción para descubrimiento estructural.

Usar esas alternativas únicamente si el MCP directo no está disponible o no puede responder la consulta.

## Política de Frescura

Antes de usar el grafo para decisiones importantes:

1. Consultar `check_index_coverage`.
2. Si la frescura es `metadata_match`, usar el grafo normalmente.
3. Si es `metadata_changed`, `missing` o `not_tracked`:
  - esperar primero a `auto_watch`,
  - comprobar nuevamente,
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
2. Usar Codebase Memory para verificar que la documentación siga reflejando la implementación real.
3. Reportar contradicciones o drift.
4. Proponer la actualización correspondiente cuando sea necesario.

No modificar documentación arquitectónica únicamente para justificar una implementación accidental.
