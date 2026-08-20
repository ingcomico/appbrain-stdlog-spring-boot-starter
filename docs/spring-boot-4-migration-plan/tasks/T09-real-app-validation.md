# T09 - Validar en una aplicación real

## Objetivo
Detectar problemas que un test aislado del starter no revela.

## Estrategia
Usar primero la aplicación Boot 4 menos crítica disponible.

## Validar
- Startup.
- Logs esperados.
- Requests salientes.
- Headers/contexto propagado.
- Errores HTTP.
- Métricas/observability si aplica.
- Ausencia de duplicación de logs/interceptores.

## Release previo sugerido
```text
4.0.0-RC1
```

o snapshot interno antes del estable.

## Done
- Una aplicación real opera correctamente con el starter Boot 4.
