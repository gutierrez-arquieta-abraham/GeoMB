package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ============================================================
// CLASE    : Patios
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Catálogo de PATIOS / ENCIERROS donde se guardan las unidades del
// Metrobús (assets/patios.json). Se ubicaron con una captura del feed
// GTFS-RT de Sonda de madrugada (2026-11-07, 2:00–4:00 CDMX), cuando
// casi toda la flota está guardada: grupos de ≥5 unidades detenidas a
// ≤150 m entre sí, fuera del andén de las terminales.
//
// SIRVE PARA: que el mapa (y las listas/conteos que usan las mismas
// unidades) muestre a los usuarios normales SOLO las unidades en
// servicio, y deje ver las guardadas en patio a quien tenga la función
// "unidades en patio" ({@link Modos#verUnidadesPatio}).
//
// FORMATO de patios.json:
//   { "patios": [ { "id", "nombre", "lineas": [..],
//                   "lat", "lon", "radio_m",          (círculo envolvente)
//                   "poligono": [[lat,lon], ...],      (opcional; si viene,
//                                                       manda sobre el círculo)
//                   "excluir": [[lat,lon,radio_m], ...] } ] }  (opcional)
// "excluir" son círculos que NUNCA cuentan como patio aunque caigan
// dentro: el andén de una terminal pegada al encierro o el carril
// confinado que cruza/bordea el patio -- ahí una unidad detenida está
// OPERANDO (subiendo pasaje, en un alto), no guardada.
//
// MÉTODOS CLAVE:
//   - enPatio(LatLng)        : ¿el punto cae dentro de algún patio?
//   - patioEn(LatLng)        : el patio que lo contiene (o null).
//   - guardada(UnidadReal)   : en patio Y detenida (velocidad ≈ 0).
//   - ocultar(ctx, u)        : guardada(u) y el usuario NO tiene la función.
//   - cargarDesdeJson(json)  : carga pura (sin Context) -- la usan las pruebas.
//
// Una unidad DETENIDA en un patio está guardada; una que va saliendo o
// entrando (velocidad > umbral) se sigue mostrando: ya está operando.
// Clase de UTILIDAD (final + static + caché volatile).
// ============================================================
/** Patios/encierros de unidades (assets/patios.json) y el filtro "solo unidades en servicio". */
public final class Patios {

    /** Velocidad (m/s) por debajo de la cual una unidad cuenta como detenida (~2 km/h: el GPS
     *  de una unidad estacionada reporta 0 o un ruido de 1-2 km/h). */
    static final float VEL_DETENIDA_MS = 0.6f;

    private static final double M_POR_GRADO_LAT = 110540.0;
    private static final double M_POR_GRADO_LON_ECUADOR = 111320.0;

    /** Un patio: círculo envolvente (descarte rápido) + polígono opcional (forma real). */
    public static final class Patio {
        public final String id, nombre;
        public final int[] lineas;
        public final double lat, lon, radioM;
        /** Vértices [lat, lon] (cerrado implícitamente) o null para usar solo el círculo. */
        final double[][] poligono;
        /** Círculos [lat, lon, radio_m] excluidos (andenes / carril confinado), o vacío. */
        final double[][] excluir;

        Patio(String id, String nombre, int[] lineas, double lat, double lon, double radioM,
              double[][] poligono, double[][] excluir) {
            this.id = id;
            this.nombre = nombre;
            this.lineas = lineas;
            this.lat = lat;
            this.lon = lon;
            this.radioM = radioM;
            this.poligono = poligono;
            this.excluir = excluir != null ? excluir : new double[0][];
        }

        public boolean contiene(LatLng p) {
            if (p == null) return false;
            if (distanciaM(lat, lon, p.latitude, p.longitude) > radioM) return false;
            if (poligono != null && !dentroDePoligono(poligono, p.latitude, p.longitude)) return false;
            for (double[] e : excluir)
                if (distanciaM(e[0], e[1], p.latitude, p.longitude) <= e[2]) return false;
            return true;
        }
    }

    private static volatile List<Patio> patios = null;

    private Patios() {}

    /** Carga el catálogo desde assets la primera vez (barato: pocos patios). */
    static void cargar(Context c) {
        if (patios != null || c == null) return;
        synchronized (Patios.class) {
            if (patios != null) return;
            try (InputStream in = c.getApplicationContext().getAssets().open("patios.json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                patios = parsear(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Sin catálogo legible no se oculta nada: mejor mostrar de más que de menos.
                patios = Collections.emptyList();
            }
        }
    }

    /** Carga pura (sin Context), para pruebas o para un catálogo descargado. */
    static void cargarDesdeJson(String json) {
        try {
            patios = parsear(json);
        } catch (Exception e) {
            patios = Collections.emptyList();
        }
    }

    static List<Patio> parsear(String json) throws org.json.JSONException {
        JSONArray arr = new JSONObject(json).getJSONArray("patios");
        List<Patio> res = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            JSONArray ls = o.optJSONArray("lineas");
            int[] lineas = new int[ls != null ? ls.length() : 0];
            for (int k = 0; k < lineas.length; k++) lineas[k] = ls.getInt(k);
            double[][] pol = null;
            JSONArray pj = o.optJSONArray("poligono");
            if (pj != null && pj.length() >= 3) {
                pol = new double[pj.length()][];
                for (int k = 0; k < pj.length(); k++) {
                    JSONArray v = pj.getJSONArray(k);
                    pol[k] = new double[]{v.getDouble(0), v.getDouble(1)};
                }
            }
            double[][] exc = null;
            JSONArray ej = o.optJSONArray("excluir");
            if (ej != null) {
                exc = new double[ej.length()][];
                for (int k = 0; k < ej.length(); k++) {
                    JSONArray v = ej.getJSONArray(k);
                    exc[k] = new double[]{v.getDouble(0), v.getDouble(1), v.getDouble(2)};
                }
            }
            res.add(new Patio(o.optString("id", "patio" + i), o.optString("nombre", ""), lineas,
                    o.getDouble("lat"), o.getDouble("lon"), o.getDouble("radio_m"), pol, exc));
        }
        return res;
    }

    /** Patios cargados (vacío si aún no se cargan). */
    public static List<Patio> todos() {
        List<Patio> l = patios;
        return l != null ? l : Collections.<Patio>emptyList();
    }

    /** El patio que contiene el punto, o null. */
    public static Patio patioEn(LatLng p) {
        for (Patio pa : todos()) if (pa.contiene(p)) return pa;
        return null;
    }

    /** ¿El punto cae dentro de algún patio/encierro? */
    public static boolean enPatio(LatLng p) {
        return patioEn(p) != null;
    }

    /** ¿La unidad está GUARDADA? = dentro de un patio y detenida. Una que se mueve dentro del patio
     *  (saliendo a su primer viaje o entrando al terminar) se considera ya en servicio. */
    public static boolean guardada(UnidadReal u) {
        return u != null && Math.abs(u.velMs) < VEL_DETENIDA_MS && enPatio(u.posicion);
    }

    /** ¿Se oculta esta unidad a ESTE usuario? Solo si está guardada y no tiene la función
     *  "unidades en patio" ({@link Modos#verUnidadesPatio}, donde se conectará la suscripción). */
    public static boolean ocultar(Context c, UnidadReal u) {
        if (c == null || Modos.verUnidadesPatio(c)) return false;
        cargar(c);
        return guardada(u);
    }

    // ---- geometría (pura) ----

    static double distanciaM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Ray casting sobre una proyección local (equirectangular): a la escala de un patio (cientos
     *  de metros) el error es despreciable. */
    static boolean dentroDePoligono(double[][] pol, double lat, double lon) {
        double k = Math.cos(Math.toRadians(lat)) * M_POR_GRADO_LON_ECUADOR;
        boolean dentro = false;
        for (int i = 0, j = pol.length - 1; i < pol.length; j = i++) {
            double xi = (pol[i][1] - lon) * k, yi = (pol[i][0] - lat) * M_POR_GRADO_LAT;
            double xj = (pol[j][1] - lon) * k, yj = (pol[j][0] - lat) * M_POR_GRADO_LAT;
            if ((yi > 0) != (yj > 0) && 0 < (xj - xi) * (0 - yi) / (yj - yi) + xi) dentro = !dentro;
        }
        return dentro;
    }
}
