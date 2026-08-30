package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Carga las líneas del Metrobús desde assets/lineas.json (generado del GTFS). */
public final class GtfsRepository {

    private static List<Linea> lineas;

    private GtfsRepository() {}

    public static synchronized List<Linea> getLineas(Context context) {
        if (lineas == null) {
            lineas = cargar(context.getApplicationContext());
        }
        return lineas;
    }

    private static List<Linea> mexibus;
    private static List<Linea> ruteables;

    /** Líneas del Mexibús (servicio ordinario) desde assets/mexibus.json. Numeración 101, 102, … */
    public static synchronized List<Linea> getMexibus(Context context) {
        if (mexibus == null) mexibus = cargarArchivo(context.getApplicationContext(), "mexibus.json");
        return mexibus;
    }

    /**
     * Líneas ruteables por el planificador. Incluye el Mexibús SOLO si el usuario activó
     * "Mostrar Mexibús" (Acerca de); si no, solo Metrobús. Así el planificador ignora esas
     * estaciones cuando la capa está apagada.
     */
    public static synchronized List<Linea> getRuteables(Context context) {
        if (!Modos.mostrarMexibus(context)) return getLineas(context);
        if (ruteables == null) {
            List<Linea> t = new ArrayList<>(getLineas(context));
            t.addAll(getMexibus(context));
            ruteables = Collections.unmodifiableList(t);
        }
        return ruteables;
    }

    public static Linea porNumero(Context context, int numero) {
        for (Linea l : getRuteables(context)) {
            if (l.numero == numero) return l;
        }
        return null;
    }

    /** Sublíneas por sentido (couplet L7): trazado real para que el planificador ancle estaciones. */
    private static Map<String, List<LatLng>> sublineas;

    public static synchronized List<LatLng> sublinea(Context context, String clave) {
        if (sublineas == null) cargarSublineas(context.getApplicationContext());
        return sublineas.get(clave);
    }

    /** Carga assets/sublineas.json: [{"clave":"L7-sur","ruta":[[lat,lon],...]}, ...]. */
    private static void cargarSublineas(Context context) {
        sublineas = new HashMap<>();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("sublineas.json"), StandardCharsets.UTF_8));
            String l;
            while ((l = reader.readLine()) != null) sb.append(l);
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String clave = o.getString("clave");
                JSONArray pts = o.getJSONArray("ruta");
                List<LatLng> ruta = new ArrayList<>();
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray p = pts.getJSONArray(j);
                    ruta.add(new LatLng(p.getDouble(0), p.getDouble(1)));
                }
                if (ruta.size() >= 2) sublineas.put(clave, ruta);
            }
        } catch (Exception e) {
            // Sin sublineas.json el planificador dibuja el mixto por las estaciones (recto).
        }
    }

    private static List<Linea> cargar(Context context) {
        List<Linea> resultado = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("lineas.json"), StandardCharsets.UTF_8));
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea);
            reader.close();

            // Estaciones oficiales del KML (opcional): si existe, reemplaza las de lineas.json.
            Map<Integer, List<Estacion>> estOficiales = leerEstaciones(context);

            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("lineas");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int numero = o.getInt("numero");

                List<Estacion> estaciones = new ArrayList<>();
                JSONArray ests = o.getJSONArray("estaciones");
                for (int j = 0; j < ests.length(); j++) {
                    JSONObject e = ests.getJSONObject(j);
                    estaciones.add(new Estacion(e.getString("n"),
                            e.getDouble("lat"), e.getDouble("lon")));
                }
                if (estOficiales != null && estOficiales.containsKey(numero)) {
                    estaciones = estOficiales.get(numero);
                }

                List<LatLng> ruta = new ArrayList<>();
                JSONArray pts = o.getJSONArray("ruta");
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray p = pts.getJSONArray(j);
                    ruta.add(new LatLng(p.getDouble(0), p.getDouble(1)));
                }

                resultado.add(new Linea(numero, o.getString("nombre"),
                        o.getString("color"), estaciones, ruta));
            }

            cargarSegmentos(context, resultado);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.unmodifiableList(resultado);
    }

    /**
     * Carga un archivo de líneas con el formato básico {"lineas":[{numero,nombre,color,
     * estaciones:[{n,lat,lon}], ruta:[[lat,lon]]}]} (sin estaciones oficiales ni segmentos).
     * Se usa para redes adicionales como el Mexibús.
     */
    private static List<Linea> cargarArchivo(Context context, String archivo) {
        List<Linea> resultado = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(archivo), StandardCharsets.UTF_8));
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea);
            reader.close();

            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("lineas");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<Estacion> estaciones = new ArrayList<>();
                JSONArray ests = o.getJSONArray("estaciones");
                for (int j = 0; j < ests.length(); j++) {
                    JSONObject e = ests.getJSONObject(j);
                    estaciones.add(new Estacion(e.getString("n"), e.getDouble("lat"), e.getDouble("lon"),
                            e.optString("icono", "")));   // pictograma opcional (drawable) por estación
                }
                List<LatLng> ruta = new ArrayList<>();
                JSONArray pts = o.getJSONArray("ruta");
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray p = pts.getJSONArray(j);
                    ruta.add(new LatLng(p.getDouble(0), p.getDouble(1)));
                }
                resultado.add(new Linea(o.getInt("numero"), o.getString("nombre"),
                        o.getString("color"), estaciones, ruta));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.unmodifiableList(resultado);
    }

    /**
     * Estaciones oficiales por número de línea desde assets/estaciones.json
     * (opcional). Formato: {"lineas":[{"numero":n,"estaciones":[{"n","lat","lon"}]}]}.
     * Si el archivo no existe, devuelve null y se usan las de lineas.json.
     */
    private static Map<Integer, List<Estacion>> leerEstaciones(Context context) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("estaciones.json"), StandardCharsets.UTF_8));
            String l;
            while ((l = reader.readLine()) != null) sb.append(l);
            reader.close();

            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("lineas");
            Map<Integer, List<Estacion>> mapa = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<Estacion> est = new ArrayList<>();
                JSONArray a = o.getJSONArray("estaciones");
                for (int j = 0; j < a.length(); j++) {
                    JSONObject e = a.getJSONObject(j);
                    est.add(new Estacion(e.getString("n"), e.getDouble("lat"), e.getDouble("lon"),
                            e.optString("icono", ""), e.optBoolean("soloMapa", false)));
                }
                mapa.put(o.getInt("numero"), est);
            }
            return mapa;
        } catch (Exception e) {
            return null;   // sin archivo: se usan las estaciones de lineas.json
        }
    }

    /**
     * Carga el trazado oficial por tramos desde assets/segmentos.json (opcional)
     * y lo asigna a cada línea por número. Formato:
     * [{"numero":1,"segmentos":[[[lat,lon],...],[[lat,lon],...]]}, ...]
     */
    private static void cargarSegmentos(Context context, List<Linea> lineas) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("segmentos.json"), StandardCharsets.UTF_8));
            String l;
            while ((l = reader.readLine()) != null) sb.append(l);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int numero = o.getInt("numero");
                Linea destino = null;
                for (Linea x : lineas) {
                    if (x.numero == numero) { destino = x; break; }
                }
                if (destino == null) continue;

                JSONArray segs = o.getJSONArray("segmentos");
                List<List<LatLng>> segmentos = new ArrayList<>();
                for (int s = 0; s < segs.length(); s++) {
                    JSONArray seg = segs.getJSONArray(s);
                    List<LatLng> puntos = new ArrayList<>();
                    for (int p = 0; p < seg.length(); p++) {
                        JSONArray pt = seg.getJSONArray(p);
                        puntos.add(new LatLng(pt.getDouble(0), pt.getDouble(1)));
                    }
                    if (puntos.size() >= 2) segmentos.add(puntos);
                }
                if (!segmentos.isEmpty()) destino.segmentos = segmentos;
            }
        } catch (Exception e) {
            // Sin segmentos.json se usa el trazado simple (linea.ruta).
            e.printStackTrace();
        }
    }
}
