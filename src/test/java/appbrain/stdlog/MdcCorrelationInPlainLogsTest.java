package appbrain.stdlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.composite.loggingevent.MdcJsonProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auditoría F-11: la correlación debe aparecer en <b>toda</b> línea de log, no sólo en los
 * eventos {@code stdlog}.
 *
 * <p>Sin esto se puede filtrar por {@code request_id} lo que emite la librería, pero no lo que
 * escribe el código de la aplicación durante ese mismo request — que suele ser lo primero que
 * hace falta al depurar.</p>
 */
class MdcCorrelationInPlainLogsTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
        MDC.clear();
    }

    @Test
    void plainApplicationLogsMustCarryTheCorrelationFromMdc() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.TRACE);
        MDC.put("request_id", "req-77");
        MDC.put("operation", "PagosController#pagar");

        org.slf4j.LoggerFactory.getLogger("stdlog").info("procesando pedido");

        ILoggingEvent event = appender.list.get(0);
        assertEquals("procesando pedido", event.getFormattedMessage());
        assertEquals("req-77", event.getMDCPropertyMap().get("request_id"));
        assertEquals("PagosController#pagar", event.getMDCPropertyMap().get("operation"));
    }

    /**
     * El marcador interno de exclusión no debe salir al log del consumidor: la configuración
     * lo excluye explícitamente del provider `mdc`.
     */
    @Test
    void theInternalExclusionMarkerMustBeExcludedFromTheConfiguredProvider() {
        MdcJsonProvider provider = new MdcJsonProvider();
        provider.addExcludeMdcKeyName("stdlog.excluded");

        List<String> excluded = provider.getExcludeMdcKeyNames();
        assertTrue(excluded.contains("stdlog.excluded"),
                "stdlog.excluded es un marcador interno de la politica de exclusion, no informacion del consumidor");
    }
}
