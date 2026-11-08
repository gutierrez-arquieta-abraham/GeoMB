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
//   - guardada(UnidadReal)   : en patio Y detenida (velocidad ≈ 0); o, en la
//                              zona de corredor de un patio ("excluir"), más de
//                              5 min ahí sin moverse o sin route_id.
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
            return dentroDelArea(p) && !enExcluida(p);
        }

        /** Dentro del círculo y del polígono, sin contar las zonas excluidas. */
        boolean dentroDelArea(LatLng p) {
            if (p == null) return false;
            if (distanciaM(lat, lon, p.latitude, p.longitude) > radioM) return false;
            return poligono == null || dentroDePoligono(poligono, p.latitude, p.longitude);
        }

        /** ¿Cae en una zona excluida (andén / carril del corredor que cruza el patio)? */
        boolean enExcluida(LatLng p) {
            if (p == null) return false;
            for (double[] e : excluir)
                if (distanciaM(e[0], e[1], p.latitude, p.longitude) <= e[2]) return true;
            return false;
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
     *  (saliendo a su primer viaje o entrando al terminar) se considera ya en servicio. Excepción en
     *  los patios pegados al corredor (Constitución de Apatzingán, Júpiter, El Coyol: los que tienen
     *  zonas "excluir"): en esas zonas también cuenta como guardada si lleva más de
     *  {@link #PERMANENCIA_CORREDOR_MS} ahí sin moverse o sin route_id (ver estancadaEnCorredor). */
    public static boolean guardada(UnidadReal u) {
        return guardada(u, System.currentTimeMillis());
    }

    static boolean guardada(UnidadReal u, long ahoraMs) {
        if (u == null || u.posicion == null) return false;
        if (Math.abs(u.velMs) < VEL_DETENIDA_MS && enPatio(u.posicion)) return true;
        return estancadaEnCorredor(u, ahoraMs);
    }

    // ---- excepción del corredor (zonas "excluir" dentro de un patio) ----

    /** Tiempo que una unidad debe quedarse en la zona de corredor de un patio, sin moverse o sin
     *  route_id, para contarse como guardada (una que opera solo se detiene ahí unos segundos). */
    static final long PERMANENCIA_CORREDOR_MS = 5 * 60_000L;
    /** "Se queda ahí": no se aleja más de esto del punto donde empezó a contar. */
    static final double RADIO_PERMANENCIA_M = 40.0;
    private static final long OLVIDAR_MS = 30 * 60_000L;

    /** Desde cuándo y dónde está cada unidad estancada en una zona de corredor: {desdeMs, lat, lon, vistoMs}. */
    private static final java.util.Map<String, double[]> ESTANCADAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** ¿Lleva {@link #PERMANENCIA_CORREDOR_MS} o más en la zona de corredor de un patio, detenida o sin
     *  route_id, sin alejarse de donde empezó? El conteo se reinicia si avanza con ruta o sale de la zona. */
    static boolean estancadaEnCorredor(UnidadReal u, long ahoraMs) {
        if (u.numero == null) return false;
        boolean enCorredor = false;
        for (Patio pa : todos())
            if (pa.excluir.length > 0 && pa.dentroDelArea(u.posicion) && pa.enExcluida(u.posicion)) { enCorredor = true; break; }
        boolean sinServicio = Math.abs(u.velMs) < VEL_DETENIDA_MS || u.ruta == null || u.ruta.trim().isEmpty();
        if (!enCorredor || !sinServicio) {
            ESTANCADAS.remove(u.numero);
            return false;
        }
        double[] e = ESTANCADAS.get(u.numero);
        if (e == null || distanciaM(e[1], e[2], u.posicion.latitude, u.posicion.longitude) > RADIO_PERMANENCIA_M) {
            if (ESTANCADAS.size() > 2000) olvidarViejas(ahoraMs);
            ESTANCADAS.put(u.numero, new double[]{ahoraMs, u.posicion.latitude, u.posicion.longitude, ahoraMs});
            return false;
        }
        e[3] = ahoraMs;
        return ahoraMs - (long) e[0] >= PERMANENCIA_CORREDOR_MS;
    }

    private static void olvidarViejas(long ahoraMs) {
        ESTANCADAS.values().removeIf(e -> ahoraMs - (long) e[3] > OLVIDAR_MS);
    }

    /** Solo pruebas: borra el conteo de permanencia. */
    static void reiniciarPermanencia() {
        ESTANCADAS.clear();
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
