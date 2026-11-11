package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Interruptor remoto de "actualización obligatoria": lee {@link Config#PATH_VERSION} (servido por
 * el backend, fijado a mano en {@code /admin/version}) para saber si la versión instalada
 * ({@code BuildConfig.VERSION_CODE}) quedó por debajo del mínimo vigente.
 *
 * Mismo patrón que {@link EstadoServicio} (el interruptor de "servicio en pausa"): el último
 * estado se guarda en SharedPreferences para que, sin internet al abrir la app, se respete lo
 * último que se supo. Ante cualquier duda (nunca se ha leído, JSON inválido, sin conexión) →
 * NO se bloquea a nadie -- un error aquí jamás debe dejar fuera a todos los usuarios.
 *
 * {@link Backend} también puede marcar el bloqueo AL INSTANTE (vía {@link #forzarEnMemoria}) en
 * cuanto CUALQUIER llamada al backend reciba 426 (defensa adicional, por si alguien manipula el
 * APK para saltarse este chequeo de arranque) -- no hace falta esperar al siguiente refrescar().
 */
// ============================================================
// CLASE    : ActualizacionObligatoria
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Cuando el backend fija una versión mínima (/admin/version) mayor al
// versionCode instalado, esta app DEBE actualizarse antes de seguir. Con
// 'minima' > versionCode local:
//   - requerida() devuelve true;
//   - MainActivity muestra una pantalla fija de "actualiza para continuar",
//     sin botón de cerrar.
//
// El último estado se guarda en SharedPreferences: igual que EstadoServicio,
// así se respeta lo último conocido si no hay internet al abrir la app.
// ============================================================
public final class ActualizacionObligatoria {

    private static final String PREFS = "actualizacion_obligatoria";
    private static final String K_MINIMA = "minima";

    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static volatile int minima = 0;
    private static volatile boolean cargado = false;

    private ActualizacionObligatoria() {}

    /** ¿La versión instalada quedó por debajo del mínimo vigente? Barato: solo compara enteros. */
    public static boolean requerida() {
        return minima > 0 && BuildConfig.VERSION_CODE < minima;
    }

    /** Solo para pruebas (mismo trato que Manifestaciones.limpiarSimulado()): el estado es
     *  estático y persiste entre @Test de una misma clase. */
    static void resetParaPruebas() {
        minima = 0;
        cargado = false;
    }

    /** Carga el último estado guardado (síncrono, sin red). Idempotente. */
    public static void cargar(Context ctx) {
        if (cargado) return;
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        minima = p.getInt(K_MINIMA, 0);
        cargado = true;
    }

    /** Consulta el mínimo vigente en segundo plano. {@code alCambiar} corre en el hilo principal
     *  solo si {@link #requerida()} pasó de false a true respecto al guardado. */
    public static void refrescar(Context ctx, Runnable alCambiar) {
        final Context app = ctx.getApplicationContext();
        cargar(app);
        EXEC.execute(() -> {
            try {
                int nueva;
                try {
                    JSONObject o = new JSONObject(Backend.descargar(Config.PATH_VERSION));
                    nueva = o.optInt("minima", 0);
                } catch (Exception e) {
                    // Sin internet o backend caído: se queda el último estado conocido.
                    return;
                }
                aplicar(app, nueva, alCambiar);
            } catch (Throwable t) {   // un error en este hilo cerraría la app
                android.util.Log.w("GeoMB", "ActualizacionObligatoria: fallo en segundo plano", t);
            }
        });
    }

    /** Actualiza SOLO el estado en memoria, sin persistir (lo llama {@link Backend} al recibir un
     *  426 en cualquier petición -- Backend es una utilidad sin Context, no puede escribir
     *  SharedPreferences). Devuelve true si este cambio hizo que {@link #requerida()} pasara de
     *  false a true (para que Backend decida si vale la pena avisar). El próximo
     *  {@link #refrescar} de MainActivity (con Context) lo persiste igual que siempre. */
    static boolean forzarEnMemoria(int minimaRecibida) {
        boolean antes = requerida();
        if (minimaRecibida > minima) minima = minimaRecibida;
        return !antes && requerida();
    }

    private static void aplicar(Context app, int nueva, Runnable alCambiar) {
        boolean antes = requerida();
        minima = nueva;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(K_MINIMA, nueva).apply();
        boolean ahora = requerida();
        if (!antes && ahora && alCambiar != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try { alCambiar.run(); } catch (Exception e) {
                    Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "ActualizacionObligatoria.aplicar", String.valueOf(e.getMessage()));
                }
            });
        }
    }
}
