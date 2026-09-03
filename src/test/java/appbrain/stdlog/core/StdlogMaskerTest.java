package appbrain.stdlog.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdlogMaskerTest {

    @AfterEach
    void tearDown() {
        StdlogMasker.reset();
    }

    // ---------- pasada estructural ----------

    @Test
    void shouldMaskSensitiveKeyAtTopLevel() {
        Map<String, Object> out = StdlogMasker.mask(new LinkedHashMap<>(Map.of(
                "event", "X", "password", "s3cr3t")));

        assertEquals("X", out.get("event"));
        assertEquals("***", out.get("password"));
    }

    @Test
    void shouldMaskNestedInMapsAndLists() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", "ana");
        body.put("token", "abc.def.ghi");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", new LinkedHashMap<>(Map.of("body", body)));
        payload.put("items", List.of(new LinkedHashMap<>(Map.of("cvv", "123"))));

        Map<String, Object> out = StdlogMasker.mask(payload);

        Map<?, ?> maskedBody = (Map<?, ?>) ((Map<?, ?>) out.get("request")).get("body");
        assertEquals("ana", maskedBody.get("user"));
        assertEquals("***", maskedBody.get("token"));
        assertEquals("***", ((Map<?, ?>) ((List<?>) out.get("items")).get(0)).get("cvv"));
    }

    @Test
    void shouldNormalizeKeySpellings() {
        Map<String, Object> out = StdlogMasker.mask(new LinkedHashMap<>(Map.of(
                "card_number", "4111", "API-Key", "k", "Authorization", "Bearer x")));

        assertEquals("***", out.get("card_number"));
        assertEquals("***", out.get("API-Key"));
        assertEquals("***", out.get("Authorization"));
    }

    /** Motivo de comparar por igualdad y no por inclusión: "shipping" contiene "pin". */
    @Test
    void shouldNotMaskKeysThatMerelyContainASensitiveToken() {
        Map<String, Object> out = StdlogMasker.mask(new LinkedHashMap<>(Map.of(
                "shipping", "express", "tokenizer", "v2", "passwordPolicy", "strong")));

        assertEquals("express", out.get("shipping"));
        assertEquals("v2", out.get("tokenizer"));
        assertEquals("strong", out.get("passwordPolicy"));
    }

    @Test
    void shouldReturnTheSameInstanceWhenThereIsNothingToMask() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("event", "X", "elapsedMs", 12));
        assertSame(payload, StdlogMasker.mask(payload));
    }

    @Test
    void shouldNotMutateTheCallersMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("password", "s3cr3t");

        StdlogMasker.mask(payload);

        assertEquals("s3cr3t", payload.get("password"), "el payload original no se toca");
    }

    @Test
    void shouldPreserveKeyOrder() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("a", 1);
        payload.put("password", "x");
        payload.put("z", 2);

        assertEquals(List.of("a", "password", "z"), new ArrayList<>(StdlogMasker.mask(payload).keySet()));
    }

    // ---------- pasada textual ----------

    @Test
    void shouldMaskJsonPairsInsideAStringBody() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "body", "{\"user\":\"ana\",\"password\":\"s3cr3t\",\"amount\":100}"));

        String masked = (String) StdlogMasker.mask(payload).get("body");

        assertTrue(masked.contains("\"user\":\"ana\""), masked);
        assertTrue(masked.contains("\"password\":\"***\""), masked);
        assertTrue(masked.contains("\"amount\":100"), masked);
        assertFalse(masked.contains("s3cr3t"));
    }

    @Test
    void shouldMaskNonStringJsonValues() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("body", "{\"pin\":1234}"));
        assertEquals("{\"pin\":\"***\"}", StdlogMasker.mask(payload).get("body"));
    }

    @Test
    void shouldMaskFormAndQueryPairs() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", "username=ana&password=s3cr3t&site=MCO");
        payload.put("fullPath", "/pay?token=abc123&id=7");

        Map<String, Object> out = StdlogMasker.mask(payload);

        assertEquals("username=ana&password=***&site=MCO", out.get("body"));
        assertEquals("/pay?token=***&id=7", out.get("fullPath"));
    }

    /** El texto puede venir truncado: por eso la pasada es textual y no un parseo. */
    @Test
    void shouldMaskTruncatedOrInvalidJson() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "body", "{\"user\":\"ana\",\"password\":\"s3cr3\"...(truncated)"));

        String masked = (String) StdlogMasker.mask(payload).get("body");

        assertTrue(masked.contains("\"password\":\"***\""), masked);
        assertFalse(masked.contains("s3cr3"));
    }

    /**
     * El cribado indexa por los tres primeros caracteres de la clave, justamente para que las
     * variantes con separador se detecten igual. Si se rompiera, esto se escaparía en silencio.
     */
    @Test
    void shouldMaskSeparatorVariantsInsideTextBodies() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", "{\"api_key\":\"k1\",\"card-number\":\"4111\",\"accessToken\":\"t\"}");

        String masked = (String) StdlogMasker.mask(payload).get("body");

        assertFalse(masked.contains("k1"), masked);
        assertFalse(masked.contains("4111"), masked);
        assertFalse(masked.contains("\"t\""), masked);
    }

    /**
     * El cribado sobre-coincide a propósito ({@code car} dispara con «carrier»). Debe costar
     * sólo trabajo de más, nunca enmascarar de menos ni de más.
     */
    @Test
    void screeningOvermatchMustNotChangeTheResult() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", "{\"carrier\":\"DHL\",\"pinned\":true,\"tokenizer\":\"v2\"}");

        assertEquals("{\"carrier\":\"DHL\",\"pinned\":true,\"tokenizer\":\"v2\"}",
                StdlogMasker.mask(payload).get("body"));
    }

    @Test
    void shouldNotApplyTextPassToArbitraryStringValues() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "message", "el campo password=x no se toca aqui"));

        assertEquals("el campo password=x no se toca aqui", StdlogMasker.mask(payload).get("message"));
    }

    // ---------- configuración ----------

    @Test
    void shouldDoNothingWhenDisabled() {
        StdlogMasker.configure(false, StdlogMasker.DEFAULT_KEYS, "***");
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("password", "s3cr3t"));

        assertEquals("s3cr3t", StdlogMasker.mask(payload).get("password"));
    }

    @Test
    void shouldHonourCustomKeysAndPlaceholder() {
        StdlogMasker.configure(true, List.of("iban"), "[REDACTED]");
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("iban", "ES91", "password", "s3cr3t"));

        Map<String, Object> out = StdlogMasker.mask(payload);

        assertEquals("[REDACTED]", out.get("iban"));
        assertEquals("s3cr3t", out.get("password"), "keys reemplaza la lista incorporada");
    }

    @Test
    void shouldMaskByDefaultWithoutAnyConfiguration() {
        assertTrue(StdlogMasker.activeKeys().contains("password"));
        assertTrue(StdlogMasker.activeKeys().contains("authorization"));
        assertEquals("***", StdlogMasker.mask(new LinkedHashMap<>(Map.of("secret", "x"))).get("secret"));
    }

    @Test
    void shouldTolerateNullAndEmpty() {
        assertNull(StdlogMasker.mask(null));
        Map<String, Object> empty = new LinkedHashMap<>();
        assertSame(empty, StdlogMasker.mask(empty));
    }
}
