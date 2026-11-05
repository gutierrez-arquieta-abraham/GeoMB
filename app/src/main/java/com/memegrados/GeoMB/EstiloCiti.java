package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Piezas visuales del "estilo CITI": el mapa con el aspecto de la app oficial del Metrobús que
 * hizo Indra (2018). Ver docs/MODO_CITI_referencia.md.
 */
// ============================================================
// CLASE    : EstiloCiti
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Reproduce cómo la app de Indra (FragmentMapa) dibujaba el mapa:
//   - Trazo de la ruta de 3dp, en los colores de línea que usaba esa app.
//   - Estación: pictograma de 20dp centrado sobre la flecha de rumbo
//     (citi_rumbo, 56dp) girada hacia el siguiente punto del recorrido.
//     Con ida y vuelta por el mismo lugar salen 2 flechas opuestas (↕),
//     igual que los 2 andenes que se veían en la app original.
//   - Autobús: el ícono citi_bus a 32dp, sin número ni color de línea.
//
// citi_rumbo / citi_bus / citi_cerrar son las imágenes originales de la
// app (res/drawable, densidad base, como en la app de Indra).
// ============================================================
public final class EstiloCiti {

    private EstiloCiti() {}

    /** Distancia máxima (m) entre la estación y el trazo para tomar su rumbo. */
    private static final double RADIO_RUMBO_M = 60;

    /** Colores de línea que pintaba la app de Indra (medidos de sus capturas). La L7 llegó en su
     *  última versión y no hay captura: se usa el color oficial del GTFS. */
    private static final Map<Integer, Integer> COLORES = new HashMap<>();
    static {
        COLORES.put(1, 0xFF983223);
        COLORES.put(2, 0xFF7C109A);
        COLORES.put(3, 0xFF739500);
        COLORES.put(4, 0xFFF8901F);
        COLORES.put(5, 0xFF223C77);
        COLORES.put(6, 0xFFE73F97);
    }

    private static final Map<String, BitmapDescriptor> CACHE = new HashMap<>();
    private static BitmapDescriptor bus;

    /** Color de la línea en estilo CITI; {@code porDefecto} si esa app no la tenía. */
    public static int color(int linea, int porDefecto) {
        Integer c = COLORES.get(linea);
        return c != null ? c : porDefecto;
    }

    /** "Indios Verdes – El Caminero" → "Indios Verdes-El Caminero", como se escribía en la app. */
    public static String nombreRuta(String nombre) {
        return nombre == null ? "" : nombre.replace(" – ", "-").replace(" / ", "/");
    }

    /** Ancho del trazo en px (map_iti_width = 3dp). */
    public static float anchoTrazo(Context c) {
        return c.getResources().getDimension(R.dimen.citi_trazo);
    }

    /** Ícono del autobús (icon_bus a map_bus_width = 32dp). */
    public static BitmapDescriptor iconoBus(Context c) {
        if (bus == null) {
            int px = (int) c.getResources().getDimension(R.dimen.citi_bus);
            Bitmap b = BitmapFactory.decodeResource(c.getResources(), R.drawable.citi_bus);
            bus = BitmapDescriptorFactory.fromBitmap(Bitmap.createScaledBitmap(b, px, px, false));
        }
        return bus;
    }

    /** Rumbos (grados, 0 = norte) del recorrido al pasar por {@code pos}, uno por sentido: el de
     *  cada tramo de ida que pasa a menos de {@link #RADIO_RUMBO_M} y el de su vuelta (con la
     *  geometría propia de la vuelta si la línea la trae, o el rumbo contrario si ambos sentidos van
     *  por la misma calle). Así una estación de doble sentido lleva ↕, como los 2 andenes del original. */
    public static List<Float> rumbos(Linea l, LatLng pos) {
        List<Float> res = new ArrayList<>();
        List<List<LatLng>> ida = new ArrayList<>();
        if (l.segmentos != null && !l.segmentos.isEmpty()) ida.addAll(l.segmentos);
        else if (l.ruta != null) ida.add(l.ruta);
        for (int i = 0; i < ida.size(); i++) {
            Float r = rumboCerca(ida.get(i), pos);
            if (r == null) continue;
            agregarRumbo(res, r);
            List<LatLng> vuelta = l.segmentosVuelta != null ? l.segmentosVuelta.get(i) : null;
            Float rv = vuelta != null ? rumboCerca(vuelta, pos) : null;
            if (vuelta == null) agregarRumbo(res, (r + 180f) % 360f);   // mismo trazo en ambos sentidos
            else if (rv != null) agregarRumbo(res, rv);                  // la vuelta va por otra calle
        }
        if (l.rutaVuelta != null) {   // sentido contrario por otra calle (toda la línea)
            Float rv = rumboCerca(l.rutaVuelta, pos);
            if (rv != null) agregarRumbo(res, rv);
        }
        return res;
    }

    /** Rumbo del tramo {@code t} en su punto más cercano a {@code pos}; null si pasa lejos. */
    private static Float rumboCerca(List<LatLng> t, LatLng pos) {
        if (t == null || t.size() < 2) return null;
        int mejor = -1;
        double dMin = Double.MAX_VALUE;
        for (int i = 0; i < t.size(); i++) {
            double d = Linea.distancia(t.get(i), pos);
            if (d < dMin) { dMin = d; mejor = i; }
        }
        if (mejor < 0 || dMin > RADIO_RUMBO_M) return null;
        LatLng a = mejor < t.size() - 1 ? t.get(mejor) : t.get(mejor - 1);
        LatLng b = mejor < t.size() - 1 ? t.get(mejor + 1) : t.get(mejor);
        return rumbo(a, b);
    }

    /** Agrega {@code r} si no hay ya un rumbo casi igual (menos de 20°). */
    private static void agregarRumbo(List<Float> res, float r) {
        for (float x : res) if (Math.abs(((r - x + 540) % 360) - 180) < 20) return;
        res.add(r);
    }

    /** Estación: la flecha de rumbo girada por cada sentido y el pictograma encima (como
     *  creaParadaLinea() de la app de Indra). Sin pictograma, un disco del color de la línea. */
    public static BitmapDescriptor iconoEstacion(Context c, String icono, int colorLinea, List<Float> rumbos,
                                                 boolean fueraDeServicio) {
        StringBuilder k = new StringBuilder(icono == null ? "" : icono).append('|').append(colorLinea)
                .append('|').append(fueraDeServicio);
        for (float r : rumbos) k.append('|').append(Math.round(r / 5f) * 5);
        BitmapDescriptor cacheado = CACHE.get(k.toString());
        if (cacheado != null) return cacheado;

        int px = (int) c.getResources().getDimension(R.dimen.citi_estacion);
        // Sin flechas el lienzo es solo el del pictograma: el estilo CITI dibuja las ~280 estaciones
        // a la vez y con el lienzo de la flecha (56dp) serían decenas de MB de bitmaps.
        Bitmap flecha = rumbos.isEmpty() ? null : BitmapFactory.decodeResource(c.getResources(), R.drawable.citi_rumbo);
        int w = flecha != null ? Math.max(flecha.getWidth(), px) : px;
        int h = flecha != null ? Math.max(flecha.getHeight(), px) : px;
        if (CACHE.size() > 400) CACHE.clear();   // al cambiar mucho de ruta no crece sin límite
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint suave = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (float r : rumbos) {
            Matrix m = new Matrix();
            m.setTranslate((w - flecha.getWidth()) / 2f, (h - flecha.getHeight()) / 2f);
            m.postRotate(r, w / 2f, h / 2f);
            canvas.drawBitmap(flecha, m, suave);
        }
        Bitmap picto = Iconos.pictograma(c, icono, px);
        float x = (w - px) / 2f, y = (h - px) / 2f;
        if (picto != null) {
            Paint p = null;
            if (fueraDeServicio) {
                p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(0f);
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            }
            canvas.drawBitmap(Bitmap.createScaledBitmap(picto, px, px, true), x, y, p);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            canvas.drawCircle(w / 2f, h / 2f, px * 0.45f, p);
            p.setColor(fueraDeServicio ? 0xFF9E9E9E : colorLinea);
            canvas.drawCircle(w / 2f, h / 2f, px * 0.35f, p);
        }
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(out);
        CACHE.put(k.toString(), bd);
        return bd;
    }

    // --- Estaciones favoritas ("Añadir a favoritos" del menú de estación). GeoMB solo guardaba
    // unidades, así que se guardan aparte como "línea|nombre" en SharedPreferences. ---

    private static final String PREFS_FAV = "estilo_citi";
    private static final String K_FAVORITAS = "estaciones_favoritas";

    /** Una favorita es un andén (el original guardaba parada.cod): línea, nombre y posición. */
    private static String claveFavorita(int linea, String nombre, LatLng pos) {
        return linea + "|" + nombre + "|" + String.format(java.util.Locale.US, "%.5f,%.5f", pos.latitude, pos.longitude);
    }

    public static boolean esFavorita(Context c, int linea, String nombre, LatLng pos) {
        return favoritas(c).contains(claveFavorita(linea, nombre, pos));
    }

    public static void agregarFavorita(Context c, int linea, String nombre, LatLng pos) {
        java.util.Set<String> s = new java.util.HashSet<>(favoritas(c));
        s.add(claveFavorita(linea, nombre, pos));
        c.getApplicationContext().getSharedPreferences(PREFS_FAV, Context.MODE_PRIVATE)
                .edit().putStringSet(K_FAVORITAS, s).apply();
    }

    private static java.util.Set<String> favoritas(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_FAV, Context.MODE_PRIVATE)
                .getStringSet(K_FAVORITAS, java.util.Collections.emptySet());
    }

    /** Rumbo inicial de a→b en grados (0 = norte, sentido horario), como Pos.rumboA() de Indra. */
    static float rumbo(LatLng a, LatLng b) {
        double f1 = Math.toRadians(a.latitude), f2 = Math.toRadians(b.latitude);
        double dl = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dl) * Math.cos(f2);
        double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
    }
}
