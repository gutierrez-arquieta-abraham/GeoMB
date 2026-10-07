package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Eliminación de cuenta desde la app (ver ConfiguracionFragment → "Eliminar mi cuenta"), parte
 * del cumplimiento de la sección "Seguridad y recopilación de datos" de Google Play.
 *
 * NO duplica la lógica de borrado: solo prueba la identidad del usuario (ID token de Firebase,
 * recién refrescado) y llama a {@code POST /api/account/delete} en {@code metrobus_app}, que es
 * quien de verdad borra Firebase Auth, Firestore, la verificación KYC y (vía X-Device-ID) las
 * alertas/token/ubicación de ESTE dispositivo — ver account_deletion.py. El uid nunca se manda
 * desde aquí: el backend lo obtiene exclusivamente del token verificado.
 *
 * Caso "cuenta ya eliminada" (p. ej. se eliminó desde la página web mientras la app seguía con
 * sesión abierta): al refrescar el ID token, Firebase Auth detecta que el usuario ya no existe y
 * la tarea falla con {@link FirebaseAuthInvalidUserException} ANTES de llegar siquiera al
 * backend — se distingue de un error de red/servidor para informar correctamente al usuario.
 */
public final class EliminarCuenta {

    private EliminarCuenta() {}

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        /** Se borró todo correctamente. */
        void onExito();
        /** La cuenta ya no existía (se adelantó otra vía) -- mismo resultado final para el usuario. */
        void onCuentaYaEliminada();
        /** No se pudo completar (sin sesión, sin red, o el servidor rechazó la solicitud). */
        void onError(String mensaje);
    }

    /** Dispara la eliminación. Todos los callbacks llegan en el hilo PRINCIPAL. */
    public static void eliminar(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        FirebaseUser u;
        try {
            u = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Throwable ex) {
            u = null;
        }
        if (u == null) {
            callback.onError(app.getString(R.string.eliminar_cuenta_sin_sesion));
            return;
        }
        // getIdToken(true) fuerza el refresco (no uno cacheado que ya podría estar vencido) --
        // es también el momento en que Firebase detecta si la cuenta ya no existe (ver arriba).
        u.getIdToken(true).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().getToken() != null) {
                String idToken = task.getResult().getToken();
                String deviceId = DeviceUtils.idDispositivo(app);
                executor.execute(() -> ejecutarBorrado(app, main, idToken, deviceId, callback));
                return;
            }
            Exception ex = task.getException();
            if (ex instanceof FirebaseAuthInvalidUserException) {
                callback.onCuentaYaEliminada();
            } else {
                callback.onError(app.getString(R.string.eliminar_cuenta_error_token));
            }
        });
    }

    private static void ejecutarBorrado(Context app, Handler main, String idToken, String deviceId,
                                        Callback callback) {
        int status;
        String fallo = null;
        try {
            status = enviar(idToken, deviceId);
        } catch (Exception e) {
            status = -1;
            fallo = String.valueOf(e.getMessage());
        }
        final int st = status;
        final String err = fallo;
        main.post(() -> {
            if (st == 200) {
                callback.onExito();
            } else {
                if (st != -1) {
                    // -1 = fallo de red (ya se registró el mensaje); un status real del servidor
                    // (401/429/5xx) también se registra para diagnóstico, nunca se le expone el
                    // detalle crudo al usuario.
                    Telemetria.registrarError(app, Telemetria.ERR_RED, "EliminarCuenta.ejecutarBorrado",
                            "status=" + st);
                } else {
                    Telemetria.registrarError(app, Telemetria.ERR_RED, "EliminarCuenta.ejecutarBorrado", err);
                }
                callback.onError(app.getString(R.string.eliminar_cuenta_error_servidor));
            }
        });
    }

    private static int enviar(String idToken, String deviceId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(Config.ACCOUNT_DELETE_API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("X-Device-ID", deviceId);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.write(new byte[0]);
            }
            int codigo = conn.getResponseCode();
            // Se consume el cuerpo de la respuesta (aunque no se use) para liberar la conexión
            // limpiamente -- mismo criterio que AlertasBackend.enviar/Asistente.post.
            InputStream is = codigo / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* descartado */ }
                }
            }
            return codigo;
        } finally {
            conn.disconnect();
        }
    }
}
