package appbrain.stdlog.web;

import appbrain.stdlog.StdlogTestSupport;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Condición de aceptación de ADR-0012, en un contenedor servlet real.
 *
 * <p>Al mover {@code ControllerBodyAndOutLoggingFilter} a la posición más externa, sus
 * {@code ContentCaching*Wrapper} pasan a envolver la cadena de Spring Security. Ese es el
 * riesgo real del cambio, y con mocks no se puede descargar: {@code MockHttpServletRequest}
 * no parsea bodies de formulario como lo hace un contenedor, así que sólo Tomcat real
 * demuestra que el <i>form login</i> sigue funcionando.</p>
 *
 * <p>Arranca Tomcat con Spring Security y **la autoconfiguración real** del starter, así que
 * verifica el cableado de producción: el orden {@code Integer.MIN_VALUE + 100} que
 * {@code StdlogAutoConfiguration} registra desde ADR-0012, no un montaje del test.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControllerFilterOrderContainerTest {

    @LocalServerPort
    private int port;

    private ListAppender<ILoggingEvent> appender;

    /**
     * Cliente del JDK a propósito, no {@code RestTemplate} ni {@code RestClient}: esos dos
     * están instrumentados por el propio starter y añadirían eventos {@code CLIENT_HTTP} al
     * appender, contaminando las aserciones de este test.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void setUp() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
    }

    private List<Map<String, Object>> events() {
        return appender.list.stream().map(StdlogTestSupport::stdlogPayload).toList();
    }

    private Map<String, Object> event(String directionOrEvent) {
        return events().stream()
                .filter(p -> directionOrEvent.equals(p.get("direction")) || directionOrEvent.equals(p.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no se emitió " + directionOrEvent + " en " + events()));
    }

    // ---------- riesgo 1: el form login lee el body a través del wrapper ----------

    @Test
    void formLoginMustStillAuthenticateWithTheRequestWrapperOutside() throws Exception {
        HttpResponse<String> res = post("/login", "application/x-www-form-urlencoded",
                "username=ana&password=s3cr3t");

        // Spring Security responde 302 a la URL de éxito cuando las credenciales son válidas.
        assertEquals(302, res.statusCode(), "el form login debe autenticar");
        String location = res.headers().firstValue("Location").orElse("");
        assertFalse(location.contains("error"),
                "redirigió a error: el wrapper rompió el parseo del formulario -> " + location);
    }

    /** El password del formulario no puede acabar en el log (ADR-0010). */
    @Test
    void formLoginCredentialsMustBeMasked() throws Exception {
        post("/login", "application/x-www-form-urlencoded", "username=ana&password=s3cr3t");

        assertFalse(events().toString().contains("s3cr3t"),
                "la credencial apareció en el log: " + events());
    }

    // ---------- riesgo 2: la respuesta la escribe la cadena de seguridad ----------

    @Test
    void unauthorizedRequestMustEmitEventsAndKeepItsStatus() throws Exception {
        HttpResponse<String> res = get("/secure");

        assertEquals(401, res.statusCode(), "el 401 debe llegar al cliente intacto");

        Map<String, Object> out = event("OUT");
        assertEquals(401, ((Map<?, ?>) out.get("http")).get("status"));
        assertEquals("FAILURE", out.get("outcome"));
        assertNotNull(out.get("route"), "el evento debe llevar route aunque no haya handler");
        assertNotNull(out.get("request_id"));
    }

    @Test
    void unauthorizedRequestMustEmitTheExtraWarnEvent() throws Exception {
        get("/secure");

        Map<String, Object> warn = event("WARN");
        assertEquals(401, ((Map<?, ?>) warn.get("http")).get("status"));
    }

    // ---------- no-regresión: el camino normal no pierde nada ----------

    @Test
    void normalRequestMustKeepOperationAndRoute() throws Exception {
        assertEquals(200, get("/public").statusCode());

        Map<String, Object> in = event("IN");
        assertEquals("TestController#publicEndpoint", in.get("operation"));
        assertEquals("GET /public", in.get("route"));
        assertNotNull(in.get("request_id"));
    }

    @Test
    void jsonBodyMustBeCapturedAndTheResponseMustReachTheClientIntact() throws Exception {
        HttpResponse<String> res = post("/echo", "application/json", "{\"a\":1}");

        assertEquals(200, res.statusCode());
        assertEquals("{\"a\":1}", res.body(), "el body de respuesta debe llegar completo al cliente");

        // El content-type es JSON, así que el body se captura parseado, no como texto.
        Map<?, ?> reqNode = (Map<?, ?>) event("IN").get("request");
        assertEquals(Map.of("a", 1), reqNode.get("body"));
        assertEquals("JSON", reqNode.get("bodyFormat"));
    }

    // ---------- riesgo 3: asíncronos ----------

    @Test
    void asyncRequestMustEmitExactlyOnePairOfEvents() throws Exception {
        assertEquals(200, get("/async").statusCode());

        long in = events().stream().filter(p -> "IN".equals(p.get("direction"))).count();
        long out = events().stream().filter(p -> "OUT".equals(p.get("direction"))).count();
        assertEquals(1, in, "un solo CONTROLLER_HTTP IN: " + events());
        assertEquals(1, out, "un solo CONTROLLER_HTTP OUT: " + events());
    }

    // ================= app de prueba =================

    @SpringBootApplication
    static class App {}

    @TestConfiguration
    static class SecurityAndFilterConfig {

        @Bean
        TestController testController() { return new TestController(); }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(reg -> reg
                            .requestMatchers("/public", "/echo", "/async", "/login", "/ok").permitAll()
                            .anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .formLogin(form -> form.loginProcessingUrl("/login").defaultSuccessUrl("/ok", true))
                    .exceptionHandling(e -> e.authenticationEntryPoint(
                            (req, res, ex) -> res.sendError(401)))
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("ana").password("{noop}s3cr3t").roles("USER").build());
        }
    }

    @RestController
    public static class TestController {

        @GetMapping("/public")
        String publicEndpoint() { return "ok"; }

        @GetMapping("/ok")
        String ok() { return "ok"; }

        @GetMapping("/secure")
        String secure() { return "secret"; }

        @PostMapping(value = "/echo", produces = MediaType.APPLICATION_JSON_VALUE)
        String echo(@RequestBody String body) { return body; }

        @GetMapping("/async")
        java.util.concurrent.CompletableFuture<String> async() {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> "async-ok");
        }
    }
}
