package com.memegrados.GeoMB;

import org.json.JSONObject;

import java.util.ArrayList;
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
        byte[] datos = Backend.descargarBinario(urlVigente());
        List<UnidadRtWire.Registro> crudos = UnidadRtWire.leerFeed(datos);
        List<UnidadReal> lista = new ArrayList<>(crudos.size());
        for (UnidadRtWire.Registro c : crudos) {
            if (Float.isNaN(c.lat) || Float.isNaN(c.lon)) continue;

            String numero = (c.label != null && !c.label.isEmpty()) ? c.label
                    : (c.id != null && !c.id.isEmpty() ? c.id : "—");
            String low = numero.toLowerCase();
            if (low.contains("test") || low.contains("maqueta") || low.contains("carraci")) continue;

            Ruta ruta = RutasRepository.porRouteId(c.routeId);
            Integer linea = (ruta != null && ruta.linea != 0) ? ruta.linea : null;
            String destino = (ruta != null && !ruta.destino.isEmpty()) ? ruta.destino : null;
            String origen = (ruta != null && !ruta.origen.isEmpty()) ? ruta.origen : null;

            Modelos.Ficha ficha = Modelos.paraEconomico(numero);
            // Igual que RealtimeRepository.parsear(): el feed manda la velocidad en km/h.
            float velMs = c.speed / 3.6f;

            lista.add(new UnidadReal(numero, linea, destino, origen, c.routeId, ficha.empresa,
                    ficha.marca, ficha.modelo, c.placa != null ? c.placa : "",
                    c.lat, c.lon, c.bearing, velMs, c.timestamp));
        }
        return lista;
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
