package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Descarga los parches de ruta TEMPORALES (desvíos/servicios de apoyo -- p. ej. Mexibús cubriendo
 * una estación de Metro cerrada por afectación) que se cargan a mano desde el panel web
 * {@code /admin/trazos} del backend ({@link Config#PATH_RUTAS_TEMP}) y los instala en
 * {@link GtfsRepository} para que Planificador rutee con ellos sin recompilar la app.
 *
 * El backend ya solo manda los parches VIGENTES (filtra por 'expira'), así que cada refresco
 * reemplaza por completo el conjunto anterior -- mismo criterio "el servidor manda la verdad"
 * que {@link AfectacionesMexibus}.
 */
// ============================================================
// CLASE    : RutasTemporales
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Descarga /data/rutas_temporales.json (parches de ruta vigentes, escritos
// a mano desde /admin/trazos) y los instala en GtfsRepository.aplicarParches().
// GtfsRepository.getRuteables() es el ÚNICO punto que los mezcla con la red
// normal -- Planificador no se toca para nada.
//
// Corre en segundo plano (executor) y avisa al terminar en el hilo principal.
// ============================================================
public final class RutasTemporales {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private RutasTemporales() {}

    // Handler del hilo principal, construido AL VUELO (no como campo estático): así cargar esta
    // clase para llamar a parsearParche() desde una prueba (JUnit puro, sin Android) no choca
    // contra Looper.getMainLooper() sin mockear -- solo lo toca refrescar(), que es Context-real.
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    /** Descarga en segundo plano e instala los parches vigentes. {@code alTerminar} (opcional)
     *  corre en el hilo principal. Cualquier falla se descarta -- si no hay conexión o no hay
     *  parches, simplemente no se aplica ningún cambio (no se borra lo que ya había cargado). */
    public static void refrescar(Context ctx, Runnable alTerminar) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Map<Integer, Linea> parches = new HashMap<>();
            try {
                String json = Backend.descargar(Config.PATH_RUTAS_TEMP);   // con failover
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.optJSONArray("rutas");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.getJSONObject(i);
                        Linea l = parsearParche(p, colorParche(app, p.optInt("numero", 0)));
                        if (l != null) parches.put(l.numero, l);
                    }
                }
            } catch (Exception e) {
                // Sin conexión o sin archivo aún: no es error fatal, solo no hay parches que aplicar.
                return;
            }
            GtfsRepository.aplicarParches(parches);
            if (alTerminar != null) main().post(alTerminar);
        });
    }

    /** Un parche -> Linea completa (reemplaza 'numero' mientras esté vigente). Null si viene
     *  incompleto o malformado (se ignora ESE parche, no debe tumbar los demás ni la app). Sin
     *  Context (puro): package-private para probarse directo, mismo trato que otros helpers de
     *  Planificador (corrManual()/nucleoCoincide()/subRuta()). */
    static Linea parsearParche(JSONObject p, String colorHex) {
        try {
            int numero = p.getInt("numero");
            String nombre = p.optString("nombre", "Servicio temporal");
            List<Estacion> estaciones = new ArrayList<>();
            JSONArray je = p.getJSONArray("estaciones");
            for (int i = 0; i < je.length(); i++) {
                JSONObject e = je.getJSONObject(i);
                estaciones.add(new Estacion(e.getString("nombre"), e.getDouble("lat"), e.getDouble("lon")));
            }
            JSONArray jr = p.getJSONArray("ruta");
            List<LatLng> ruta = new ArrayList<>();
            for (int i = 0; i < jr.length(); i++) {
                JSONArray punto = jr.getJSONArray(i);
                ruta.add(new LatLng(punto.getDouble(0), punto.getDouble(1)));
            }
            if (estaciones.size() < 2 || ruta.size() < 2) return null;
            return new Linea(numero, nombre, colorHex, estaciones, ruta);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reusa el color de la línea/ramal base si 'numero' ya existe (lo normal: el parche extiende
     *  o reemplaza un ramal real, p. ej. el 112); si el número es nuevo por completo, gris neutro
     *  en vez de inventar un color oficial que no corresponde a ningún sistema. */
    private static String colorParche(Context ctx, int numero) {
        Linea existente = GtfsRepository.porNumero(ctx, numero);
        return existente != null ? String.format("#%06X", existente.color & 0xFFFFFF) : "#9E9E9E";
    }
}
