package com.memegrados.GeoMB;

import org.json.JSONObject;

import java.util.List;

/**
 * Camino alterno (más barato en ancho de banda del backend) para obtener las posiciones en vivo:
 * en vez de que el servidor descargue y reconvierta el feed completo para servirlo ya armado
 * (lo que hace {@link Config#PATH_VEHICLES} hoy), aquí solo se le pide al servidor la URL firmada
 * de S3 (ver {@link Config#PATH_SYNC}, endpoint neutro adrede -- el backend sigue siendo el ÚNICO
 * que presenta credenciales al proveedor del feed, ningún secreto viaja a esta clase) y la app
 * descarga + decodifica el ".proto" directo de ese proveedor (ver {@link UnidadRtWire}).
 *
 * Necesita que {@link RutasRepository} ya tenga su catálogo cargado, para resolver línea/origen/
 * destino por route_id -- exactamente lo que hoy resuelve el servidor antes de mandar el JSON.
 * Si no está listo, o cualquier otra cosa falla (red, feed caduco, parseo), esta clase LANZA la
 * excepción -- no decide el respaldo por su cuenta; eso le toca al llamador (ver
 * RealtimeRepository.fetch(), que cae de vuelta al camino de siempre sin que el usuario note nada).
 */
final class UnidadRtEdge {

    private UnidadRtEdge() {}

    private static volatile String urlCache;
    private static volatile long expiraEpoch;

    static List<UnidadReal> obtener() throws Exception {
        if (!RutasRepository.estaCargado()) {
            throw new IllegalStateException("catalogo de rutas aun sin cargar");
        }
        String url = urlVigente();
        try {
            byte[] datos = Backend.descargarBinario(url);
            return UnidadRtWire.aUnidades(UnidadRtWire.leerFeed(datos));
        } catch (Exception e) {
            // La URL firmada puede morir ANTES de que caduque nuestro caché local -- igual que
            // poll_loop() en el EC2 (metrobus_app/app.py), que pide una URL nueva DE INMEDIATO si
            // el feed falla, en vez de esperar al siguiente ciclo del timer: se invalida el caché
            // para forzar una llamada fresca a Config.PATH_SYNC en el próximo intento.
            invalidarCache();
            throw e;
        }
    }

    private static synchronized void invalidarCache() {
        urlCache = null;
        expiraEpoch = 0;
    }

    /** URL firmada vigente: la cachea (igual que hace el propio backend) para que, aunque el
     *  mapa pida datos cada pocos segundos, {@link Config#PATH_SYNC} solo se llame de verdad
     *  cuando la URL esté por caducar -- no en cada ciclo de refresco del mapa. */
    private static synchronized String urlVigente() throws Exception {
        long ahoraSeg = System.currentTimeMillis() / 1000L;
        if (urlCache != null && ahoraSeg < expiraEpoch) return urlCache;
        String json = Backend.descargar(Config.PATH_SYNC);
        JSONObject o = new JSONObject(json);
        String u = o.optString("u", "");
        if (u.isEmpty()) throw new java.io.IOException("sin url en " + Config.PATH_SYNC);
        urlCache = u;
        expiraEpoch = o.optLong("e", ahoraSeg + 60);
        return u;
    }
}
