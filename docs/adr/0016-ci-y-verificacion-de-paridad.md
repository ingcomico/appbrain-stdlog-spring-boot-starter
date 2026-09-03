# ADR-0016: Integración continua y verificación automática de la paridad entre líneas

## Estado

Aceptado

## Contexto

- `ADR-0005` define dos ramas permanentes con **paridad funcional** y un flujo de porte obligatorio (`main` → `spring-boot-3.x` inmediatamente tras el merge). `ADR-0004` obliga a que la documentación viaje en el mismo commit. Ninguna de las dos reglas tiene mecanismo de verificación: dependen enteramente de que la persona (o el agente) se acuerde.
- El repositorio **no tiene integración continua**. No existe `.github/workflows`. Cada afirmación de "suite verde en JDK 17 y JDK 25" que aparece en los ADR y en `AI_CONTEXT.md` se produjo ejecutando los comandos a mano.
- La auditoría técnica del starter mostró el coste concreto de esa ausencia:
  - se cerró `ADR-0008` (soporte WebFlux) con 228 tests en verde y, aun así, el starter **no arrancaba en una aplicación WebFlux pura**: dos autoconfiguraciones servlet sin guarda de classpath. Ningún test lo detectaba porque todos corrían con `spring-webmvc` presente;
  - una tanda de correcciones se documentó como "no verificada en JDK 17 porque no está instalado", cuando sí lo estaba;
  - `spring-boot-3.x` conserva un fichero inerte (`META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor`) que se eliminó de `main`: deriva silenciosa entre líneas que nadie detectó.
- Medición del estado real de la paridad (`git diff --name-only main spring-boot-3.x -- src/`): de **77 ficheros bajo `src/`, 63 son byte-idénticos** y sólo **14 difieren**, y todos por razones conocidas de major (Jackson 2 vs 3, paquete de los customizers, `headerSet()` vs `entrySet()`, encoder 8 vs 9). La paridad no es una aspiración difusa: es una propiedad concreta y comprobable sobre una lista acotada.
- Cobertura actual (JaCoCo, `main`): **89 % de líneas, 89 % de métodos, 67 % de ramas**. El déficit de ramas se concentra en el código de reflexión y de degradación best-effort.

## Alternativas Consideradas

### Alternativa 1 — Sin CI; seguir verificando a mano

Ventajas:

- Coste cero de configuración; nada que mantener.

Desventajas:

- Es el estado actual, y ya falló: `ADR-0008` se dio por cerrado con un defecto que impedía su caso de uso principal.
- Las afirmaciones de validación de los ADR no son auditables: no hay registro de qué se ejecutó ni dónde.
- La paridad de `ADR-0005` puede romperse en silencio y sólo se descubre en el siguiente cherry-pick conflictivo.

### Alternativa 2 — CI de build únicamente (matriz de JDK)

Ejecutar `mvn verify` en JDK 17 y 25 en cada push y cada PR de las dos ramas permanentes.

Ventajas:

- Simple, sin política que discutir.
- Cubre la afirmación más repetida de los ADR ("verde en JDK 17 y JDK 25").

Desventajas:

- No detecta deriva entre ramas, que es el riesgo específico del modelo de `ADR-0005`.
- No impide que la cobertura baje.

### Alternativa 3 — CI de build, trinquete de cobertura y verificación de paridad

Lo anterior más: umbral de cobertura fijado en el nivel actual (falla si baja) y un job que compara las dos ramas contra una lista declarada de ficheros que legítimamente difieren.

Ventajas:

- Convierte `ADR-0005` en una regla verificada en vez de disciplina.
- Convierte la lista de diferencias entre líneas en un artefacto revisable: añadir una diferencia nueva exige un cambio explícito y revisable en la lista.
- El trinquete impide regresiones de cobertura sin exigir subirla ahora, que es trabajo pendiente de la auditoría.

Desventajas:

- Hay que mantener la lista de excepciones cuando aparezca una diferencia legítima nueva.
- El job de paridad quedará en rojo entre el merge en `main` y el porte a `spring-boot-3.x`.

## Decisión

Se adopta la **Alternativa 3**.

### Reglas derivadas

**1. Workflow de build (`.github/workflows/ci.yml`).**

- Matriz `java: [17, 25]`, distribución Temurin.
- Se dispara en `push` a `main` y a `spring-boot-3.x`, y en `pull_request` dirigida a cualquiera de las dos.
- Ejecuta `mvn -B clean verify`, no `test`: `verify` es la fase donde corre el trinquete de cobertura.
- El informe de JaCoCo se publica como artefacto del build.

**2. Trinquete de cobertura (`jacoco:check`, fase `verify`).**

- Umbrales a nivel `BUNDLE`: **85 % de líneas y 65 % de ramas**, es decir, el nivel actual redondeado a la baja.
- Su propósito es **impedir regresiones**, no forzar mejoras. Subir cobertura es trabajo de otros hallazgos de la auditoría.
- Cuando la cobertura suba de forma estable, se sube el umbral en el mismo PR que la sube. El umbral nunca baja sin un ADR que lo justifique.

**3. Verificación de paridad (`.github/workflows/parity.yml`).**

- Compara `origin/main` con `origin/spring-boot-3.x` restringido a `src/`.
- Todo fichero que difiera y **no** esté en `.github/branch-parity-allowlist.txt` hace fallar el job.
- La allowlist es un fichero versionado, con una razón por línea. Añadir una entrada es una decisión revisable en PR, no un efecto colateral.
- **Se dispara sólo en `push` a las ramas permanentes, nunca en `pull_request`.** Es deliberado: según `ADR-0005` el porte ocurre *después* del merge, así que una PR no puede ni debe satisfacer la paridad. El job en rojo sobre `main` significa exactamente «este cambio todavía no está portado», que es el estado que `ADR-0005` define como "no cerrado". Vuelve a verde con el push del porte a `spring-boot-3.x`.

**4. Lectura del rojo.** Un fallo de paridad no es una rotura del código: es deuda de porte. Un fallo de build o de cobertura sí es una rotura. Los dos workflows se mantienen separados para que la distinción sea visible sin abrir los logs.

### Fuera de alcance de este ADR

- Publicación automática del artefacto a un repositorio remoto: sigue siendo decisión pendiente (ver `AI_CONTEXT.md`).
- Detección de deuda de porte a nivel de *commit* (qué commits de `main` no están en `spring-boot-3.x`). La verificación por contenido de fichero cubre el riesgo real —que las líneas diverjan— sin depender de heurísticas sobre historia de git.
- Subir la cobertura de ramas. Es trabajo de los hallazgos pendientes de la auditoría.

## Consecuencias

### Positivas

- Las afirmaciones de validación de los ADR pasan a estar respaldadas por una ejecución registrada y reproducible.
- La paridad de `ADR-0005` se vuelve comprobable, y su lista de excepciones, revisable.
- La cobertura no puede bajar en silencio.
- Un defecto como el de `ADR-0008` (guardas de classpath ausentes) queda cubierto: el test con classpath filtrado corre en cada push, en los dos JDK y en las dos ramas.

### Negativas

- Mantener la allowlist de paridad cuando aparezca una diferencia legítima nueva.
- El tiempo de CI se duplica por la matriz de JDK.

### Riesgos

- **La allowlist crece sin control** y la verificación pierde valor. Mitigación: cada entrada lleva su razón en el propio fichero; una PR que la amplía tiene que justificarlo, igual que cualquier otro cambio de contrato.
- **El rojo permanente por paridad se normaliza** y se deja de mirar. Mitigación: el porte debe ocurrir inmediatamente tras el merge, que es lo que ya exige `ADR-0005`; la ventana en rojo se mide en minutos, no en días.
- **`setup-java` deja de ofrecer alguno de los JDK de la matriz.** Mitigación: la matriz es explícita y su fallo es visible; se actualiza junto con la política de toolchain.

## Impacto

- **Módulos afectados:** ninguno. No se toca código de producción.
- **Contratos públicos:** sin cambios.
- **Dependencias:** ninguna nueva en el artefacto. `jacoco-maven-plugin` ya estaba, se le añade la ejecución `check`.
- **Build:** `mvn verify` pasa a fallar si la cobertura baja de los umbrales. `mvn test` mantiene el comportamiento anterior, así que el ciclo de desarrollo local no cambia.
- **Proceso:** `ADR-0005` gana un mecanismo de verificación; `ADR-0004` sigue sin él (la actualización de documentación no es automatizable de forma fiable y sigue dependiendo de revisión).

## Validación

- Estado real medido antes de decidir: 77 ficheros bajo `src/` en `main`, 14 difieren respecto de `spring-boot-3.x`, 63 idénticos; cobertura 89 % líneas / 67 % ramas; `.github/workflows` inexistente.
- Los umbrales del trinquete se comprobaron ejecutando `mvn -B clean verify` en las dos ramas y en los dos JDK antes de aceptar el ADR.
- La allowlist inicial se generó a partir del diff real entre ramas, y cada entrada se contrastó con la tabla de diferencias de `AI_CONTEXT.md` de la rama `spring-boot-3.x`.

## Relación con Otros ADR

- **Da cumplimiento a `ADR-0005`**: aporta el mecanismo de verificación de la paridad funcional que el ADR exige pero no define.
- Relacionado con `ADR-0004` (la documentación sigue verificándose por revisión humana, no por CI) y con `ADR-0008` (su defecto de guardas de classpath es el caso que motivó priorizar este ADR).
