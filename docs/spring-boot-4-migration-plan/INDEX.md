# Spring Boot 3 -> 4 Migration Plan

Objetivo: migrar `appbrain-stdlog-spring-boot-starter` a Spring Boot 4 sin perder soporte para Spring Boot 3.

## Estrategia de ramas

- `spring-boot-3.x`: mantenimiento permanente de Boot 3.
- `main`: línea activa compatible con Boot 4.
- `feature/spring-boot-4-migration`: rama temporal para ejecutar la migración antes del merge.

## Orden de ejecución

1. [T01 - Congelar Boot 3](tasks/T01-freeze-boot3.md)
2. [T02 - Crear rama Boot 3](tasks/T02-create-boot3-branch.md)
3. [T03 - Preparar rama de migración](tasks/T03-create-migration-branch.md)
4. [T04 - Actualizar plataforma a Boot 4](tasks/T04-upgrade-platform.md)
5. [T05 - Corregir APIs HTTP](tasks/T05-http-client-api.md)
6. [T06 - Revisar auto-configuración](tasks/T06-autoconfiguration.md)
7. [T07 - Resolver incompatibilidades restantes](tasks/T07-compatibility-sweep.md)
8. [T08 - Crear pruebas de compatibilidad](tasks/T08-tests.md)
9. [T09 - Validar en aplicación real](tasks/T09-real-app-validation.md)
10. [T10 - Publicar y mergear Boot 4](tasks/T10-release.md)
11. [T11 - Política de mantenimiento](tasks/T11-maintenance-policy.md)

## Regla de alcance

Durante esta migración no mezclar refactors grandes, nuevas features ni rediseños. Primero lograr paridad funcional en Boot 4; después optimizar.

## Preflight del repositorio

- Rama actual: `main`.
- Spring Boot actual: `3.3.0`.
- Java actual: `17`.
- Antes de saltar a Boot 4, revisar si conviene subir primero la rama Boot 3 a la última línea `3.5.x`, como recomienda la guía oficial de migración.
- El cambio de código esperado más visible está en los imports de `RestClientCustomizer` y `RestTemplateCustomizer`, que pasan de `org.springframework.boot.web.client` a `org.springframework.boot.restclient`.
- La auto-configuración ya usa `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
