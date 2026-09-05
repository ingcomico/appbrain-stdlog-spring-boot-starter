package appbrain.stdlog.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Garantiza la invariante de {@code ADR-0011}: <b>un fallo del logging nunca altera el
 * resultado de la operación instrumentada</b>, y <b>nunca ocurre en silencio</b>.
 *
 * <h2>Por qué existe</h2>
 * <p>La protección vivía sólo en los módulos reactivos; el filtro servlet, el listener JDBC y
 * el interceptor HTTP emitían sin red. El punto más expuesto es el bloque {@code finally} del
 * filtro servlet, que corre <em>después</em> de generar la respuesta: una excepción allí
 * convierte un request correcto en un error del cliente.</p>
 *
 * <h2>Dos capas</h2>
 * <p>{@link StdlogEmitter} envuelve la emisión con esta clase, lo que cubre a todos los módulos
 * por construcción. Pero el emitter recibe el payload <b>ya construido</b>, así que cada punto
 * de instrumentación envuelve además su bloque de «construir payload + emitir»: el riesgo real
 * está en la construcción, y la red del emitter no la alcanza.</p>
 *
 * <h2>Qué se captura, y qué no</h2>
 * <p>{@link RuntimeException} y {@link LinkageError}. El segundo cubre un classpath incompleto,
 * que es un fallo de configuración del que tiene sentido recuperarse. El resto de {@link Error}
 * se deja pasar: tragarse un {@code OutOfMemoryError} para salvar una línea de log es peor que
 * el fallo que se intenta evitar.</p>
 *
 * <h2>Nunca en silencio, pero con freno</h2>
 * <p>Descartar la excepción sin más también es perder datos, sólo que sin avisar: un evento que
 * no se emite por un fallo silencioso es indistinguible de uno que no debía emitirse. Por eso
 * cada fallo se registra en el logger {@code appbrain.stdlog.internal} —nunca en {@code stdlog},
 * para no arriesgar recursión si el fallo está en la propia ruta de emisión—. Y como un fallo
 * sistemático convertiría ese aviso en la inundación que se quería evitar, sólo se registran el
 * 1.º, el 10.º, el 100.º… junto con el total acumulado.</p>
 */
public final class StdlogFailsafe {

    /** Logger separado de {@code stdlog} a propósito: evita recursión si falla la emisión. */
    private static final Logger INTERNAL = LoggerFactory.getLogger("appbrain.stdlog.internal");

    private static final AtomicLong FAILURES = new AtomicLong();

    private StdlogFailsafe() {}

    /**
     * Ejecuta una emisión protegida. Si falla, lo registra y vuelve con normalidad.
     *
     * @param emission bloque de construcción y/o emisión de un evento
     */
    public static void run(Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException | LinkageError failure) {
            report(failure);
        }
    }

    /**
     * Variante para cuando el bloque protegido produce un valor que la operación instrumentada
     * necesita — por ejemplo la respuesta re-leíble que devuelve el interceptor HTTP saliente.
     *
     * @param emission bloque a ejecutar
     * @param fallback valor a devolver si la emisión falla; debe dejar la operación intacta
     * @return el resultado de {@code emission}, o {@code fallback} si falló
     */
    public static <T> T call(Supplier<T> emission, T fallback) {
        try {
            return emission.get();
        } catch (RuntimeException | LinkageError failure) {
            report(failure);
            return fallback;
        }
    }

    /**
     * Registra un fallo de logging ya capturado. La usan los módulos que necesitan su propio
     * {@code try/catch} por la forma de su código, en lugar de {@link #run(Runnable)}.
     *
     * @param failure excepción capturada; si es {@code null} no hace nada
     */
    public static void report(Throwable failure) {
        if (failure == null) return;
        long total = FAILURES.incrementAndGet();
        if (!isPowerOfTen(total)) return;

        INTERNAL.warn("stdlog no pudo emitir un evento; la operacion instrumentada no se ve afectada. "
                + "Fallos acumulados: {}. Este aviso se repite en el 1, 10, 100... para no inundar.",
                total, failure);
    }

    /** Número de fallos capturados desde que arrancó la JVM. Diagnóstico y tests. */
    public static long failureCount() {
        return FAILURES.get();
    }

    /** Reinicia el contador. Uso previsto: aislamiento entre tests. */
    public static void resetFailureCount() {
        FAILURES.set(0);
    }

    private static boolean isPowerOfTen(long n) {
        for (long p = 1; p <= n; p *= 10) {
            if (p == n) return true;
        }
        return false;
    }
}
