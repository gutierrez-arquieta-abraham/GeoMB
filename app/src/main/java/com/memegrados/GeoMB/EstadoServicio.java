package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interruptor remoto de "Servicio en pausa": lee {@link Config#ESTADO_SERVICIO_URL} (un JSON en
 * GitHub, FUERA del EC2) para saber si el backend está apagado a propósito.
 *
 * Formato:  {"pausa": true, "titulo": "Servicio en pausa", "mensaje": "..."}
 *           (titulo/mensaje son opcionales; si faltan se usan los textos de strings.xml).
 */
// ============================================================
// CLASE    : EstadoServicio
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Cuando el servidor se apaga para no pagarlo, la app solo veía errores de
// red: toasts de "sin conexión", el sondeo de respaldo (SincronizacionService)
// arrancando solo y reintentos inútiles cada 10 s contra un servidor muerto.
//
// Este interruptor vive en GitHub (raw.githubusercontent.com) porque tiene que
// seguir respondiendo con el EC2 apagado. Con "pausa": true:
//   - Backend.descargar() falla al instante sin tocar la red;
//   - no arranca el sondeo de respaldo ni se muestran toasts de error;
//   - MainActivity muestra UNA vez por arranque el aviso con el mensaje.
//
// El último estado se guarda en SharedPreferences: así, si al abrir la app
// no hay internet o GitHub no responde, se respeta lo último que se supo.
// Ante cualquier duda (nunca se ha leído, JSON inválido) → NO hay pausa.
// ============================================================
public final class EstadoServicio {

    private static final String PREFS = "estado_servicio";
    private static final String K_PAUSA = "pausa";
    private static final String K_TITULO = "titulo";
    private static final String K_MENSAJE = "mensaje";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean pausa = false;
    private static volatile String titulo = "";
    private static volatile String mensaje = "";
    private static volatile boolean cargado = false;

    private EstadoServicio() {}

    /** ¿El backend está en pausa (apagado a propósito)? Barato: solo lee una variable. */
    public static boolean enPausa() {
        return pausa;
    }

    /** Título del aviso (el del JSON o, si no trae, el de strings.xml). */
    public static String titulo(Context c) {
        return titulo.isEmpty() ? c.getString(R.string.pausa_titulo) : titulo;
    }

    /** Mensaje del aviso (el del JSON o, si no trae, el de strings.xml). */
    public static String mensaje(Context c) {
        return mensaje.isEmpty() ? c.getString(R.string.pausa_mensaje) : mensaje;
    }

    /** Carga el último estado guardado (síncrono, sin red). Idempotente. */
    public static void cargar(Context ctx) {
        if (cargado) return;
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pausa = p.getBoolean(K_PAUSA, false);
        titulo = p.getString(K_TITULO, "");
        mensaje = p.getString(K_MENSAJE, "");
        cargado = true;
    }

    /** Consulta el interruptor en segundo plano. {@code alCambiar} corre en el hilo principal
     *  solo si el estado de pausa cambió respecto al guardado. */
    public static void refrescar(Context ctx, Runnable alCambiar) {
        final Context app = ctx.getApplicationContext();
        cargar(app);
        EXEC.execute(() -> {
            boolean nuevaPausa;
            String nuevoTitulo, nuevoMensaje;
            try {
                JSONObject o = new JSONObject(descargar(Config.ESTADO_SERVICIO_URL));
                nuevaPausa = o.optBoolean("pausa", false);
                nuevoTitulo = o.isNull("titulo") ? "" : o.optString("titulo", "");
                nuevoMensaje = o.isNull("mensaje") ? "" : o.optString("mensaje", "");
            } catch (Exception e) {
                // Sin internet o GitHub caído: se queda el último estado conocido.
                return;
            }
            boolean cambio = nuevaPausa != pausa;
            pausa = nuevaPausa;
            titulo = nuevoTitulo;
            mensaje = nuevoMensaje;
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(K_PAUSA, nuevaPausa)
                    .putString(K_TITULO, nuevoTitulo)
                    .putString(K_MENSAJE, nuevoMensaje)
                    .apply();
            if (cambio && alCambiar != null) {
                MAIN.post(() -> {
                    try { alCambiar.run(); } catch (Exception e) {
                        Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "EstadoServicio.refrescar", String.valueOf(e.getMessage()));
                    }
                });
            }
        });
    }

    // GET directo (sin Backend): este archivo NO vive en el EC2 ni en Railway, y Backend.descargar()
    // es justo lo que se corta cuando hay pausa.
    private static String descargar(String urlStr) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setUseCaches(false);
            int status = conn.getResponseCode();
            if (status / 100 != 2) throw new java.io.IOException("HTTP " + status);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
