# ADR-0001: Migración del starter a Spring Boot 4 y Jackson 3

> **Nota de rama (`spring-boot-3.x`):** este ADR describe la migración de la línea `main`. Esta rama **no** se migra: se queda en Spring Boot 3.5.x, Jackson 2 (`com.fasterxml.jackson.core`) y `logstash-logback-encoder` 8.x. Se conserva aquí como contexto y porque `ADR-0005` (que sí aplica a esta rama) depende de él.

## Estado

Aceptado

## Contexto

- El starter (`appbrain:appbrain-stdlog-spring-boot-starter:1.0.0`) se construyó sobre la línea Spring Boot 3.x (`spring.boot.version = 3.3.0`) y Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`).
- Spring Boot 4.x cambia el BOM de dependencias, mueve tipos públicos de paquete y adopta Jackson 3 (`tools.jackson.core`) como binding JSON de referencia. Spring Framework 7 (transitivo) también modifica APIs usadas por el starter (por ejemplo `HttpHeaders`).
- El starter se integra por autoconfiguración y registra filtros, interceptores, customizers de cliente HTTP y un `EnvironmentPostProcessor`; depende de tipos que Spring Boot 4 reubicó:
  - `RestClientCustomizer` / `RestTemplateCustomizer`: de `org.springframework.boot.web.client` a `org.springframework.boot.restclient` (nuevo módulo `spring-boot-restclient`).
  - `EnvironmentPostProcessor`: de `org.springframework.boot.env` a `org.springframework.boot`.
- Consumidores del starter que sigan en Spring Boot 3 no pueden usar una build compilada contra Spring Boot 4 (incompatibilidad binaria de los tipos movidos y de Jackson).
- No existía ADR previo; la decisión de baseline solo vivía en el historial de commits (`58c8c0c chore: upgrade Boot 3 baseline`, luego `61dc1c0 chore: migrate starter to Spring Boot 4`).
- `AI_CONTEXT.md` registraba esto como decisión pendiente: "Definir política de mantenimiento/compatibilidad entre la línea Spring Boot 3 y la rama actual de migración a Spring Boot 4".

## Alternativas Consideradas

### Alternativa 1 — Migrar a Spring Boot 4 en la línea principal y discontinuar la línea Boot 3

El artefacto pasa a compilarse y publicarse contra Spring Boot 4.1.0 / Jackson 3. La línea Boot 3 deja de recibir cambios nuevos.

Ventajas:

- Una sola base de código y un solo flujo de build/publicación.
- Alineación con el stack soportado a futuro por Spring.
- Adopción de Jackson 3, que es el binding que Spring Boot 4 trae y prueba.

Desventajas:

- Rompe a los consumidores que aún están en Spring Boot 3; requieren quedarse en la última versión Boot 3 del starter.
- Obliga a Java 17+ como mínimo (ya era el `release` declarado, pero ahora también lo exigen el encoder logstash 9 y el resto del stack).

### Alternativa 2 — Mantener dos líneas en paralelo (rama/artefacto por versión de Boot)

Publicar `...-boot3` y `...-boot4`, o ramas `3.x` y `4.x`, con backports.

Ventajas:

- Los consumidores migran a su ritmo.

Desventajas:

- Doble mantenimiento: dos matrices de dependencias, dos suites de CI, backports manuales de cada fix.
- Riesgo de divergencia de comportamiento entre líneas.
- Coste desproporcionado para el tamaño y la madurez actual del starter (versión `1.0.0`, sin base de consumidores amplia documentada).

### Alternativa 3 — Permanecer en Spring Boot 3 hasta su fin de soporte

Ventajas:

- Cero trabajo inmediato; sin ruptura para consumidores.

Desventajas:

- Deuda acumulada: la migración se vuelve más grande y más urgente al acercarse el EOL.
- El starter quedaría inutilizable para aplicaciones nuevas que arrancan en Spring Boot 4.

## Decisión

Se adopta la **Alternativa 1**: la línea principal del starter se migra a **Spring Boot 4.1.0** y **Jackson 3**, manteniendo **Java 17** como `release` mínimo.

Reglas derivadas:

- `pom.xml` fija `spring.boot.version = 4.1.0`; el BOM de Spring Boot gobierna las versiones transitivas.
- El binding JSON usa `tools.jackson.core:jackson-databind` (Jackson 3). Todo `import com.fasterxml.jackson.*` del código productivo se migra a `tools.jackson.*`.
- Se añade la dependencia explícita `org.springframework.boot:spring-boot-restclient` para `RestClientCustomizer` / `RestTemplateCustomizer`.
- El `maven-compiler-plugin` usa `<release>${java.version}</release>` en lugar de `<source>/<target>`; se eliminan las propiedades `maven.compiler.source/target`.
- El registro de `StdlogVersionEnvironmentPostProcessor` en `META-INF/spring.factories` usa la clave `org.springframework.boot.EnvironmentPostProcessor` (paquete nuevo).
- Las adaptaciones de API forzadas por Spring Framework 7 se aplican en el sitio de uso (por ejemplo `HttpHeaders.entrySet()` → `HttpHeaders.headerSet()` en `StdlogClientHttpInterceptor`).
- **Decisión asociada (orden de filtro):** `ControllerBodyAndOutLoggingFilter` pasa de orden `Integer.MIN_VALUE + 1` a `Ordered.LOWEST_PRECEDENCE`. Se ejecuta como el filtro más externo del scope de observabilidad (envuelve la ejecución completa de la cadena para medir tiempos y capturar bodies), mientras `RequestIdMdcFilter` conserva `Integer.MIN_VALUE` para poblar el MDC antes que nadie.
- ~~La última versión del starter compilada contra Spring Boot 3 queda como referencia para consumidores que aún no migran; no se le añaden features nuevas.~~ **(Anulado por `ADR-0005`: la línea Boot 3 se mantiene activa con paridad funcional en la rama `spring-boot-3.x`.)**

## Consecuencias

### Positivas

- Base de código única, alineada con el stack soportado por Spring hacia adelante.
- Jackson 3 coherente con lo que Spring Boot 4 autoconfigura y prueba.
- Build más simple (`release` en vez de `source/target`).

### Negativas

- Ruptura de compatibilidad para consumidores en Spring Boot 3: deben permanecer en la versión previa del starter hasta migrar.
- Cualquier consumidor con `import com.fasterxml.jackson.*` en integraciones propias con tipos del starter debe adaptarse.
- El cambio de orden de `ControllerBodyAndOutLoggingFilter` altera cómo se compone con otros filtros del consumidor (ahora corre "más adentro" respecto a filtros con precedencia por defecto).

- `StdlogVersionEnvironmentPostProcessor`: la vía efectiva de registro es `META-INF/spring.factories` (clave `org.springframework.boot.EnvironmentPostProcessor`, ya migrada). El archivo `META-INF/spring/org.springframework.boot.EnvironmentPostProcessor` **no lo lee ningún mecanismo de Spring Boot 4** (`EnvironmentPostProcessorsFactory.fromSpringFactories` sólo consulta `spring.factories`; el mecanismo `META-INF/spring/*.imports` exige el sufijo `.imports` y no aplica a `EnvironmentPostProcessor`). Es un archivo inerte heredado; debe eliminarse. No hay doble ejecución (además el post-processor es idempotente).
- APIs de Spring Framework 7 aún no ejercitadas por los tests podrían tener otros cambios latentes (además de `HttpHeaders`).
- `datasource-proxy 1.9`, `logstash-logback-encoder 9.0` y `slf4j` deben permanecer compatibles con el stack transitivo de Boot 4; un bump del BOM puede requerir revalidación.

## Impacto

- **Módulos afectados:** `autoconfig` (imports, registro EPP), `restclient` (customizers, `HttpHeaders`), `web` (`ObjectMapper`, orden de filtro), `core` (`ObjectMapper`), `pom.xml`, recursos `META-INF/*`.
- **Contratos públicos:** cambia el stack mínimo requerido (Spring Boot 4, Java 17); cambia el paquete del binding JSON expuesto indirectamente; cambia el orden efectivo de `ControllerBodyAndOutLoggingFilter`.
- **Dependencias:** BOM Boot 3.3.0 → 4.1.0; Jackson 2 → 3; nuevo `spring-boot-restclient`; logstash-logback-encoder 8.1 → 9.0 (ver ADR-0003).
- **Compatibilidad:** ruptura para consumidores Spring Boot 3.
- **Observabilidad:** sin cambio en el formato de eventos por esta decisión (la correlación de tracing es ADR-0002; el formato JSON es ADR-0003).
- **Despliegue:** los consumidores deben actualizar su propio BOM a Spring Boot 4 antes de subir la versión del starter.
- **Migraciones necesarias:** consumidores en Boot 3 → subir a Boot 4; adaptar imports Jackson propios si los hubiera.

## Validación

- `AI_CONTEXT.md` revisado: la sección "Plataforma y Dependencias" y "Decisiones Técnicas Actuales" ya reflejan Boot 4.1.0, Java 17, Jackson 3 y encoder 9.0 (actualizadas en `0303745`).
- Codebase Memory: verificar que no quedan referencias a `com.fasterxml.jackson` ni a `org.springframework.boot.web.client` / `org.springframework.boot.env.EnvironmentPostProcessor` en `src/main`.
- Consumidores/implementaciones afectados: autoconfiguraciones registradas en `AutoConfiguration.imports` y el EPP en `spring.factories`.
- Estado real del código: `pom.xml` en la rama `feature/spring-boot-4-migration` declara `spring.boot.version = 4.1.0`; el código compila contra `tools.jackson.*` y `org.springframework.boot.restclient.*`.
- Suite de tests ejecutada (2026-09-02, JDK 17, `mvn test`): 177 tests, 0 fallos, `BUILD SUCCESS`.

## Relación con Otros ADR

- Sustituye: ninguna decisión formal previa (no había ADRs); reemplaza el baseline informal Spring Boot 3.
- Relacionado con: `ADR-0002` (correlación de tracing, incorporada en la misma rama), `ADR-0003` (salida JSON con logstash-encoder 9, bump forzado por esta migración).
- **Sustituido parcialmente por `ADR-0005`**: la regla "la última versión Boot 3 queda como referencia y no se le añaden features nuevas" queda anulada. La línea Spring Boot 3 se mantiene de forma activa en la rama `spring-boot-3.x` con paridad funcional. El resto de este ADR (migración de `main` a Boot 4.1 / Jackson 3) sigue vigente.
