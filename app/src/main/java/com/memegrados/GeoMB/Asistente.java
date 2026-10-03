package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
 * Cliente del asistente conversacional (Gemini + Function Calling, backend Python en
 * integrations/gemini/, detrás de Nginx en {@link Config#ASISTENTE_CHAT_URL}).
 *
 * Sigue el mismo estilo de red que {@link Backend} (HttpURLConnection, hilo aparte + Handler
 * al hilo principal), pero es un POST con JSON y no tiene failover: el asistente vive en un
 * solo backend. Cada instalación manda su {@link DeviceUtils#idDispositivo(Context)} como
 * header X-Device-ID, que el servidor usa para la sesión de conversación y la cuota diaria.
 */
public final class Asistente {

    private Asistente() {}

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onRespuesta(String texto);
        /** Cuota diaria agotada (HTTP 429 con {"error":"cuota_agotada", ...}): aviso, no error genérico. */
        void onCuotaAgotada(String mensaje);
        void onError(String mensaje);
    }

    /** Compatibilidad con llamadas existentes: igual que {@link #enviar(Context, String, boolean,
     *  JSONObject, Callback)} pero sin contexto de dispositivo. */
    public static void enviar(Context ctx, String mensaje, boolean reiniciar, Callback cb) {
        enviar(ctx, mensaje, reiniciar, null, cb);
    }

    /** El try/catch de aquí solo cubre la petición HTTP; cb.* corre DESPUÉS, dentro del
     *  post() al hilo principal, así que se protege aparte (mismo criterio que
     *  RealtimeRepository.fetch()): una excepción de la UI que consume la respuesta no debe
     *  tumbar el hilo de red ni dejar la app en un estado raro.
     *
     *  @param contextoDispositivo contexto de tracking (ver DiagnosticoReporte.contextoTracking),
     *  o null para no mandar ninguno -- el backend lo trata como ausente igual en ambos casos. */
    public static void enviar(Context ctx, String mensaje, boolean reiniciar, JSONObject contextoDispositivo, Callback cb) {
        String deviceId = DeviceUtils.idDispositivo(ctx);
        executor.execute(() -> {
            try {
                JSONObject cuerpo = new JSONObject();
                cuerpo.put("mensaje", mensaje);
                cuerpo.put("reiniciar", reiniciar);
                if (contextoDispositivo != null && contextoDispositivo.length() > 0) {
                    cuerpo.put("contextoDispositivo", contextoDispositivo);
                }
                Resultado r = post(Config.ASISTENTE_CHAT_URL, deviceId, cuerpo.toString());

                if (r.codigo == 429) {
                    String msgCuota = extraerCampo(r.cuerpo, "mensaje",
                            "Ya usaste tu límite de mensajes del asistente por hoy. Vuelve mañana.");
                    main.post(() -> {
                        try { cb.onCuotaAgotada(msgCuota); } catch (Exception e) { registrarFallo("Asistente.onCuotaAgotada", e); }
                    });
                    return;
                }
                if (r.codigo / 100 != 2) throw new Exception("HTTP " + r.codigo);

                String respuesta = extraerCampo(r.cuerpo, "respuesta", "");
                main.post(() -> {
                    try { cb.onRespuesta(respuesta); } catch (Exception e) { registrarFallo("Asistente.onRespuesta", e); }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "error de red";
                GeoMBApplication app = GeoMBApplication.get();
                if (app != null) Telemetria.registrarError(app, Telemetria.ERR_RED, "Asistente.enviar", msg);
                main.post(() -> {
                    try { cb.onError(msg); } catch (Exception e2) { registrarFallo("Asistente.onError", e2); }
                });
            }
        });
    }

    private static void registrarFallo(String origen, Exception e) {
        GeoMBApplication app = GeoMBApplication.get();
        if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, origen, String.valueOf(e.getMessage()));
    }

    private static String extraerCampo(String cuerpoJson, String campo, String porDefecto) {
        try {
            return new JSONObject(cuerpoJson).optString(campo, porDefecto);
        } catch (Exception e) {
            return porDefecto;
        }
    }

    private static final class Resultado {
        final int codigo;
        final String cuerpo;
        Resultado(int codigo, String cuerpo) { this.codigo = codigo; this.cuerpo = cuerpo; }
    }

    private static Resultado post(String urlStr, String deviceId, String cuerpoJson) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000); // el ciclo de Function Calling puede tardar varias vueltas
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-ID", deviceId);
            byte[] datos = cuerpoJson.getBytes(StandardCharsets.UTF_8);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.write(datos);
            }
            int codigo = conn.getResponseCode();
            InputStream is = codigo / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                }
            }
            return new Resultado(codigo, sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
