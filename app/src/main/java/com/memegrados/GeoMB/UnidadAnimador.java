package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import java.util.HashMap;
import java.util.Map;

/**
 * Anima los marcadores de unidades en tiempo real PEGÁNDOLOS AL GRAFO de su línea (la polilínea con la
 * que se dibuja) y avanzándolos por su velocidad ({@code speed}) entre actualizaciones del feed:
 *
 *  · Al recibir una posición, se PROYECTA sobre la polilínea de la línea (no se dibuja el GPS crudo, que
 *    a veces cae fuera del carril) y el marcador se desliza por la ruta hasta esa posición ({@link Config#ANIM_MS}).
 *  · Después sigue avanzando sobre la ruta a la velocidad reportada (dead-reckoning), dando sensación de
 *    tiempo real; la siguiente actualización corrige la deriva.
 *  · Si la unidad no tiene línea o cae lejos del trazo, se anima en línea recta como antes (respaldo).
 *
 * El sentido de avance se toma del cambio de posición entre actualizaciones (fiable) y, la primera vez,
 * del destino. Cada pantalla (mapa general, planificador) usa su propia instancia.
 */
public final class UnidadAnimador {

    private static final double CERCA_LINEA_M = 150.0;   // máx. desviación del GPS al trazo para "pegarlo"
    private static final float  VEL_MIN_MS    = 0.5f;    // por debajo de esto se considera detenida (no avanza)
    // El avance entre actualizaciones usa solo una FRACCIÓN de la velocidad reportada: así el marcador
    // queda SIEMPRE un poco atrás de la posición real y la siguiente actualización corrige HACIA ADELANTE,
    // sin "regresones" (que aparecían al extrapolar a velocidad completa y sobrepasar la próxima posición).
    private static final double FACTOR_VEL    = 0.4;     // 60% (rango razonable ~0.5–0.8)

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long seq = 0;
    private final Map<String, Long>    token   = new HashMap<>();   // animación vigente por unidad
    private final Map<String, Double>  distAct = new HashMap<>();   // distancia-along actual (m)
    private final Map<String, Double>  ultObj  = new HashMap<>();   // último objetivo (para el sentido)

    public UnidadAnimador(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Posición INICIAL (proyectada al grafo) para crear el marcador; siembra el estado. */
    public LatLng inicial(UnidadReal u) {
        Linea l = lineaDe(u);
        if (l == null) return u.posicion;
        double d = l.distanciaEn(u.posicion);
        if (Linea.distancia(u.posicion, l.puntoEn(d)) > CERCA_LINEA_M) return u.posicion;   // fuera del trazo
        distAct.put(u.numero, d);
        ultObj.put(u.numero, d);
        return l.puntoEn(d);
    }

    /** Anima el marcador de {@code u}: corrige hacia su posición (por el grafo) y luego avanza por velocidad. */
    public void animar(UnidadReal u, Marker marker) {
        Linea l = lineaDe(u);
        LatLng cruda = u.posicion;
        if (l == null || Linea.distancia(cruda, l.puntoEn(l.distanciaEn(cruda))) > CERCA_LINEA_M) {
            recta(u.numero, marker, cruda);   // sin línea o fuera del trazo: respaldo en recta
            return;
        }
        final double dObj = l.distanciaEn(cruda);
        Double prev = ultObj.get(u.numero);
        final int dir = (prev != null && Math.abs(dObj - prev) > 1.0)
                ? (dObj >= prev ? 1 : -1) : sentido(l, dObj, u.destino);
        ultObj.put(u.numero, dObj);

        final long tk = ++seq; token.put(u.numero, tk);
        final double dIni = distAct.containsKey(u.numero) ? distAct.get(u.numero) : dObj;
        final long t0 = SystemClock.uptimeMillis();
        final float vel = u.velMs;
        final double largo = l.largoTotal();
        final String num = u.numero;
        final Linea lf = l;
        h.post(new Runnable() {
            @Override public void run() {
                Long a = token.get(num);
                if (a == null || a != tk) return;   // reemplazada por una actualización más nueva
                long el = SystemClock.uptimeMillis() - t0;
                double d;
                boolean seguir;
                if (el < Config.ANIM_MS) {
                    double t = el / (double) Config.ANIM_MS;
                    d = dIni + (dObj - dIni) * t;    // desliza por la RUTA hacia la posición reportada
                    seguir = true;
                } else {
                    // Ya alcanzó la posición reportada: avanza sobre la ruta a una FRACCIÓN de su velocidad
                    // (queda un poco atrás para que el próximo update corrija hacia adelante, sin regresones).
                    d = dObj + dir * (vel * FACTOR_VEL) * ((el - Config.ANIM_MS) / 1000.0);
                    seguir = vel > VEL_MIN_MS;       // detenida: no hace falta seguir animando
                }
                d = Math.max(0, Math.min(largo, d));
                distAct.put(num, d);
                try { marker.setPosition(lf.puntoEn(d)); } catch (Exception e) { return; }
                if (seguir) h.postDelayed(this, 80);   // ~12 fps: suave y ligero
            }
        });
    }

    /** Olvida el estado de una unidad (al quitar su marcador). */
    public void olvidar(String numero) {
        token.remove(numero); distAct.remove(numero); ultObj.remove(numero);
    }

    /** Limpia todo (al borrar todos los marcadores). */
    public void limpiar() {
        token.clear(); distAct.clear(); ultObj.clear();
    }

    // ---- internos ----

    private Linea lineaDe(UnidadReal u) {
        if (u == null || u.linea == null) return null;
        return GtfsRepository.porNumero(ctx, u.linea);
    }

    /** +1 si el destino queda "adelante" (mayor distancia) que la unidad; -1 si atrás. */
    private static int sentido(Linea l, double dU, String destino) {
        if (destino != null) {
            for (Estacion e : l.estaciones) {
                if (e.nombre != null && e.nombre.equalsIgnoreCase(destino))
                    return l.distanciaEn(e.posicion) >= dU ? 1 : -1;
            }
        }
        return 1;   // por defecto, hacia el final de la ruta
    }

    /** Respaldo: interpolación en LÍNEA RECTA (unidades sin línea o fuera del trazo). */
    private void recta(String numero, Marker marker, LatLng destino) {
        final LatLng inicio = marker.getPosition();
        if (inicio.latitude == destino.latitude && inicio.longitude == destino.longitude) return;
        final long tk = ++seq; token.put(numero, tk);
        distAct.remove(numero); ultObj.remove(numero);
        final long t0 = SystemClock.uptimeMillis();
        h.post(new Runnable() {
            @Override public void run() {
                Long a = token.get(numero);
                if (a == null || a != tk) return;
                float t = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) Config.ANIM_MS);
                double lat = inicio.latitude + t * (destino.latitude - inicio.latitude);
                double lon = inicio.longitude + t * (destino.longitude - inicio.longitude);
                try { marker.setPosition(new LatLng(lat, lon)); } catch (Exception e) { return; }
                if (t < 1f) h.postDelayed(this, 16);
            }
        });
    }
}
