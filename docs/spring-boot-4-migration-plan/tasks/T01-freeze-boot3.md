# T01 - Congelar estado estable de Boot 3

## Objetivo
Asegurar un punto reproducible antes de tocar la migración.

## Acciones
1. Ejecutar build y tests actuales.
2. Confirmar que el starter funciona en Boot 3.
3. Identificar la versión estable actual.
4. Crear tag del último estado estable.

```bash
git checkout main
git pull
git tag v3.0.0
git push origin v3.0.0
```

## Done
- Build verde.
- Tests verdes.
- Tag remoto creado.
