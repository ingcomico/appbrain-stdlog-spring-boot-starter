# T10 - Merge y release Boot 4

## Objetivo
Convertir Boot 4 en la línea principal estable.

## Acciones
```bash
git checkout main
git merge feature/spring-boot-4-migration
git push origin main

git tag v4.0.0
git push origin v4.0.0
```

## Convención recomendada
```text
starter 3.x.x -> Spring Boot 3.x
starter 4.x.x -> Spring Boot 4.x
```

## Done
- `main` compatible con Boot 4.
- Release/tag estable publicado.
