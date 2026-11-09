package com.memegrados.GeoMB;

// ============================================================
// CLASE   : Backend
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Esta clase es el "cartero" de la app: se encarga de PEDIR
// datos al servidor de GeoMB por internet (por ejemplo la lista
// de unidades en tiempo real o el catálogo de rutas) y devolver
// el texto que responde el servidor.
//
// Un solo servidor (Config.BASE_URL, AWS/México) -- el respaldo en
// Railway que existía antes se dio de baja (ya no se paga, ni corre
// su propia copia del backend).
//
// ------------------------------------------------------------
// NOTA: 'final' + constructor privado = clase de UTILIDAD
// ------------------------------------------------------------
//
// No se crean objetos de esta clase; todo es 'static'. Se usa
// directo como  Backend.descargar("/data/vehicles.json").
//
// ============================================================

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Acceso al backend (Config.BASE_URL, único servidor desde que se dio de baja el respaldo de
 *  Railway). */
public final class Backend {

    // Metadatos de la última petición HTTP real (cualquier endpoint), para el diagnóstico técnico
    // de ReporteApp/DiagnosticoReporte -- solo se leen para reportes.
    public static volatile String ultimoEndpoint;
    public static volatile int ultimoHttpStatus;
    public static volatile long ultimaLatenciaMs = -1;

    /** Si no es null, se llama (en el hilo principal) la primera vez que una petición recibe 426
     *  "versión mínima no cumplida" (ver gate_version_minima en el backend). Lo registra
     *  MainActivity para mostrar la pantalla de actualización obligatoria al instante, sin
     *  esperar al próximo ActualizacionObligatoria.refrescar(). */
    public static volatile Runnable alRequerirActualizacion;

    private Backend() {}

    /** GET de un path (p. ej. "/data/vehicles.json") contra el único backend (Config.BASE_URL). */
    public static String descargar(String path) throws java.io.IOException {
        // Servidor apagado a propósito: ni lo intenta.
        if (EstadoServicio.enPausa()) throw new java.io.IOException("servicio en pausa");
        return get(Config.BASE_URL + path);
    }

    // ========================================================
    // MÉTODO: get(urlStr)   [privado]
    // ========================================================
    //
    // Hace la petición HTTP REAL a una URL completa y devuelve
    // el texto de la respuesta.
    //
    // DETALLES IMPORTANTES:
    //
    //   - setConnectTimeout / setReadTimeout: cuánto esperar antes
    //     de rendirse (10 s para conectar, 15 s para leer).
    //   - Si el código de respuesta NO es 2xx (éxito), lanza una
    //     excepción.
    //   - Lee la respuesta línea por línea y la junta en un
    //     StringBuilder.
    //   - El bloque 'finally' cierra la conexión SIEMPRE, haya o
    //     no error (buena práctica: liberar recursos).
    //
    // ========================================================
    private static String get(String urlStr) throws java.io.IOException {
        long inicio = System.currentTimeMillis();
        ultimoHttpStatus = 0;   // 0 = sin respuesta HTTP (falla de conexión/timeout), distinto de un código real
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-App-Version", String.valueOf(BuildConfig.VERSION_CODE));
            conn.connect();
            int status = conn.getResponseCode();
            ultimoEndpoint = urlStr;
            ultimoHttpStatus = status;
            ultimaLatenciaMs = System.currentTimeMillis() - inicio;
            if (status == 426) {
                avisarActualizacionRequerida(conn);
                throw new java.io.IOException("HTTP 426");
            }
            if (status / 100 != 2) throw new java.io.IOException("HTTP " + status);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            return sb.toString();
        } catch (Exception e) {
            ultimoEndpoint = urlStr;
            ultimaLatenciaMs = System.currentTimeMillis() - inicio;
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    /** El backend respondió 426: lee el 'minima' del cuerpo (best-effort) y avisa a
     *  ActualizacionObligatoria + alRequerirActualizacion SIN bloquear ni lanzar -- cualquier
     *  falla aquí se ignora, el que SÍ importa es la excepción que lanza get() después. */
    private static void avisarActualizacionRequerida(HttpURLConnection conn) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            int minima = new org.json.JSONObject(sb.toString()).optInt("minima", 0);
            if (ActualizacionObligatoria.forzarEnMemoria(minima)) {
                Runnable cb = alRequerirActualizacion;
                if (cb != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
            }
        } catch (Exception ignore) {}
    }

    /** GET binario a una URL ABSOLUTA de un tercero (p. ej. la URL firmada de S3 que entrega
     *  {@link Config#PATH_SYNC}) -- sin el prefijo de {@link Config#BASE_URL}: el destino YA
     *  viene resuelto completo y es de otro proveedor. */
    public static byte[] descargarBinario(String urlAbsoluta) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlAbsoluta).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();
            int status = conn.getResponseCode();
            if (status / 100 != 2) throw new java.io.IOException("HTTP " + status);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
