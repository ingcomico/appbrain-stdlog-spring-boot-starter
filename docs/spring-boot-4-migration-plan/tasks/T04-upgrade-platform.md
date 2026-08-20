# T04 - Actualizar plataforma a Spring Boot 4

## Objetivo
Cambiar primero la plataforma y usar los errores de compilación como inventario real de incompatibilidades.

## Revisar
- Spring Boot -> 4.1.x.
- Java -> versión requerida/decidida.
- Maven plugins.
- BOM / dependency management.
- Starters y módulos desacoplados en Boot 4.
- Dependencias externas incompatibles.

## Acción clave
Después de actualizar el `pom.xml`, compilar sin intentar arreglar todo previamente.

```bash
mvn clean test
```

## Done
- Existe una lista concreta de errores de compilación/test.
