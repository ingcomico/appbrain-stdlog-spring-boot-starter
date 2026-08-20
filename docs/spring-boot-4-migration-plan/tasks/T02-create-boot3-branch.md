# T02 - Crear rama permanente Boot 3

## Objetivo
Mantener soporte de Spring Boot 3 sin bloquear la evolución a Boot 4.

## Acciones
```bash
git checkout main
git checkout -b spring-boot-3.x
git push -u origin spring-boot-3.x
```

## Política
- Solo fixes, seguridad y correcciones críticas.
- No borrar mientras existan consumidores Boot 3.
- Evitar nuevas features salvo necesidad fuerte.

## Done
- Rama existe local y remotamente.
