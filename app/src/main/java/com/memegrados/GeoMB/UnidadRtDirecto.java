package com.memegrados.GeoMB;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ÚLTIMO recurso si el backend MISMO no responde (no solo el feed en vivo): la app llama DIRECTO
 * al proveedor con sus propias credenciales, para que el mapa en vivo pueda seguir funcionando
 * aunque el EC2/Oracle esté caído de verdad (p. ej. por un corte de pago) -- sin depender por
 * completo de un servidor propio para esta sola función.
 *
 * Por diseño esto NUNCA es el camino normal -- ver su único llamador, {@link RealtimeRepository},
 * que solo llega aquí cuando YA fallaron {@link UnidadRtEdge} (URL firmada vía backend) Y el JSON
 * de siempre ({@link Config#PATH_VEHICLES}). Si fuera el camino normal, cada dispositivo pediría
 * su propio token al proveedor por separado (hoy es 1 servidor para todos) -- eso multiplica la
 * carga de autenticación por el número de usuarios activos y es justo el patrón que un sistema
 * antifraude normal de una API marcaría como credencial compartida. Como respaldo RARO (solo
 * cuando el backend ya está roto), el riesgo es aceptable.
 *
 * Respeta {@link EstadoServicio#enPausa()}: una pausa marcada a propósito apaga TODO, incluido
 * este respaldo.
 *
 * <p>CREDENCIALES: {@code BuildConfig.SONDA_USUARIO}/{@code SONDA_SENHA}, generados desde un
 * archivo LOCAL fuera del repo ({@code sonda.properties}, en la raíz del proyecto, junto a
 * {@code settings.gradle.kts} -- mismo patrón que {@code key.properties} para la firma de la
 * app). El archivo NUNCA pasa por el asistente ni por git (ver .gitignore). Formato esperado
 * (2 líneas, sin comillas):
 * <pre>
 *   sonda.usuario=TU_USUARIO
 *   sonda.senha=TU_CONTRASEÑA
 * </pre>
 * Sin ese archivo (cualquier checkout nuevo, o CI), los campos de BuildConfig quedan vacíos y
 * este respaldo simplemente no se activa -- nunca rompe la compilación ni el resto de la app.
 */
final class UnidadRtDirecto {

    private UnidadRtDirecto() {}

    private static final String URL_VALIDACION =
            "https://metrobus-gtfs.sinopticoplus.com/gtfs-api/partnerValidation";

    private static volatile String urlCache;
    private static volatile long expiraEpoch;

    static List<UnidadReal> obtener() throws Exception {
        if (EstadoServicio.enPausa()) throw new IllegalStateException("servicio en pausa");
        if (BuildConfig.SONDA_USUARIO.isEmpty() || BuildConfig.SONDA_SENHA.isEmpty()) {
            throw new IllegalStateException("sin credenciales locales (sonda.properties)");
        }
        if (!RutasRepository.estaCargado()) {
            throw new IllegalStateException("catalogo de rutas aun sin cargar");
        }
        String url = urlVigente();
        try {
            byte[] datos = Backend.descargarBinario(url);
            return UnidadRtWire.aUnidades(UnidadRtWire.leerFeed(datos));
        } catch (Exception e) {
            // La URL firmada puede morir ANTES de que caduque nuestro caché local (revocada,
            // feed reiniciado del lado de Sonda, etc.) -- igual que poll_loop() en el EC2
            // (metrobus_app/app.py), que pide una URL nueva DE INMEDIATO si el feed falla, en vez
            // de esperar al siguiente ciclo del timer: se invalida el caché para forzar una
            // llamada fresca a partnerValidation en el próximo intento, en vez de reintentar la
            // misma URL muerta hasta por 8 min más.
            invalidarCache();
            throw e;
        }
    }

    private static synchronized void invalidarCache() {
        urlCache = null;
        expiraEpoch = 0;
    }

    private static synchronized String urlVigente() throws Exception {
        long ahoraSeg = System.currentTimeMillis() / 1000L;
        if (urlCache != null && ahoraSeg < expiraEpoch) return urlCache;

        JSONObject cuerpo = new JSONObject();
        cuerpo.put("usuario", BuildConfig.SONDA_USUARIO);
        cuerpo.put("senha", BuildConfig.SONDA_SENHA);
        byte[] salida = cuerpo.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_VALIDACION).openConnection();
        String cuerpoResp;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(salida); }
            int status = conn.getResponseCode();
            if (status / 100 != 2) throw new java.io.IOException("HTTP " + status);
            cuerpoResp = leer(conn.getInputStream());
        } finally {
            conn.disconnect();
        }

        JSONObject resp = new JSONObject(cuerpoResp);
        String url = resp.optString("urlRealTime", "");
        if (url.isEmpty()) throw new java.io.IOException("sin urlRealTime");
        urlCache = url;
        // Dura 600 s de verdad (manual de Sonda); se deja de usar con margen, mismo criterio que
        // edge_rt_backend.py, para nunca intentar con una URL a punto de caducar.
        expiraEpoch = ahoraSeg + (600 - 120);
        return url;
    }

    private static String leer(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        in.close();
        return sb.toString();
    }
}
