package appbrain.stdlog.restclient;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auditoría F-09: el body de la respuesta saliente se copiaba <b>entero</b> a memoria, sin
 * ningún tope, y {@code maxBodyChars} venía en {@code 0} (= sin límite). Una descarga grande
 * con {@code stdlog} en DEBUG podía llenar el heap.
 *
 * <p>Para poder loguear el body hay que bufferizarlo entero —la aplicación tiene que poder
 * releerlo—, así que el único modo real de acotar el heap es <b>no capturarlo</b> cuando se
 * sabe de antemano que es grande.</p>
 */
class OutgoingBodyCaptureLimitTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
    }

    private static StdlogProperties props(int maxCaptureBytes) {
        StdlogProperties p = new StdlogProperties();
        p.setMode(StdlogProperties.Mode.NON_PROD);
        p.getRestclient().setMaxCaptureBytes(maxCaptureBytes);
        return p;
    }

    private static MockClientHttpRequest request() {
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setURI(URI.create("https://api.example.com/download"));
        request.setMethod(HttpMethod.GET);
        return request;
    }

    private Map<String, Object> payload() {
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }

    @Test
    void shouldSkipCaptureWhenContentLengthExceedsTheLimit() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        byte[] big = new byte[64 * 1024];

        MockClientHttpResponse upstream = new MockClientHttpResponse(big, HttpStatus.OK);
        upstream.getHeaders().setContentLength(big.length);

        new StdlogClientHttpInterceptor(props(1024))
                .intercept(request(), new byte[0], (req, body) -> upstream);

        Map<?, ?> res = (Map<?, ?>) payload().get("response");
        assertEquals("SKIPPED_TOO_LARGE", res.get("bodyCapture"),
                "una respuesta que se declara mayor que el tope no debe copiarse a memoria");
        assertNull(res.get("body"));
    }

    @Test
    void shouldStillCaptureWhenUnderTheLimit() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);

        MockClientHttpResponse upstream = new MockClientHttpResponse("hola".getBytes(), HttpStatus.OK);
        upstream.getHeaders().setContentLength(4);

        new StdlogClientHttpInterceptor(props(1024))
                .intercept(request(), new byte[0], (req, body) -> upstream);

        Map<?, ?> res = (Map<?, ?>) payload().get("response");
        assertEquals("hola", res.get("body"));
    }

    /**
     * Lo que no puede pasar bajo ningún concepto: que acotar el log recorte la respuesta que
     * recibe la aplicación. Cuando se omite la captura, el stream se entrega intacto.
     */
    @Test
    void skippingCaptureMustNotTruncateTheResponseTheApplicationReceives() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        byte[] big = new byte[64 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');

        MockClientHttpResponse upstream = new MockClientHttpResponse(big, HttpStatus.OK);
        upstream.getHeaders().setContentLength(big.length);

        ClientHttpResponse returned = new StdlogClientHttpInterceptor(props(1024))
                .intercept(request(), new byte[0], (req, body) -> upstream);

        assertEquals(big.length, returned.getBody().readAllBytes().length,
                "la aplicacion debe recibir la respuesta COMPLETA aunque el log la omita");
    }

    @Test
    void zeroMeansNoLimit() throws Exception {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        MockClientHttpResponse upstream = new MockClientHttpResponse("hola".getBytes(), HttpStatus.OK);
        upstream.getHeaders().setContentLength(4);

        new StdlogClientHttpInterceptor(props(0))
                .intercept(request(), new byte[0], (req, body) -> upstream);

        assertEquals("hola", ((Map<?, ?>) payload().get("response")).get("body"));
    }
}
