package com.memegrados.GeoMB;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Acceso al backend con FAILOVER automático: primero el principal (AWS/México) y, si no
 * responde, el de respaldo (Railway). Recuerda cuál funcionó para no reintentar el caído en
 * cada petición; pero reintenta el PRINCIPAL cada {@link #REINTENTO_PRIMARIO_MS} aunque siga
 * "activo" el respaldo, para no quedarse pegado a él indefinidamente si el respaldo funciona
 * (no lanza excepción) pero sirve datos incompletos (p. ej. menos unidades en tiempo real) -
 * antes, una sola falla transitoria del principal dejaba la app en el respaldo para siempre.
 */
public final class Backend {

    private static volatile String activo = Config.BASE_URL;
    private static volatile long ultimoIntentoPrimario = 0L;
    private static final long REINTENTO_PRIMARIO_MS = 30_000L;   // vuelve a probar el principal cada 30 s

    private Backend() {}

    /** GET de un path (p. ej. "/data/vehicles.json") con failover entre los dos backends. */
    public static String descargar(String path) throws Exception {
        boolean tocaReintentarPrimario = !activo.equals(Config.BASE_URL)
                && (System.currentTimeMillis() - ultimoIntentoPrimario) > REINTENTO_PRIMARIO_MS;
        String primero = tocaReintentarPrimario ? Config.BASE_URL : activo;
        String otro = primero.equals(Config.BASE_URL) ? Config.FALLBACK_URL : Config.BASE_URL;
        if (primero.equals(Config.BASE_URL)) ultimoIntentoPrimario = System.currentTimeMillis();
        try {
            String r = get(primero + path);
            activo = primero;
            return r;
        } catch (Exception e1) {
            String r = get(otro + path);   // el activo falló: usa el otro y recuérdalo
            activo = otro;
            return r;
        }
    }

    private static String get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
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
