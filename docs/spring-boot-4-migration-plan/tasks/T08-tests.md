# T08 - Pruebas de compatibilidad

## Objetivo
Evitar validar únicamente por compilación.

## Validar en Boot 4
- Contexto Spring inicia.
- Auto-configuración se carga.
- Interceptor HTTP se registra.
- `RestClient` funciona.
- `RestTemplate` funciona si sigue soportado.
- No hay conflictos de beans.
- Propiedades del starter se enlazan correctamente.

## Boot 3
La rama `spring-boot-3.x` conserva su suite existente y debe seguir verde.

## Done
- Tests automatizados cubren startup + integración HTTP mínima.
