package appbrain.stdlog.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enmascara valores sensibles en el payload de un evento antes de emitirlo (ADR-0010).
 *
 * <p>Se aplica en un <b>único punto</b>, {@link StdlogEmitter}, por el que pasan todos los
 * módulos. Así cubre de una vez las cinco superficies por las que puede salir un dato
 * sensible —bodies de controller, bodies de {@code CLIENT_HTTP}, {@code queryParams},
 * {@code db.params} y headers— y también cualquier módulo futuro, que no puede olvidarse
 * de aplicarlo.</p>
 *
 * <h2>Dos pasadas</h2>
 * <ol>
 *   <li><b>Estructural</b>: recorre {@code Map} y {@code List} a cualquier profundidad y
 *       sustituye el valor de toda clave sensible. Cubre {@code queryParams},
 *       {@code db.params}, headers y los bodies JSON que llegan ya parseados.</li>
 *   <li><b>Textual</b>: los bodies llegan como {@code String} en cinco de los seis puntos
 *       de captura, así que sobre los valores de {@link #TEXT_KEYS} se enmascaran además
 *       los pares {@code "clave": valor} (JSON) y {@code clave=valor} (query o formulario).
 *       Es <b>best-effort</b> por naturaleza: opera sobre texto, no sobre un árbol, para
 *       funcionar también con bodies truncados o con JSON inválido.</li>
 * </ol>
 *
 * <h2>Comparación de claves</h2>
 * <p>La clave se normaliza (minúsculas, sin {@code _ - . espacio}) y se compara por
 * <b>igualdad exacta</b>, no por inclusión: {@code card_number}, {@code cardNumber} y
 * {@code Card-Number} coinciden entre sí, pero {@code shipping} no activa la regla
 * {@code pin} —que es justo lo que ocurriría comparando por subcadena—.</p>
 *
 * <h2>Configuración</h2>
 * <p>El emitter es una fachada estática sin acceso al contexto de Spring, así que la
 * configuración se instala una vez al arrancar desde {@code StdlogMaskingAutoConfiguration}.
 * Hasta entonces rige la lista incorporada: el enmascarado está activo desde el primer
 * evento, sin necesidad de configurar nada.</p>
 */
public final class StdlogMasker {

    /** Sustituto que se escribe en lugar del valor sensible. */
    public static final String DEFAULT_PLACEHOLDER = "***";

    /**
     * Claves cuyo valor es texto libre que puede llevar pares embebidos. Sólo sobre estas
     * se paga la pasada textual, que es la cara.
     */
    private static final Set<String> TEXT_KEYS = Set.of("body", "statement", "url", "fullpath");

    /** Nombres de clave enmascarados de fábrica, ya normalizados. Ver ADR-0010. */
    public static final List<String> DEFAULT_KEYS = List.of(
            "password", "passwd", "pwd",
            "secret", "clientsecret",
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken",
            "authorization", "proxyauthorization",
            "apikey", "apitoken",
            "credential", "credentials",
            "privatekey",
            "sessionid", "cookie", "setcookie",
            "cardnumber", "pan", "cvv", "cvc",
            "pin", "otp", "ssn", "taxid");

    /** Par {@code "clave": valor} de JSON. El valor puede ser cadena, número, booleano o null. */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"([A-Za-z0-9_.\\-]{1,64})\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)");

    /** Par {@code clave=valor} de query string o formulario. */
    private static final Pattern FORM_PAIR = Pattern.compile(
            "([A-Za-z0-9_.\\-]{1,64})=([^&\\s\"]*)");

    private static volatile Config config = newConfig(true, DEFAULT_KEYS, DEFAULT_PLACEHOLDER);

    private StdlogMasker() {}

    private static Config newConfig(boolean enabled, Collection<String> keys, String placeholder) {
        Set<String> normalized = normalize(keys);
        return new Config(enabled, normalized, placeholder, buildAtoms(normalized));
    }

    /**
     * Construye el cribado: para cada clave, un "átomo" de hasta 3 caracteres indexado por su
     * inicial.
     *
     * <p>Existe por rendimiento. Sin cribado, la pasada textual hace <i>match</i> de <b>cada</b>
     * par del texto y sólo después comprueba si la clave es sensible: sobre un body de 1,2 KB
     * eso costaba unos 118 µs por evento.</p>
     *
     * <p>Se usan 3 caracteres y no la clave entera porque los separadores aparecen entre
     * palabras, nunca dentro de las primeras letras: el átomo {@code api} criba {@code apiKey},
     * {@code api_key} y {@code api-key} por igual. Un átomo corto <b>sobre-coincide</b> —
     * {@code car} también dispara con «carrier»—, y eso es deliberado: una falsa alarma sólo
     * cuesta ejecutar la pasada completa, que entonces no encuentra nada. La decisión
     * autoritativa la sigue tomando {@link Config#matches(String)} sobre la clave normalizada,
     * así que el cribado nunca puede provocar una fuga, sólo trabajo de más.</p>
     */
    private static Atoms buildAtoms(Set<String> normalizedKeys) {
        boolean[] prefix3 = new boolean[26 * 26 * 26];
        List<String> shortKeys = new ArrayList<>();

        for (String key : normalizedKeys) {
            if (key.isEmpty()) continue;
            if (key.length() < 3) { shortKeys.add(key); continue; }
            int idx = packed(key.charAt(0), key.charAt(1), key.charAt(2));
            if (idx >= 0) prefix3[idx] = true;
            else shortKeys.add(key);
        }
        return new Atoms(prefix3, shortKeys.toArray(new String[0]));
    }

    /** Índice del trigrama en la tabla, o -1 si algún carácter no es una letra ASCII. */
    private static int packed(char a, char b, char c) {
        if (a < 'a' || a > 'z' || b < 'a' || b > 'z' || c < 'a' || c > 'z') return -1;
        return (a - 'a') * 676 + (b - 'a') * 26 + (c - 'a');
    }

    private static char lowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    /**
     * Una sola pasada sobre el texto comprobando si menciona alguna clave sensible.
     *
     * <p>Existe por rendimiento: sin este cribado, la pasada textual hace <i>match</i> de
     * <b>cada</b> par del texto y sólo después mira si la clave es sensible, lo que sobre un
     * body de 1,2 KB costaba unos 118 µs por evento.</p>
     *
     * <p>Se criba por los <b>tres primeros caracteres</b> de cada clave, no por la clave
     * entera, porque los separadores caen entre palabras y nunca dentro de las primeras
     * letras: el trigrama {@code api} criba {@code apiKey}, {@code api_key} y {@code api-key}
     * por igual. Tres caracteres <b>sobre-coinciden</b> —{@code car} también dispara con
     * «carrier»—, y es deliberado: una falsa alarma sólo cuesta ejecutar la pasada completa,
     * que entonces no encuentra nada. La decisión autoritativa la sigue tomando
     * {@link Config#matches(String)} sobre la clave normalizada, así que el cribado nunca
     * puede provocar una fuga: como mucho, trabajo de más.</p>
     */
    private static boolean mentionsAnyKey(String text, Config cfg) {
        Atoms atoms = cfg.atoms();
        boolean[] prefix3 = atoms.prefix3();
        int len = text.length();

        for (int i = 0; i + 2 < len; i++) {
            char a = lowerAscii(text.charAt(i));
            if (a < 'a' || a > 'z') continue;
            char b = lowerAscii(text.charAt(i + 1));
            if (b < 'a' || b > 'z') continue;
            char c = lowerAscii(text.charAt(i + 2));
            if (c < 'a' || c > 'z') continue;
            if (prefix3[(a - 'a') * 676 + (b - 'a') * 26 + (c - 'a')]) return true;
        }

        // Claves de una o dos letras: patológicas, pero no se pueden ignorar en silencio.
        for (String key : atoms.shortKeys()) {
            for (int i = 0; i + key.length() <= len; i++) {
                if (text.regionMatches(true, i, key, 0, key.length())) return true;
            }
        }
        return false;
    }

    /** Tabla de cribado: trigramas empaquetados y las claves demasiado cortas para caber en ella. */
    record Atoms(boolean[] prefix3, String[] shortKeys) {}

    /**
     * Instala la configuración del consumidor. La llama la autoconfiguración al arrancar;
     * no es API para el código de negocio.
     *
     * @param enabled     si {@code false}, {@link #mask(Map)} devuelve el payload intacto
     * @param keys        nombres de clave a enmascarar; se normalizan aquí
     * @param placeholder valor sustituto; si es nulo o vacío se usa {@link #DEFAULT_PLACEHOLDER}
     */
    public static void configure(boolean enabled, Collection<String> keys, String placeholder) {
        config = newConfig(
                enabled,
                keys == null ? List.of() : keys,
                (placeholder == null || placeholder.isEmpty()) ? DEFAULT_PLACEHOLDER : placeholder);
    }

    /** Restaura la configuración de fábrica. Uso previsto: aislamiento entre tests. */
    public static void reset() {
        config = newConfig(true, DEFAULT_KEYS, DEFAULT_PLACEHOLDER);
    }

    /**
     * Devuelve el payload con los valores sensibles sustituidos.
     *
     * <p>No modifica el mapa recibido: si hay algo que enmascarar devuelve una copia y, si
     * no, el mismo objeto, para no pagar una asignación en el caso habitual.</p>
     *
     * @param payload payload del evento; puede ser {@code null}
     * @return el payload enmascarado, o el original si no había nada que enmascarar
     */
    public static Map<String, Object> mask(Map<String, Object> payload) {
        Config cfg = config;
        if (payload == null || payload.isEmpty() || !cfg.enabled() || cfg.keys().isEmpty()) {
            return payload;
        }
        Object masked = maskValue(null, payload, cfg, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) masked;
        return out;
    }

    /** Profundidad máxima del recorrido; evita que un payload cíclico o absurdo cueste de más. */
    private static final int MAX_DEPTH = 12;

    private static Object maskValue(String key, Object value, Config cfg, int depth) {
        if (value == null || depth > MAX_DEPTH) return value;

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object original = e.getValue();
                Object replaced = cfg.matches(k) ? cfg.placeholder() : maskValue(k, original, cfg, depth + 1);
                if (replaced != original && copy == null) {
                    copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> seen : map.entrySet()) {
                        if (seen.getKey().equals(e.getKey())) break;
                        copy.put(String.valueOf(seen.getKey()), seen.getValue());
                    }
                }
                if (copy != null) copy.put(k, replaced);
            }
            return copy != null ? copy : value;
        }

        if (value instanceof List<?> list) {
            List<Object> copy = null;
            for (int i = 0; i < list.size(); i++) {
                Object original = list.get(i);
                Object replaced = maskValue(key, original, cfg, depth + 1);
                if (replaced != original && copy == null) copy = new ArrayList<>(list);
                if (copy != null) copy.set(i, replaced);
            }
            return copy != null ? copy : value;
        }

        if (value instanceof Object[] arr) {
            Object[] copy = null;
            for (int i = 0; i < arr.length; i++) {
                Object replaced = maskValue(key, arr[i], cfg, depth + 1);
                if (replaced != arr[i] && copy == null) copy = arr.clone();
                if (copy != null) copy[i] = replaced;
            }
            return copy != null ? copy : value;
        }

        // Pasada textual: sólo sobre las claves que llevan texto libre.
        if (value instanceof String s && key != null && TEXT_KEYS.contains(normalizeKey(key))) {
            return maskText(s, cfg);
        }

        return value;
    }

    /**
     * Enmascara pares {@code "clave": valor} y {@code clave=valor} dentro de una cadena.
     * Best-effort: no parsea, así que también sirve con bodies truncados o JSON inválido.
     */
    static String maskText(String text, Config cfg) {
        if (text == null || text.isEmpty()) return text;
        // Cribado: si el texto no menciona ninguna clave sensible, no se paga el barrido de pares.
        if (!mentionsAnyKey(text, cfg)) return text;
        String out = replacePairs(text, JSON_PAIR, cfg, true);
        return replacePairs(out, FORM_PAIR, cfg, false);
    }

    private static String replacePairs(String text, Pattern pattern, Config cfg, boolean quoteValue) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = null;
        while (m.find()) {
            if (!cfg.matches(m.group(1))) continue;
            if (sb == null) sb = new StringBuilder(text.length());
            String replacement = quoteValue ? '"' + cfg.placeholder() + '"' : cfg.placeholder();
            // Se conserva todo lo que precede al valor (la clave y el separador) y se
            // sustituye sólo el grupo 2, que es el valor.
            String head = text.substring(m.start(), m.start(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(head + replacement));
        }
        if (sb == null) return text;
        m.appendTail(sb);
        return sb.toString();
    }

    private static Set<String> normalize(Collection<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            if (k == null) continue;
            String n = normalizeKey(k);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /**
     * Minúsculas y sin separadores, para que {@code card_number} y {@code cardNumber} coincidan.
     * Devuelve la misma instancia si ya está normalizada, que es el caso de la mayoría de las
     * claves del schema ({@code event}, {@code body}, {@code status}...): así el recorrido
     * estructural no asigna una cadena por clave y por evento.
     */
    static String normalizeKey(String key) {
        boolean alreadyNormalized = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == '.' || c == ' ' || (c >= 'A' && c <= 'Z')) {
                alreadyNormalized = false;
                break;
            }
        }
        if (alreadyNormalized) return key;

        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == '.' || c == ' ') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    record Config(boolean enabled, Set<String> keys, String placeholder, Atoms atoms) {
        boolean matches(String key) {
            return key != null && keys.contains(normalizeKey(key));
        }
    }

    /** Sólo para tests: la lista efectiva de claves normalizadas. */
    static Set<String> activeKeys() {
        return config.keys();
    }
}
