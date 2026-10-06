package com.memegrados.GeoMB;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cliente HTTP de las alertas de proximidad de unidades guardadas ({@code device_alertas.py} +
 * los endpoints nuevos de {@code app.py} en {@code metrobus_app} — ver
 * {@link Config#DEVICE_TOKEN_URL}/{@link Config#DEVICE_ALERTA_URL}). Mismo estilo que
 * {@link Asistente}: {@code HttpURLConnection}, un executor propio + nada de hilo principal
 * (estas llamadas no tienen resultado que la UI necesite esperar). A propósito SIN failover a
 * {@code Config.FALLBACK_URL} — a diferencia de {@link Backend} (que sí cae a Railway para el
 * feed de solo lectura), esto ESCRIBE en el SQLite que {@code push_metrobus.py} lee en el EC2;
 * si cayera a Railway, la escritura "tendría éxito" contra un almacén que el detector de
 * proximidad nunca ve — peor que simplemente fallar y reintentar más tarde. El dispositivo se
 * identifica SIEMPRE por {@code X-Device-ID} ({@link DeviceUtils}), nunca por uid de Firebase
 * Auth (un mismo usuario puede tener varios dispositivos).
 *
 * Todas las llamadas son "fire and forget" desde la UI: Room ya quedó actualizado ANTES de
 * llamar aquí (ver {@link Telemetria}), así que un fallo de red nunca bloquea ni revierte la
 * preferencia local — solo se registra con {@link Telemetria#registrarError}, el mismo
 * mecanismo que ya usa el resto de la app para errores no bloqueantes; nunca se le muestra al
 * usuario. No hay una cola de reintentos nueva: {@link #resincronizarTodo} re-declara el estado
 * local completo (token cacheado + unidades con alerta activa) de forma idempotente (el backend
 * hace upsert) cada vez que arranca la app (ver {@link GeoMBApplication}) — esto recupera tanto
 * un fallo de red pasado como una reinstalación, sin rastrear cuáles llamadas en particular
 * fallaron.
 */
public final class AlertasBackend {

    private AlertasBackend() {}

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String PREFS = "geomb";
    private static final String KEY_FCM_TOKEN = "fcm_token_cache";

    // ---------------------------------------------------------------- token FCM (por dispositivo)

    /** Registra/actualiza el token FCM de este dispositivo. Lo cachea en SharedPreferences
     *  ANTES de intentar la red (para que {@link #resincronizarTodo} siempre tenga algo que
     *  reintentar en el próximo arranque, aunque esta llamada falle). */
    public static void registrarToken(Context c, String fcmToken) {
        if (fcmToken == null || fcmToken.isEmpty()) return;
        Context app = c.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FCM_TOKEN, fcmToken).apply();
        executor.execute(() -> {
            try {
                JSONObject cuerpo = new JSONObject();
                cuerpo.put("fcmToken", fcmToken);
                enviar(Config.DEVICE_TOKEN_URL, "POST", DeviceUtils.idDispositivo(app), cuerpo.toString());
            } catch (Exception e) {
                registrarFallo(app, "AlertasBackend.registrarToken", e);
            }
        });
    }

    // ---------------------------------------------------------------- preferencia de alerta

    /** Sincroniza con el backend la preferencia de UNA unidad (activar, desactivar o cambiar
     *  radio son la misma llamada: un upsert con el estado actual completo). */
    public static void actualizarAlerta(Context c, String economico, boolean activa, int radioM) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        executor.execute(() -> {
            try {
                JSONObject cuerpo = new JSONObject();
                cuerpo.put("economico", economico);
                cuerpo.put("alertaActiva", activa);
                cuerpo.put("radioAlertaM", radioM);
                enviar(Config.DEVICE_ALERTA_URL, "POST", DeviceUtils.idDispositivo(app), cuerpo.toString());
            } catch (Exception e) {
                registrarFallo(app, "AlertasBackend.actualizarAlerta", e);
            }
        });
    }

    /** Baja completa de la preferencia en el backend -- se llama cuando la unidad se quita de
     *  "Guardadas" por completo (no solo al apagar su alerta), para no dejar una fila huérfana
     *  que el detector de proximidad seguiría evaluando. */
    public static void eliminarAlerta(Context c, String economico) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        executor.execute(() -> {
            try {
                JSONObject cuerpo = new JSONObject();
                cuerpo.put("economico", economico);
                enviar(Config.DEVICE_ALERTA_URL, "DELETE", DeviceUtils.idDispositivo(app), cuerpo.toString());
            } catch (Exception e) {
                registrarFallo(app, "AlertasBackend.eliminarAlerta", e);
            }
        });
    }

    // ---------------------------------------------------------------- reconciliación al iniciar la app

    /** Re-declara al backend el token cacheado (si hay uno) y TODAS las unidades con alerta
     *  activa en Room -- ver el javadoc de la clase. Se llama una vez por arranque desde
     *  {@link GeoMBApplication}. */
    public static void resincronizarTodo(Context c) {
        Context app = c.getApplicationContext();
        String token = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FCM_TOKEN, null);
        if (token != null) registrarToken(app, token);
        Telemetria.listaFavoritos(app, favoritos -> {
            for (EconomicoFavoritoEntity f : favoritos) {
                if (f.alertaActiva) actualizarAlerta(app, f.economico, true, f.radioAlertaM);
            }
        });
    }

    private static void registrarFallo(Context app, String origen, Exception e) {
        Telemetria.registrarError(app, Telemetria.ERR_RED, origen, String.valueOf(e.getMessage()));
    }

    private static void enviar(String urlStr, String metodo, String deviceId, String cuerpoJson) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod(metodo);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-ID", deviceId);
            byte[] datos = cuerpoJson.getBytes(StandardCharsets.UTF_8);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.write(datos);
            }
            int codigo = conn.getResponseCode();
            // Se consume el cuerpo de la respuesta (aunque no se use) para liberar la conexión
            // limpiamente -- mismo criterio que Asistente.post().
            InputStream is = codigo / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* descartado */ }
                }
            }
        } finally {
            conn.disconnect();
        }
    }
}
