# T11 - Política de mantenimiento

## Ramas
```text
main               -> Boot 4 / desarrollo activo
spring-boot-3.x    -> Boot 3 / mantenimiento
```

## Bugs comunes
Corregir normalmente en `master` y portar a Boot 3 cuando aplique.

```bash
git checkout spring-boot-3.x
git cherry-pick <commit>
```

Si las ramas divergieron demasiado, implementar el fix equivalente manualmente.

## Regla
No intentar mantener una única base de código con reflection/profiles solo para esconder diferencias entre majors, salvo que el costo real de dos ramas lo justifique después.
