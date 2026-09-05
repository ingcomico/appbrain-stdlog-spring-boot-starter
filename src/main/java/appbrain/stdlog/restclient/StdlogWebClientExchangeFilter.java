package appbrain.stdlog.restclient;

import appbrain.stdlog.config.StdlogLevel;
import appbrain.stdlog.config.StdlogProperties;
import appbrain.stdlog.core.StdlogEmitter;
import appbrain.stdlog.core.StdlogFailsafe;
import appbrain.stdlog.core.StdlogModeResolver;
import appbrain.stdlog.core.StdlogReactiveCorrelation;
import appbrain.stdlog.util.StdlogCallerResolver;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Filtro de {@code WebClient} que emite el evento {@code CLIENT_HTTP} para llamadas HTTP
 * salientes hechas con el cliente reactivo, con el mismo formato que
 * {@code StdlogClientHttpInterceptor} produce para {@code RestTemplate} / {@code RestClient}.
 * Ver ADR-0006.
 *
 * <p><b>Correlación:</b> {@code request_id} y {@code operation} viven en el MDC del hilo de
 * request. {@code filter()} se ejecuta en el hilo que suscribe la llamada (el de request cuando
 * la app hace {@code .block()}), así que aquí el MDC está disponible: se copia entero y se
 * restaura alrededor de la emisión, que corre en un hilo del event-loop. Si el MDC está vacío
 * (pipeline totalmente reactivo sin context-propagation) los campos se omiten, igual que hace
 * el interceptor síncrono cuando no hay MDC.</p>
 *
 * <p><b>No modifica</b> {@code StdlogClientHttpInterceptor}: comparte con él sólo el armado del
 * payload vía {@link StdlogClientHttpPayload} (código nuevo) y el decoder {@link StdlogHttpBodyDecoder}.</p>
 *
 * <p>El body se captura sólo cuando {@code logging.level.stdlog=DEBUG}. La bufferización está
 * acotada por {@code stdlog.restclient.webclient.max-capture-bytes}; la app siempre recibe el
 * body completo.</p>
 */
public class StdlogWebClientExchangeFilter implements ExchangeFilterFunction {

    private static final Logger STDLOG = LoggerFactory.getLogger("stdlog");

    private final StdlogProperties props;

    public StdlogWebClientExchangeFilter(StdlogProperties props) {
        this.props = props;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        StdlogProperties.Restclient rc = activeConfig();
        if (rc == null) {
            return next.exchange(request);
        }
        return Mono.deferContextual(ctxView -> doFilter(request, next, rc, ctxView));
    }

    private Mono<ClientResponse> doFilter(ClientRequest request, ExchangeFunction next,
            StdlogProperties.Restclient rc, ContextView ctxView) {

        // Correlación: MDC primero (app servlet + .block()); si está vacío, Reactor Context
        // (app WebFlux — lo puebla StdlogWebFilter). Ver ADR-0008 Fase 2.
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        String requestId = firstNonBlank(value(mdc, "request_id"), StdlogReactiveCorrelation.requestId(ctxView));
        String operation = firstNonBlank(value(mdc, "operation"), StdlogReactiveCorrelation.operation(ctxView));
        boolean excluded = mdc != null && mdc.containsKey(StdlogEmitter.MDC_EXCLUDED)
                || StdlogReactiveCorrelation.excluded(ctxView);

        Map<String, String> mdcForEmit = (mdc != null && !mdc.isEmpty()) ? mdc : null;
        if (mdcForEmit == null && (requestId != null || operation != null || excluded)) {
            mdcForEmit = new HashMap<>();
            if (requestId != null) mdcForEmit.put("request_id", requestId);
            if (operation != null) mdcForEmit.put("operation", operation);
            if (excluded) mdcForEmit.put(StdlogEmitter.MDC_EXCLUDED, "true");
        }

        Ctx ctx = new Ctx(
                requestId,
                operation,
                rc.isCaptureCallId() ? UUID.randomUUID().toString() : null,
                rc.isCaptureSource() ? resolveSource(rc) : null,
                mdcForEmit,
                STDLOG.isDebugEnabled(),
                System.nanoTime());

        int maxCapture = rc.getWebclient().getMaxCaptureBytes();
        BodyBuffer reqBuf = new BodyBuffer(maxCapture);
        ClientRequest outgoing = ctx.debug ? teeRequestBody(request, reqBuf) : request;

        return next.exchange(outgoing)
                .flatMap(response -> onResponse(request, response, reqBuf, ctx, rc, maxCapture))
                .onErrorResume(err -> {
                    safeEmit(request, reqBuf, null, ctx, rc, 500, true, null, err);
                    return Mono.error(err);
                });
    }

    private Mono<ClientResponse> onResponse(ClientRequest request, ClientResponse response,
            BodyBuffer reqBuf, Ctx ctx, StdlogProperties.Restclient rc, int maxCapture) {

        int status = response.statusCode().value();
        boolean failure = status >= 400;
        boolean skip = StdlogModeResolver.isProd(props) && rc.isLogOnlyOnFailureInProd() && !failure;
        HttpHeaders resHeaders = response.headers().asHttpHeaders();

        if (skip) {
            return Mono.just(response);
        }

        if (!ctx.debug) {
            safeEmit(request, reqBuf, null, ctx, rc, status, failure, resHeaders, null);
            return Mono.just(response);
        }

        // Tee acotado del body de response: la app recibe el stream completo, nosotros
        // bufferizamos hasta maxCapture y emitimos cuando el stream termina.
        BodyBuffer resBuf = new BodyBuffer(maxCapture);
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable emit = () -> {
            if (emitted.compareAndSet(false, true)) {
                safeEmit(request, reqBuf, resBuf, ctx, rc, status, failure, resHeaders, null);
            }
        };

        Flux<DataBuffer> teed = response.body(BodyExtractors.toDataBuffers())
                .doOnNext(resBuf::offer)
                .doOnComplete(emit)
                .doOnCancel(emit)
                .doOnError(err -> emit.run());

        return Mono.just(response.mutate().body(teed).build());
    }

    // ---- emisión (puede correr en hilo del event-loop; restauramos el MDC capturado) ----

    private void safeEmit(ClientRequest request, BodyBuffer reqBuf, BodyBuffer resBuf, Ctx ctx,
            StdlogProperties.Restclient rc, int status, boolean failure, HttpHeaders resHeaders, Throwable error) {

        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (ctx.mdc != null) MDC.setContextMap(ctx.mdc);

            StdlogHttpBodyDecoder.Decoded reqBody = null;
            StdlogHttpBodyDecoder.Decoded resBody = null;
            if (ctx.debug) {
                reqBody = reqBuf == null ? null : reqBuf.decoded(
                        request.headers().getFirst("content-encoding"),
                        StdlogClientHttpPayload.charsetOf(request.headers().getContentType()),
                        rc.getMaxBodyChars());
                resBody = resBuf == null ? null : resBuf.decoded(
                        resHeaders == null ? null : resHeaders.getFirst("content-encoding"),
                        StdlogClientHttpPayload.charsetOf(resHeaders == null ? null : resHeaders.getContentType()),
                        rc.getMaxBodyChars());
            }

            Map<String, Object> payload = StdlogClientHttpPayload.build(
                    request.method().name(),
                    String.valueOf(request.url()),
                    request.url().getHost(),
                    status,
                    (System.nanoTime() - ctx.startNano) / 1_000_000,
                    failure,
                    ctx.requestId, ctx.operation, ctx.callId, ctx.source,
                    request.headers(), rc.isLogAllRequestHeaders(), rc.getRequestHeadersAllowlist(), reqBody,
                    resHeaders, rc.isLogAllResponseHeaders(), rc.getResponseHeadersAllowlist(), resBody);

            StdlogLevel level = levelForStatus(rc, status);
            if (error != null) StdlogEmitter.emit(STDLOG, level, payload, error);
            else StdlogEmitter.emit(STDLOG, level, payload);
        } catch (RuntimeException loggingFailure) {
            // Nunca romper la operacion por un fallo de logging, pero tampoco callarlo:
            // descartarlo en silencio tambien es perder datos (ADR-0011).
            StdlogFailsafe.report(loggingFailure);
        } finally {
            if (previous != null) MDC.setContextMap(previous);
            else MDC.clear();
        }
    }

    private static StdlogLevel levelForStatus(StdlogProperties.Restclient rc, int status) {
        if (status >= 500) return rc.getInLevelFailure5xx();
        if (status >= 400) return rc.getInLevelFailure4xx();
        return rc.getInLevelSuccess();
    }

    // ---- captura del body de request (tee acotado, la app recibe el body intacto) ----

    private ClientRequest teeRequestBody(ClientRequest request, BodyBuffer buf) {
        return ClientRequest.from(request).body((outputMessage, context) -> {
            ClientHttpRequestDecorator decorator = new ClientHttpRequestDecorator(outputMessage) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return super.writeWith(Flux.from(body).doOnNext(buf::offer));
                }

                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return super.writeAndFlushWith(Flux.from(body).map(inner -> Flux.from(inner).doOnNext(buf::offer)));
                }
            };
            return request.body().insert(decorator, context);
        }).build();
    }

    // ---- helpers ----

    private StdlogProperties.Restclient activeConfig() {
        if (props == null) return null;
        StdlogProperties.Restclient rc = props.getRestclient();
        if (rc == null || !rc.isEnabled()) return null;
        if (rc.getWebclient() == null || !rc.getWebclient().isEnabled()) return null;
        return rc;
    }

    private Map<String, Object> resolveSource(StdlogProperties.Restclient rc) {
        String basePkg = firstNonBlank(rc.getConsumerBasePackage(), props.getConsumerBasePackage());
        StackTraceElement caller = StdlogCallerResolver.findConsumerCaller(basePkg);
        if (caller == null) return null;
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("class", caller.getClassName());
        source.put("method", caller.getMethodName());
        source.put("file", caller.getFileName());
        source.put("line", caller.getLineNumber());
        return source;
    }

    private static String value(Map<String, String> mdc, String key) {
        return mdc == null ? null : mdc.get(key);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /** Datos capturados de forma síncrona al entrar al filtro. */
    private static final class Ctx {
        final String requestId;
        final String operation;
        final String callId;
        final Map<String, Object> source;
        final Map<String, String> mdc;
        final boolean debug;
        final long startNano;

        Ctx(String requestId, String operation, String callId, Map<String, Object> source,
                Map<String, String> mdc, boolean debug, long startNano) {
            this.requestId = requestId;
            this.operation = operation;
            this.callId = callId;
            this.source = source;
            this.mdc = mdc;
            this.debug = debug;
            this.startNano = startNano;
        }
    }

    /** Bufferiza los primeros {@code max} bytes de un body sin consumir los {@code DataBuffer}. */
    static final class BodyBuffer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int max;
        private boolean used;
        private boolean truncated;

        BodyBuffer(int max) {
            this.max = max;
        }

        void offer(DataBuffer db) {
            used = true;
            try {
                ByteBuffer view = db.asByteBuffer().duplicate();
                int available = view.remaining();
                int room = (max <= 0) ? available : Math.max(0, max - out.size());
                int n = Math.min(available, room);
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    view.get(chunk);
                    out.write(chunk, 0, n);
                }
                if (n < available) truncated = true;
            } catch (RuntimeException ignored) {
                // no arriesgamos la llamada por un fallo de captura
            }
        }

        StdlogHttpBodyDecoder.Decoded decoded(String contentEncoding, Charset charset, int maxChars) {
            if (!used || out.size() == 0) return null;
            StdlogHttpBodyDecoder.Decoded decoded =
                    StdlogHttpBodyDecoder.decodeToText(out.toByteArray(), contentEncoding, charset, maxChars);
            if (truncated && decoded.text != null) {
                return StdlogHttpBodyDecoder.Decoded.text(decoded.text + "...(truncated)", decoded.bodyEncoding);
            }
            return decoded;
        }
    }
}
