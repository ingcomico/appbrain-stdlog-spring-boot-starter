# T06 - Revisar auto-configuración del starter

## Objetivo
Garantizar que el starter siga activándose correctamente bajo Boot 4.

## Checklist
- `@AutoConfiguration`.
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- `@ConditionalOnClass`.
- `@ConditionalOnMissingBean`.
- `@ConfigurationProperties`.
- Orden de auto-configuración.
- Dependencias Servlet/WebFlux.
- Beans opcionales.
- Actuator/Micrometer/Observation si aplica.

## Done
- La app inicia con el starter.
- No hay beans duplicados.
- Las condiciones activan/desactivan lo esperado.
