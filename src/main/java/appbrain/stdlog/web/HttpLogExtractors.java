package appbrain.stdlog.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Utilidades de extracción de datos HTTP para los logs de controller.
 *
 * <p>Métodos puramente funcionales (sin estado) que extraen headers,
 * query params y validan content-types a partir del request HTTP.
 * Usados internamente por {@code ControllerBodyAndOutLoggingFilter}.</p>
 */
public final class HttpLogExtractors {
    private HttpLogExtractors() {}

    /**
     * Extrae los headers del request que están en la allowlist (case-insensitive).
     *
     * @param req       request HTTP entrante
     * @param allowlist lista de nombres de headers permitidos; si es nula o vacía devuelve mapa vacío
     * @return mapa {@code nombre → valor} con solo los headers permitidos; nunca {@code null}
     */
    public static Map<String, String> allowedHeaders(HttpServletRequest req, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) return Map.of();

        Set<String> allowedLower = new HashSet<>();
        for (String h : allowlist) allowedLower.add(h.toLowerCase(Locale.ROOT));

        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names == null) return out;

        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (allowedLower.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(name, req.getHeader(name));
            }
        }
        return out;
    }

    /**
     * Extrae únicamente los parámetros de la query string del request, sin mezclar
     * campos de un body {@code application/x-www-form-urlencoded}.
     * Parámetros con un único valor se representan como {@code String};
     * parámetros multi-valor como {@code String[]}.
     *
     * @param req request HTTP entrante
     * @return mapa con todos los query parameters; nunca {@code null}
     */
    public static Map<String, Object> queryParams(HttpServletRequest req) {
        String queryString = req.getQueryString();
        if (queryString == null || queryString.isBlank()) return Map.of();

        MultiValueMap<String, String> params = UriComponentsBuilder.fromPath("")
                .query(queryString)
                .build()
                .getQueryParams();
        Map<String, Object> out = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            String decodedKey = decodeQueryComponent(key);
            String[] decodedValues = values == null
                    ? new String[0]
                    : values.stream().map(HttpLogExtractors::decodeQueryComponent).toArray(String[]::new);
            out.put(decodedKey, decodedValues.length == 1 ? decodedValues[0] : decodedValues);
        });
        return out;
    }

    private static String decodeQueryComponent(String value) {
        if (value == null) return null;
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    /**
     * Verifica si el content-type del request está en la lista de tipos permitidos.
     * Soporta wildcards via {@link MediaType#includes(MediaType)}
     * (ej. {@code application/*+json} incluye {@code application/vnd.api+json}).
     *
     * @param contentType content-type del request (puede ser {@code null})
     * @param allowed     lista de media types permitidos
     * @return {@code true} si el content-type está permitido; {@code false} si es nulo o no está en la lista
     */
    public static boolean isAllowedContentType(String contentType, List<String> allowed) {
        if (contentType == null) return false;
        try {
            MediaType ct = MediaType.parseMediaType(contentType);
            for (String a : allowed) {
                MediaType allowedMt = MediaType.parseMediaType(a);
                if (allowedMt.includes(ct)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
