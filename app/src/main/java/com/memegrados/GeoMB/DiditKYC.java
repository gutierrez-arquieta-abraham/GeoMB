package com.memegrados.GeoMB;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Integración con el proveedor KYC Didit (verificación de identidad).
 *
 * Flujo alojado (el más rápido de integrar): TU backend crea la sesión con la API key de Didit
 * (que NO debe ir en el cliente) y devuelve la URL de verificación; la app la abre en el navegador.
 * Cuando Didit aprueba, el backend recibe el webhook y marca al usuario; la app puede consultar el
 * estado o recibir un enlace de retorno y llamar a {@link Verificacion#marcarVerificado}.
 *
 * CONFIGURA {@link #SESSION_ENDPOINT} con el endpoint de tu backend (AWS) que devuelva
 * {"url":"https://verify.didit.me/session/..."}. Mientras esté vacío, el módulo usa el modo pruebas.
 * Plan gratuito de Didit: 500 verificaciones/mes.
 */
public final class DiditKYC {

    /** Backend GeoMB (mismo del feed). Los endpoints KYC viven en didit_backend.py. */
    public static final String SESSION_ENDPOINT = "https://geomb.duckdns.org/api/didit/session";
    public static final String STATUS_ENDPOINT = "https://geomb.duckdns.org/api/didit/status";

    private DiditKYC() {}

    public static boolean configurado() {
        return SESSION_ENDPOINT != null && !SESSION_ENDPOINT.trim().isEmpty();
    }

    /** Resultado de consultar el estado de verificación en el backend. */
    public interface EstadoCallback {
        void onEstado(boolean verificado, String nombre);
    }

    /** Consulta (en segundo plano) si el usuario ya quedó verificado tras completar Didit. */
    public static void consultarEstado(String uid, EstadoCallback cb) {
        if (uid == null || uid.isEmpty()) return;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean[] verif = {false};
            String[] nom = {""};
            HttpURLConnection c = null;
            try {
                URL u = new URL(STATUS_ENDPOINT + "?uid=" + Uri.encode(uid));
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                if (c.getResponseCode() / 100 == 2) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject o = new JSONObject(sb.toString());
                    verif[0] = o.optBoolean("verificado", false);
                    nom[0] = o.optString("nombre", "");
                }
            } catch (Exception ignore) {
            } finally {
                if (c != null) c.disconnect();
            }
            main.post(() -> cb.onEstado(verif[0], nom[0]));
        }, "didit-status").start();
    }

    /**
     * Pide al backend la URL de sesión (en segundo plano) y la abre en el navegador para que el
     * usuario complete la verificación (ID + rostro en vivo) con Didit.
     */
    public static void abrir(Context ctx, String uidUsuario) {
        if (!configurado()) return;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String url = pedirUrlSesion(uidUsuario);
            main.post(() -> {
                if (url == null) {
                    Toast.makeText(ctx, R.string.verif_error, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignore) {
                    Toast.makeText(ctx, R.string.verif_error, Toast.LENGTH_LONG).show();
                }
            });
        }, "didit-session").start();
    }

    /** Llama al backend y extrae la URL de la sesión de verificación. */
    private static String pedirUrlSesion(String uidUsuario) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(SESSION_ENDPOINT + (uidUsuario != null ? "?uid=" + Uri.encode(uidUsuario) : ""));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() / 100 != 2) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            String url = o.optString("url", null);
            return (url != null && !url.isEmpty()) ? url : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
