package com.memegrados.GeoMB;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga el catálogo de rutas del backend (/data/routes.json) y les asigna un
 * "código" por línea. Cachea en memoria; se refresca en segundo plano.
 */
// ============================================================
// CLASE    : RutasRepository
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Carga y cachea el CATÁLOGO de rutas del backend (/data/routes.json) y a
// cada ruta le asigna un "código" por línea (ida y vuelta del mismo
// recorrido comparten código). Se refresca en segundo plano una vez.
//
// SIRVE PARA: resolver el nombre "Origen → Destino" de un route_id (p. ej.
// en el filtro por ruta del mapa) y listar las rutas de una línea.
//
// NOTA: conserva rutas SIN línea (linea=0) para poder resolver su recorrido
// aunque no entren en las consultas por línea (deLinea usa 1..7).
//
// MÉTODOS CLAVE: init() (descarga en 2º plano), porRouteId(id), deLinea(n),
// recorridosDeLinea(n). Clase de UTILIDAD (final + static + caché volatile).
// ============================================================
public final class RutasRepository {

    private static volatile List<Ruta> rutas = new ArrayList<>();
    private static volatile Map<String, Ruta> porId = new HashMap<>();
    private static volatile boolean cargado = false;

    private RutasRepository() {}

    /** Descarga y cachea el catálogo (una vez, en segundo plano). */
    public static void init() {
        if (cargado) return;
        new Thread(() -> {
            try {
                List<Ruta> lista = descargar();
                lista.addAll(RutasMixtas.comoRutas());   // rutas mixtas (no vienen del backend)
                if (!lista.isEmpty()) {
                    asignarCodigos(lista);
                    Map<String, Ruta> mapa = new HashMap<>();
                    for (Ruta r : lista) mapa.put(r.routeId, r);
                    rutas = lista;
                    porId = mapa;
                    cargado = true;
                }
            } catch (RuntimeException t) {
                // descargar() ya atajaba sus propios errores de red; esto cubre asignarCodigos()
                // y RutasMixtas.comoRutas(), que no lo hacían -- una falla aquí no debe tumbar la
                // app, solo dejar el catálogo de rutas sin cargar (se reintenta en el próximo init()).
                GeoMBApplication app = GeoMBApplication.get();
                if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "RutasRepository.init", String.valueOf(t));
            }
        }, "rutas-fetch").start();
    }

    public static boolean estaCargado() {
        return cargado;
    }

    /** Ruta por route_id (del feed en vivo), o null. */
    public static Ruta porRouteId(String routeId) {
        return routeId != null ? porId.get(routeId) : null;
    }

    /** Ruta por trip_id (respaldo cuando el feed en vivo no manda route_id -- ver
     *  {@link RutasEstaticoDirecto#rutaIdDeTripId} y {@link UnidadRtWire#aUnidades}), o null. */
    public static Ruta porTripId(String tripId) {
        String routeId = RutasEstaticoDirecto.rutaIdDeTripId(tripId);
        return routeId != null ? porId.get(routeId) : null;
    }

    /** Rutas de una línea, ordenadas por código. */
    public static List<Ruta> deLinea(int linea) {
        List<Ruta> res = new ArrayList<>();
        for (Ruta r : rutas) if (r.linea == linea) res.add(r);
        Collections.sort(res, Comparator.comparingInt((Ruta r) -> r.codigo)
                .thenComparing(r -> r.destino));
        return res;
    }

    /** Un recorrido representativo por código (para elegir sentido/destino). */
    public static List<Ruta> recorridosDeLinea(int linea) {
        List<Ruta> res = new ArrayList<>();
        Map<String, Boolean> vistos = new HashMap<>();
        for (Ruta r : deLinea(linea)) {
            if (vistos.put(r.destino, Boolean.TRUE) == null) res.add(r);
        }
        return res;
    }

    // ---- carga ----

    private static List<Ruta> descargar() {
        try {
            // Backend (EC2, ver Backend) -- es la más barata en red si está arriba, así que se
            // intenta primero.
            return parsearRutas(Backend.descargar(Config.PATH_ROUTES));
        } catch (Exception e) {
            try {
                // El EC2 no respondió: el catálogo DE VERDAD en vivo, directo del
                // GTFS estático de Sonda (ver RutasEstaticoDirecto) -- se regenera cada medianoche,
                // así que a diferencia del respaldo de abajo sí refleja cambios de ruta del día.
                return RutasEstaticoDirecto.obtener();
            } catch (Exception e2) {
                // Se registra EL MOTIVO exacto (sin credenciales, pausa, HTTP de Sonda, zip sin
                // routes.txt, etc.) -- a diferencia de los pólleos de unidades, init() corre UNA
                // sola vez por sesión de la app, así que este log no se repite ni ensucia el
                // reporte técnico. Sin esto, una falla silenciosa de este nivel era indistinguible
                // de que ni siquiera se hubiera intentado.
                Telemetria.registrarError(GeoMBApplication.get(), Telemetria.ERR_EXCEPCION,
                        "RutasRepository.descargar:sonda_directo", String.valueOf(e2));
                // Ni el backend ni Sonda directo respondieron (o no hay credenciales locales):
                // cae al mismo archivo YA EMPAQUETADO en la app (assets/routes.json, copia de
                // metrobus_app/data/routes.json) -- igual que lineas.json/estaciones.json/
                // mexibus.json, datos de referencia que casi no cambian, así que una copia local
                // un poco vieja sigue siendo útil como último recurso. Sin esto, CUALQUIER caída
                // simultánea del backend Y de Sonda dejaba sin línea/origen/destino a TODAS las
                // unidades del mapa, no solo las que de verdad tienen un route_id fuera de catálogo.
                try {
                    GeoMBApplication app = GeoMBApplication.get();
                    if (app == null) return new ArrayList<>();
                    String json = leerAsset(app, "routes.json");
                    return parsearRutas(json);
                } catch (Exception e3) {
                    Telemetria.registrarError(GeoMBApplication.get(), Telemetria.ERR_EXCEPCION,
                            "RutasRepository.descargar:asset", String.valueOf(e3));
                    return new ArrayList<>();
                }
            }
        }
    }

    private static String leerAsset(android.content.Context ctx, String nombre) throws java.io.IOException {
        try (java.io.InputStream in = ctx.getAssets().open(nombre);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    static List<Ruta> parsearRutas(String json) throws org.json.JSONException {
        List<Ruta> lista = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Integer linea = null;
            String ls = o.isNull("line") ? null : o.optString("line", null);
            if (ls != null && !ls.isEmpty()) {
                try { linea = Integer.parseInt(ls.trim()); } catch (NumberFormatException ignore) {}
            }
            String origen = o.isNull("origen") ? "" : o.optString("origen", "");
            String destino = o.isNull("destino") ? "" : o.optString("destino", "");
            // Antes se DESCARTABAN las rutas sin 'line'; ahora se conservan con linea=0 para que su
            // route_id siga resolviendo su recorrido (origen→destino) en el filtro y demás, aunque NO
            // entren en las consultas por línea (deLinea usa 1..7). Solo se omiten las que no traen ni
            // línea ni recorrido (inútiles).
            int ln = linea != null ? linea : 0;
            if (ln == 0 && origen.trim().isEmpty() && destino.trim().isEmpty()) continue;
            lista.add(new Ruta(o.optString("route_id", ""), ln, origen, destino,
                    o.optString("color", "#D40D0D")));
        }
        return lista;
    }

    /** Asigna código por línea: ida y vuelta del mismo recorrido comparten código. */
    private static void asignarCodigos(List<Ruta> lista) {
        // agrupa por línea
        Map<Integer, List<Ruta>> porLinea = new LinkedHashMap<>();
        for (Ruta r : lista) {
            List<Ruta> g = porLinea.get(r.linea);
            if (g == null) { g = new ArrayList<>(); porLinea.put(r.linea, g); }
            g.add(r);
        }
        for (List<Ruta> g : porLinea.values()) {
            Collections.sort(g, Comparator.comparing(r -> r.routeId));
            Map<String, Integer> codigoDe = new LinkedHashMap<>();
            int siguiente = 1;
            for (Ruta r : g) {
                Integer c = codigoDe.get(r.claveRecorrido());
                if (c == null) { c = siguiente++; codigoDe.put(r.claveRecorrido(), c); }
                r.codigo = c;
            }
        }
    }
}
