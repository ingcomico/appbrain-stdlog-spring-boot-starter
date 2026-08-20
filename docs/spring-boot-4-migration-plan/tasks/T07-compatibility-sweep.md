# T07 - Barrido de incompatibilidades

## Objetivo
Resolver el resto de cambios de Boot 4 sin mezclar problemas.

## Orden
1. Imports / packages.
2. APIs de Spring Boot.
3. Auto-configuración.
4. HTTP clients.
5. Jackson.
6. Micrometer / Observability.
7. Servlet / WebFlux.
8. Tests.
9. Maven / plugins.

## Regla
Resolver por categoría y hacer commits pequeños.

Ejemplos:
```text
chore: migrate build to Spring Boot 4
fix: migrate rest client customizers
fix: adapt stdlog autoconfiguration to Boot 4
```

## Done
- `mvn clean test` verde.
