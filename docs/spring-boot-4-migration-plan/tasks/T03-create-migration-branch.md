# T03 - Crear rama temporal de migración

## Objetivo
Evitar que `master` quede inestable durante la migración.

## Acciones
```bash
git checkout main
git pull
git checkout -b feature/spring-boot-4-migration
```

## Done
- Toda la migración ocurre aquí hasta validar Boot 4.
