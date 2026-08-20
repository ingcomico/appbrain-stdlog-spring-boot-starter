package appbrain.stdlog.restclient;

import appbrain.stdlog.StdlogTestSupport;
import appbrain.stdlog.config.StdlogProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StdlogRestClientSupportTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) StdlogTestSupport.detach(appender);
    }

    @Test
    void restClientShouldEmitClientHttpAndKeepResponseBodyReadable() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        StdlogProperties props = propsWithoutCallId();
        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);
        RestClient.Builder builder = RestClient.builder().requestInterceptor(interceptor);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.example.com/orders/1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        RestClient restClient = builder.build();

        String body = restClient.get()
                .uri("https://api.example.com/orders/1")
                .retrieve()
                .body(String.class);

        assertThat(body).isEqualTo("{\"id\":1}");
        Map<String, Object> payload = onlyPayload();
        assertThat(payload.get("event")).isEqualTo("CLIENT_HTTP");
        assertThat(payload.get("direction")).isEqualTo("IN");
        assertThat(payload.get("outcome")).isEqualTo("SUCCESS");
        Map<?, ?> http = (Map<?, ?>) payload.get("http");
        assertThat(http.get("method")).isEqualTo("GET");
        assertThat(http.get("url")).isEqualTo("https://api.example.com/orders/1");
        assertThat(http.get("status")).isEqualTo(200);
        assertThat(((Map<?, ?>) payload.get("peer")).get("host")).isEqualTo("api.example.com");
        assertThat(((Map<?, ?>) payload.get("response")).get("body")).isEqualTo("{\"id\":1}");
        server.verify();
    }

    @Test
    void restTemplateAndRestClientShouldEmitEquivalentClientHttpPayload() {
        appender = StdlogTestSupport.attachStdlogAppender(Level.DEBUG);
        StdlogProperties props = propsWithoutCallId();
        StdlogClientHttpInterceptor interceptor = new StdlogClientHttpInterceptor(props);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(interceptor);
        MockRestServiceServer restTemplateServer = MockRestServiceServer.bindTo(restTemplate).build();
        restTemplateServer.expect(requestTo("https://api.example.com/orders/1"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        RestClient.Builder builder = RestClient.builder().requestInterceptor(interceptor);
        MockRestServiceServer restClientServer = MockRestServiceServer.bindTo(builder).build();
        restClientServer.expect(requestTo("https://api.example.com/orders/1"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        RestClient restClient = builder.build();

        restTemplate.getForObject("https://api.example.com/orders/1", String.class);
        Map<String, Object> restTemplatePayload = StdlogTestSupport.stdlogPayload(appender.list.get(0));
        appender.list.clear();

        restClient.get().uri("https://api.example.com/orders/1").retrieve().body(String.class);
        Map<String, Object> restClientPayload = onlyPayload();

        assertThat(restClientPayload.get("event")).isEqualTo(restTemplatePayload.get("event"));
        assertThat(restClientPayload.get("direction")).isEqualTo(restTemplatePayload.get("direction"));
        assertThat(restClientPayload.get("outcome")).isEqualTo(restTemplatePayload.get("outcome"));
        assertThat(restClientPayload.get("http")).isEqualTo(restTemplatePayload.get("http"));
        assertThat(restClientPayload.get("peer")).isEqualTo(restTemplatePayload.get("peer"));
        assertThat(restClientPayload.get("response")).isEqualTo(restTemplatePayload.get("response"));
        restTemplateServer.verify();
        restClientServer.verify();
    }

    private static StdlogProperties propsWithoutCallId() {
        StdlogProperties props = new StdlogProperties();
        props.getRestclient().setEnabled(true);
        props.getRestclient().setCaptureCallId(false);
        return props;
    }

    private Map<String, Object> onlyPayload() {
        assertThat(appender.list).hasSize(1);
        return StdlogTestSupport.stdlogPayload(appender.list.get(0));
    }
}
