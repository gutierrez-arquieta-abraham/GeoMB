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
// ------------------------------------------------------------
// ¿QUÉ ES FAILOVER (RESPALDO)?
// ------------------------------------------------------------
//
// Existen DOS servidores: uno PRINCIPAL y uno de RESPALDO.
// "Failover" significa que, si el principal no contesta, la app
// cambia SOLA al de respaldo, sin que el usuario note nada.
//
// Es como tener dos llaves de agua: si una no da agua, abres la
// otra automáticamente.
//
// ------------------------------------------------------------
// IDEA CLAVE
// ------------------------------------------------------------
//
// La clase RECUERDA cuál servidor funcionó la última vez (campo
// 'activo'), para no volver a intentar el caído en cada petición.
// Pero cada 30 segundos vuelve a probar el PRINCIPAL, para no
// quedarse pegada al respaldo indefinidamente.
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

    // ========================================================
    // MÉTODO: descargar(path)   [público]
    // ========================================================
    //
    // Es el método que usa el RESTO de la app para pedir datos.
    //
    // Recibe un "path" (la ruta del recurso), por ejemplo:
    //
    //     "/data/vehicles.json"
    //
    // y devuelve el texto (JSON) que respondió el servidor.
    //
    // PASOS:
    //
    //   1. Decide con cuál servidor intentar PRIMERO: si ya toca
    //      reintentar el principal (pasaron 30 s) usa el principal;
    //      si no, sigue con el que estaba 'activo'.
    //   2. Intenta descargar del primero → si funciona, lo guarda
    //      como 'activo' y devuelve el resultado.
    //   3. Si falla, intenta con el OTRO servidor y recuerda cuál sirvió.
    //
    // ========================================================
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
    //     excepción para que 'descargar' pruebe el otro servidor.
    //   - Lee la respuesta línea por línea y la junta en un
    //     StringBuilder.
    //   - El bloque 'finally' cierra la conexión SIEMPRE, haya o
    //     no error (buena práctica: liberar recursos).
    //
    // ========================================================
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
