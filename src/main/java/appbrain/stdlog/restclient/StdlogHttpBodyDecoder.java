package appbrain.stdlog.restclient;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Decodifica bodies de requests/responses HTTP comprimidos para incluirlos en los logs.
 *
 * <p>Soporta los encodings {@code gzip} y {@code deflate}. Para encodings no soportados
 * (ej. {@code br} / brotli), retorna un {@link Decoded} con {@code decodeError="unsupported-encoding"}
 * en lugar de lanzar excepción, garantizando que el log se emite igualmente (sin body).</p>
 *
 * <p>Clase package-private: solo usada por {@code StdlogClientHttpInterceptor}.</p>
 */
final class StdlogHttpBodyDecoder {

    private StdlogHttpBodyDecoder() {}

    /**
     * Decodifica el body raw a texto, aplicando descompresión si corresponde y truncando
     * según {@code maxChars}.
     *
     * @param raw                    bytes del body (puede ser {@code null} o vacío)
     * @param contentEncodingHeader  valor del header {@code content-encoding} (ej. {@code "gzip"}
     *                               o {@code "gzip, deflate"}); puede ser {@code null} o vacío
     * @param charset                charset para convertir bytes a string
     * @param maxChars               límite de caracteres del texto resultante; {@code 0} = sin límite
     * @return {@link Decoded} con el texto, metadata de encoding y posibles errores; nunca {@code null}
     */
    static Decoded decodeToText(byte[] raw, String contentEncodingHeader, Charset charset, int maxChars) {
        if (raw == null || raw.length == 0) return Decoded.empty();

        byte[] current = raw;
        String applied = null;

        if (contentEncodingHeader != null && !contentEncodingHeader.isBlank()) {
            for (String token : contentEncodingHeader.split(",")) {
                String enc = token.trim().toLowerCase(Locale.ROOT);
                if (enc.isEmpty()) continue;

                try {
                    switch (enc) {
                        case "gzip":
                            current = ungzip(current);
                            applied = append(applied, "gzip");
                            break;
                        case "deflate":
                            current = undeflate(current);
                            applied = append(applied, "deflate");
                            break;
                        default:
                            // encoding no soportado (ej: br). No intentamos loguear como texto.
                            return Decoded.unsupportedEncoding(enc, raw.length);
                    }
                } catch (IOException ex) {
                    return Decoded.decodeError(enc, raw.length, ex);
                }
            }
        }

        String text = new String(current, charset);

        // truncate
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars) + "...(truncated)";
        }

        return Decoded.text(text, applied);
    }

    private static String append(String applied, String enc) {
        return applied == null ? enc : applied + "," + enc;
    }

    private static byte[] ungzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            transfer(in, out, 8192);
            return out.toByteArray();
        }
    }

    private static byte[] undeflate(byte[] deflated) throws IOException {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            transfer(in, out, 8192);
            return out.toByteArray();
        }
    }

    private static void transfer(InputStream in, OutputStream out, int bufSize) throws IOException {
        byte[] buf = new byte[bufSize];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /**
     * Resultado de la decodificación de un body HTTP.
     * Exactamente uno de {@code text} o {@code bodyBytes} estará presente
     * cuando el body no está vacío.
     */
    static final class Decoded {
        /** Texto decodificado y truncado, listo para incluir en el log. {@code null} si no se pudo decodificar. */
        final String text;
        /** Encoding(s) aplicados (ej. {@code "gzip"}, {@code "gzip,deflate"}). {@code null} si no había encoding. */
        final String bodyEncoding;
        /** Descripción del error de decodificación (ej. nombre de excepción o {@code "unsupported-encoding"}). {@code null} si no hubo error. */
        final String decodeError;
        /** Tamaño en bytes del body raw cuando no se pudo decodificar a texto. {@code null} si el body estaba vacío o se decodificó correctamente. */
        final Integer bodyBytes;

        private Decoded(String text, String bodyEncoding, String decodeError, Integer bodyBytes) {
            this.text = text;
            this.bodyEncoding = bodyEncoding;
            this.decodeError = decodeError;
            this.bodyBytes = bodyBytes;
        }

        static Decoded empty() {
            return new Decoded(null, null, null, null);
        }

        static Decoded text(String text, String appliedEncoding) {
            return new Decoded(text, appliedEncoding, null, null);
        }

        static Decoded unsupportedEncoding(String enc, int bytes) {
            return new Decoded(null, enc, "unsupported-encoding", bytes);
        }

        static Decoded decodeError(String enc, int bytes, Exception ex) {
            return new Decoded(null, enc, ex.getClass().getSimpleName(), bytes);
        }
    }
}
