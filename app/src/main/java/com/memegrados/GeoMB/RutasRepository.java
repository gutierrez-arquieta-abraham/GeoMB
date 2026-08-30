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
public final class RutasRepository {

    private static volatile List<Ruta> rutas = new ArrayList<>();
    private static volatile Map<String, Ruta> porId = new HashMap<>();
    private static volatile boolean cargado = false;

    private RutasRepository() {}

    /** Descarga y cachea el catálogo (una vez, en segundo plano). */
    public static void init() {
        if (cargado) return;
        new Thread(() -> {
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
        }, "rutas-fetch").start();
    }

    public static boolean estaCargado() {
        return cargado;
    }

    /** Ruta por route_id (del feed en vivo), o null. */
    public static Ruta porRouteId(String routeId) {
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
        List<Ruta> lista = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Backend.descargar(Config.PATH_ROUTES));   // con failover
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Integer linea = null;
                String ls = o.isNull("line") ? null : o.optString("line", null);
                if (ls != null && !ls.isEmpty()) {
                    try { linea = Integer.parseInt(ls.trim()); } catch (NumberFormatException ignore) {}
                }
                if (linea == null) continue;
                lista.add(new Ruta(
                        o.optString("route_id", ""),
                        linea,
                        o.isNull("origen") ? "" : o.optString("origen", ""),
                        o.isNull("destino") ? "" : o.optString("destino", ""),
                        o.optString("color", "#D40D0D")));
            }
        } catch (Exception e) {
            e.printStackTrace();
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
