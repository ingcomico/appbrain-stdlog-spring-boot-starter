package appbrain.stdlog.restclient;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class StdlogHttpBodyDecoderTest {

    @Test
    void shouldReturnEmptyWhenRawIsNull() {
        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(null, null, StandardCharsets.UTF_8, 0);

        assertNull(decoded.text);
        assertNull(decoded.bodyEncoding);
        assertNull(decoded.decodeError);
        assertNull(decoded.bodyBytes);
    }

    @Test
    void shouldReturnEmptyWhenRawIsEmpty() {
        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(new byte[0], null, StandardCharsets.UTF_8, 0);

        assertNull(decoded.text);
    }

    @Test
    void shouldDecodePlainTextWhenNoContentEncoding() {
        byte[] raw = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, null, StandardCharsets.UTF_8, 0);

        assertEquals("{\"ok\":true}", decoded.text);
        assertNull(decoded.bodyEncoding);
    }

    @Test
    void shouldDecodeGzipContent() throws IOException {
        byte[] raw = gzip("hello gzip world");

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, "gzip", StandardCharsets.UTF_8, 0);

        assertEquals("hello gzip world", decoded.text);
        assertEquals("gzip", decoded.bodyEncoding);
    }

    @Test
    void shouldDecodeDeflateContent() throws IOException {
        byte[] raw = deflate("hello deflate world");

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, "deflate", StandardCharsets.UTF_8, 0);

        assertEquals("hello deflate world", decoded.text);
        assertEquals("deflate", decoded.bodyEncoding);
    }

    @Test
    void shouldReturnUnsupportedEncodingForBrotli() {
        byte[] raw = "whatever".getBytes(StandardCharsets.UTF_8);

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, "br", StandardCharsets.UTF_8, 0);

        assertNull(decoded.text);
        assertEquals("br", decoded.bodyEncoding);
        assertEquals("unsupported-encoding", decoded.decodeError);
        assertEquals(raw.length, decoded.bodyBytes);
    }

    @Test
    void shouldReturnDecodeErrorForCorruptGzip() {
        byte[] corrupt = "not-really-gzip".getBytes(StandardCharsets.UTF_8);

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(corrupt, "gzip", StandardCharsets.UTF_8, 0);

        assertNull(decoded.text);
        assertNotNull(decoded.decodeError);
        assertEquals(corrupt.length, decoded.bodyBytes);
    }

    @Test
    void shouldTruncateWhenExceedingMaxChars() {
        byte[] raw = "abcdefghij".getBytes(StandardCharsets.UTF_8);

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, null, StandardCharsets.UTF_8, 5);

        assertEquals("abcde...(truncated)", decoded.text);
    }

    @Test
    void shouldNotTruncateWhenMaxCharsIsZero() {
        byte[] raw = "abcdefghij".getBytes(StandardCharsets.UTF_8);

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, null, StandardCharsets.UTF_8, 0);

        assertEquals("abcdefghij", decoded.text);
    }

    @Test
    void shouldHandleCombinedEncodings() throws IOException {
        byte[] raw = gzip("double wrapped");

        StdlogHttpBodyDecoder.Decoded decoded = StdlogHttpBodyDecoder.decodeToText(raw, " gzip ", StandardCharsets.UTF_8, 0);

        assertEquals("double wrapped", decoded.text);
        assertEquals("gzip", decoded.bodyEncoding);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    private static byte[] deflate(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream df = new DeflaterOutputStream(bos)) {
            df.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
