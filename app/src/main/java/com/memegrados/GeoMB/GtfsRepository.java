package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;

import com.google.android.gms.maps.model.LatLng;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Carga las redes (Metrobús desde assets/lineas.json, Mexibús desde mexibus.json) generadas del GTFS.
 *
 * <p>Rendimiento:
 * <ul>
 *   <li><b>Sin bloqueo de lectores.</b> Cada red se construye como lista INMUTABLE en 2º plano y se
 *       publica de golpe en una referencia {@code volatile}; los lectores solo leen esa referencia,
 *       así el hilo principal nunca se queda esperando un {@code synchronized} de 6 s.</li>
 *   <li><b>Sin memory churn.</b> El parseo es en STREAMING ({@link JsonReader}): no se carga el JSON
 *       completo a un {@code String}/árbol en memoria, sino que se instancian los objetos uno por uno.</li>
 * </ul>
 */
public final class GtfsRepository {

    private GtfsRepository() {}

    // Referencias "en la sombra": null hasta que la carga en 2º plano publica la lista inmutable.
    private static volatile List<Linea> lineas;             // Metrobús
    private static volatile List<Linea> mexibus;            // Mexibús
    private static volatile List<Linea> ruteables;          // Metrobús (+ Mexibús si la capa está visible)
    private static volatile boolean ruteablesConMxb;
    private static volatile Map<String, List<LatLng>> sublineas;

    // Un único candado SOLO para la construcción (no para las lecturas ya publicadas).
    private static final Object lock = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gtfs-io"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- getters sin bloqueo

    public static List<Linea> getLineas(Context c) {
        List<Linea> l = lineas;                 // lectura lock-free de la referencia ya publicada
        if (l != null) return l;
        synchronized (lock) {                   // solo la PRIMERA carga entra aquí
            if (lineas == null) lineas = cargarLineas(c.getApplicationContext());
            return lineas;
        }
    }

    public static List<Linea> getMexibus(Context c) {
        List<Linea> m = mexibus;
        if (m != null) return m;
        synchronized (lock) {
            if (mexibus == null) mexibus = cargarArchivo(c.getApplicationContext(), "mexibus.json", true);
            return mexibus;
        }
    }

    /**
     * Líneas ruteables por el planificador: Metrobús, y también Mexibús si el usuario activó
     * "Mostrar Mexibús". Se recalcula si el ajuste cambia; por lo demás devuelve la referencia cacheada.
     */
    public static List<Linea> getRuteables(Context c) {
        boolean mxb = Modos.mostrarMexibus(c);
        List<Linea> r = ruteables;
        if (r != null && ruteablesConMxb == mxb) return r;
        List<Linea> base = getLineas(c);
        if (!mxb) { ruteables = base; ruteablesConMxb = false; return base; }
        List<Linea> t = new ArrayList<>(base);
        t.addAll(getMexibus(c));
        List<Linea> ro = Collections.unmodifiableList(t);
        ruteables = ro; ruteablesConMxb = true;
        return ro;
    }

    public static Linea porNumero(Context c, int numero) {
        for (Linea l : getRuteables(c)) if (l.numero == numero) return l;
        return null;
    }

    public static List<LatLng> sublinea(Context c, String clave) {
        Map<String, List<LatLng>> s = sublineas;
        if (s == null) {
            synchronized (lock) {
                if (sublineas == null) sublineas = cargarSublineas(c.getApplicationContext());
                s = sublineas;
            }
        }
        return s.get(clave);
    }

    /**
     * Precarga TODO (Metrobús, Mexibús, sublíneas) en un hilo de E/S y avisa en el hilo PRINCIPAL
     * cuando ya está en memoria, sin bloquear la UI. La pantalla del mapa la usa antes de dibujar,
     * de modo que dibujarRed() ya encuentre las listas cacheadas (getLineas() es instantáneo).
     */
    public static void precargar(Context c, Runnable onReady) {
        final Context app = c.getApplicationContext();
        if (lineas != null && mexibus != null && sublineas != null) {
            if (onReady != null) MAIN.post(onReady);
            return;
        }
        IO.execute(() -> {
            getLineas(app); getMexibus(app);
            if (sublineas == null) synchronized (lock) { if (sublineas == null) sublineas = cargarSublineas(app); }
            if (onReady != null) MAIN.post(onReady);
        });
    }

    // ---------------------------------------------------------------- parseo en streaming

    private static JsonReader reader(Context ctx, String asset) throws IOException {
        return new JsonReader(new InputStreamReader(ctx.getAssets().open(asset), StandardCharsets.UTF_8));
    }

    /** lineas.json → {"lineas":[{numero,nombre,color,estaciones:[{n,lat,lon}],ruta:[[lat,lon]]}]}. */
    private static List<Linea> cargarLineas(Context ctx) {
        List<Linea> res = new ArrayList<>();
        Map<Integer, List<Estacion>> ofic = leerEstaciones(ctx);   // estaciones oficiales (opcional)
        try (JsonReader jr = reader(ctx, "lineas.json")) {
            jr.beginObject();
            while (jr.hasNext()) {
                if (!"lineas".equals(jr.nextName())) { jr.skipValue(); continue; }
                jr.beginArray();
                while (jr.hasNext()) res.add(parseLinea(jr, ofic, false));
                jr.endArray();
            }
            jr.endObject();
            cargarSegmentos(ctx, res);
        } catch (Exception e) { e.printStackTrace(); }
        return Collections.unmodifiableList(res);
    }

    /** Red básica {"lineas":[{numero,nombre,color,estaciones:[{n,lat,lon,icono}],ruta}]} (p. ej. Mexibús). */
    private static List<Linea> cargarArchivo(Context ctx, String archivo, boolean conIcono) {
        List<Linea> res = new ArrayList<>();
        try (JsonReader jr = reader(ctx, archivo)) {
            jr.beginObject();
            while (jr.hasNext()) {
                if (!"lineas".equals(jr.nextName())) { jr.skipValue(); continue; }
                jr.beginArray();
                while (jr.hasNext()) res.add(parseLinea(jr, null, conIcono));
                jr.endArray();
            }
            jr.endObject();
        } catch (Exception e) { e.printStackTrace(); }
        return Collections.unmodifiableList(res);
    }

    private static Linea parseLinea(JsonReader jr, Map<Integer, List<Estacion>> ofic, boolean conIcono) throws IOException {
        int numero = 0; String nombre = "", color = "";
        List<Estacion> est = new ArrayList<>();
        List<LatLng> ruta = new ArrayList<>();
        jr.beginObject();
        while (jr.hasNext()) {
            switch (jr.nextName()) {
                case "numero":     numero = jr.nextInt(); break;
                case "nombre":     nombre = jr.nextString(); break;
                case "color":      color = jr.nextString(); break;
                case "estaciones": est = parseEstaciones(jr, conIcono); break;
                case "ruta":       ruta = parseRuta(jr); break;
                default:           jr.skipValue();
            }
        }
        jr.endObject();
        if (ofic != null && ofic.containsKey(numero)) est = ofic.get(numero);
        return new Linea(numero, nombre, color, est, ruta);
    }

    private static List<Estacion> parseEstaciones(JsonReader jr, boolean conIcono) throws IOException {
        List<Estacion> l = new ArrayList<>();
        jr.beginArray();
        while (jr.hasNext()) {
            String n = ""; double lat = 0, lon = 0; String icono = "";
            jr.beginObject();
            while (jr.hasNext()) {
                switch (jr.nextName()) {
                    case "n":     n = jr.nextString(); break;
                    case "lat":   lat = jr.nextDouble(); break;
                    case "lon":   lon = jr.nextDouble(); break;
                    case "icono": icono = jr.nextString(); break;
                    default:      jr.skipValue();
                }
            }
            jr.endObject();
            l.add(conIcono ? new Estacion(n, lat, lon, icono) : new Estacion(n, lat, lon));
        }
        jr.endArray();
        return l;
    }

    /** Array de pares [lat,lon] → List<LatLng>. */
    private static List<LatLng> parseRuta(JsonReader jr) throws IOException {
        List<LatLng> l = new ArrayList<>();
        jr.beginArray();
        while (jr.hasNext()) {
            jr.beginArray();
            double lat = jr.nextDouble(), lon = jr.nextDouble();
            while (jr.hasNext()) jr.skipValue();   // tolera columnas extra
            jr.endArray();
            l.add(new LatLng(lat, lon));
        }
        jr.endArray();
        return l;
    }

    /** sublineas.json → [{"clave":"L7-sur","ruta":[[lat,lon],...]}, ...]. */
    private static Map<String, List<LatLng>> cargarSublineas(Context ctx) {
        Map<String, List<LatLng>> m = new HashMap<>();
        try (JsonReader jr = reader(ctx, "sublineas.json")) {
            jr.beginArray();
            while (jr.hasNext()) {
                String clave = null; List<LatLng> ruta = null;
                jr.beginObject();
                while (jr.hasNext()) {
                    String k = jr.nextName();
                    if ("clave".equals(k)) clave = jr.nextString();
                    else if ("ruta".equals(k)) ruta = parseRuta(jr);
                    else jr.skipValue();
                }
                jr.endObject();
                if (clave != null && ruta != null && ruta.size() >= 2) m.put(clave, ruta);
            }
            jr.endArray();
        } catch (Exception e) { /* sin sublineas.json: mixtos por estaciones (recto) */ }
        return m;
    }

    /** estaciones.json (opcional) → {"lineas":[{numero,estaciones:[{n,lat,lon,icono,soloMapa}]}]}. */
    private static Map<Integer, List<Estacion>> leerEstaciones(Context ctx) {
        try (JsonReader jr = reader(ctx, "estaciones.json")) {
            Map<Integer, List<Estacion>> mapa = new HashMap<>();
            jr.beginObject();
            while (jr.hasNext()) {
                if (!"lineas".equals(jr.nextName())) { jr.skipValue(); continue; }
                jr.beginArray();
                while (jr.hasNext()) {
                    int numero = 0; List<Estacion> est = new ArrayList<>();
                    jr.beginObject();
                    while (jr.hasNext()) {
                        String k = jr.nextName();
                        if ("numero".equals(k)) numero = jr.nextInt();
                        else if ("estaciones".equals(k)) {
                            jr.beginArray();
                            while (jr.hasNext()) {
                                String n = ""; double lat = 0, lon = 0; String ic = ""; boolean solo = false;
                                jr.beginObject();
                                while (jr.hasNext()) {
                                    switch (jr.nextName()) {
                                        case "n":        n = jr.nextString(); break;
                                        case "lat":      lat = jr.nextDouble(); break;
                                        case "lon":      lon = jr.nextDouble(); break;
                                        case "icono":    ic = jr.nextString(); break;
                                        case "soloMapa": solo = jr.nextBoolean(); break;
                                        default:         jr.skipValue();
                                    }
                                }
                                jr.endObject();
                                est.add(new Estacion(n, lat, lon, ic, solo));
                            }
                            jr.endArray();
                        } else jr.skipValue();
                    }
                    jr.endObject();
                    mapa.put(numero, est);
                }
                jr.endArray();
            }
            jr.endObject();
            return mapa;
        } catch (Exception e) { return null; }   // sin archivo: se usan las de lineas.json
    }

    /** segmentos.json (opcional) → [{"numero":1,"segmentos":[[[lat,lon],...],...]}, ...]. */
    private static void cargarSegmentos(Context ctx, List<Linea> lineas) {
        try (JsonReader jr = reader(ctx, "segmentos.json")) {
            jr.beginArray();
            while (jr.hasNext()) {
                int numero = 0; List<List<LatLng>> segs = new ArrayList<>();
                jr.beginObject();
                while (jr.hasNext()) {
                    String k = jr.nextName();
                    if ("numero".equals(k)) numero = jr.nextInt();
                    else if ("segmentos".equals(k)) {
                        jr.beginArray();
                        while (jr.hasNext()) {
                            List<LatLng> pts = parseRuta(jr);
                            if (pts.size() >= 2) segs.add(pts);
                        }
                        jr.endArray();
                    } else jr.skipValue();
                }
                jr.endObject();
                if (!segs.isEmpty()) {
                    for (Linea x : lineas) if (x.numero == numero) { x.segmentos = segs; break; }
                }
            }
            jr.endArray();
        } catch (Exception e) { /* sin segmentos.json: trazado simple (linea.ruta) */ }
    }
}
