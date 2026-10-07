package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import java.util.HashMap;
import java.util.Iterator;
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
 *
 * <p><b>Un solo ticker compartido</b> (no uno por unidad): todas las unidades que se están animando en
 * este momento viven en {@link #animaciones}, y un único {@code Handler.postDelayed} recorre ese mapa
 * en cada tick, mueve lo que corresponda y se reprograma SOLO si queda algo vivo. Antes, cada llamada a
 * {@link #animar}/{@code recta} arrancaba su PROPIO bucle independiente -- con muchas unidades a la vez
 * (p. ej. 50+ visibles), eso eran igual de muchos bucles de Handler compitiendo por el hilo principal.
 * Una actualización que llega a media animación no crea un segundo bucle: solo reemplaza el estado de
 * esa unidad en el mapa, que el ticker ya vigente recoge en su siguiente vuelta.
 */
// ============================================================
// CLASE    : UnidadAnimador
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Anima los marcadores de las unidades en el mapa PEGÁNDOLOS AL GRAFO
// de su línea (la polilínea) y avanzándolos por su velocidad entre
// actualizaciones del feed. Da sensación de "tiempo real" fluido.
//
// CÓMO FUNCIONA (3 pasos):
//   1. Al llegar una posición, se PROYECTA sobre la polilínea (no se dibuja
//      el GPS crudo, que a veces cae fuera del carril) y el marcador se
//      desliza hasta ahí.
//   2. Luego sigue avanzando sobre la ruta a la velocidad reportada
//      (dead-reckoning); la siguiente actualización corrige la deriva.
//   3. Si la unidad no tiene línea o cae lejos del trazo → línea recta (respaldo).
//
// TRUCO ANTI-"REGRESONES": solo avanza una FRACCIÓN de la velocidad
// (FACTOR_VEL) para quedar un poco atrás y corregir HACIA ADELANTE, sin
// saltos hacia atrás. Cada pantalla usa su PROPIA instancia.
//
// UN SOLO TICKER: todas las unidades en movimiento comparten un único
// Handler.postDelayed (ver {@link #tick()}) en vez de uno por unidad.
// ============================================================
public final class UnidadAnimador {

    private static final double CERCA_LINEA_M = 150.0;   // máx. desviación del GPS al trazo para "pegarlo"
    private static final float  VEL_MIN_MS    = 0.5f;    // por debajo de esto se considera detenida (no avanza)
    // El avance entre actualizaciones usa solo una FRACCIÓN de la velocidad reportada: así el marcador
    // queda SIEMPRE un poco atrás de la posición real y la siguiente actualización corrige HACIA ADELANTE,
    // sin "regresones" (que aparecían al extrapolar a velocidad completa y sobrepasar la próxima posición).
    private static final double FACTOR_VEL    = 0.4;     // 60% (rango razonable ~0.5–0.8)

    private static final long TICK_MS = 16;    // cadencia del ticker compartido (igual que el respaldo recto de antes)
    // Fase de avance por velocidad (dead-reckoning): se sigue recalculando/repintando cada 80 ms, EXACTO
    // igual que el bucle propio que tenía antes -- el ticker corre a 16 ms, pero esta fase se autolimita
    // para no hacer más trabajo del que ya hacía (es deliberadamente menos frecuente, "ligera").
    private static final long DEAD_RECKONING_MS = 80;

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;
    private boolean tickerActivo = false;

    /** Estado de animación vigente de una unidad; el ticker compartido lo lee/actualiza cada tick. */
    private static final class Estado {
        Marker marker;
        boolean recta;          // true = respaldo en línea recta; false = pegado al grafo
        // --- modo "pegado al grafo" ---
        Linea linea;
        double dIni, dObj;
        int dir;
        float vel;
        double largo;
        long t0;
        long ultDeadReckoning;  // 0 = todavía no entró a esa fase
        // --- modo "recta" (respaldo) ---
        LatLng inicio, destino;
    }

    private final Map<String, Estado> animaciones = new HashMap<>();   // unidades animándose AHORA
    private final Map<String, Double> distAct = new HashMap<>();   // distancia-along actual (m), sobrevive entre animaciones
    private final Map<String, Double> ultObj  = new HashMap<>();   // último objetivo (para el sentido), ídem

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

        Estado e = new Estado();
        e.marker = marker;
        e.recta = false;
        e.linea = l;
        e.dIni = distAct.containsKey(u.numero) ? distAct.get(u.numero) : dObj;
        e.dObj = dObj;
        e.dir = dir;
        e.vel = u.velMs;
        e.largo = l.largoTotal();
        e.t0 = SystemClock.uptimeMillis();
        animaciones.put(u.numero, e);   // reemplaza cualquier animación previa de esta unidad -- nunca dos a la vez
        arrancarTicker();
    }

    /** Olvida el estado de una unidad (al quitar su marcador). */
    public void olvidar(String numero) {
        animaciones.remove(numero); distAct.remove(numero); ultObj.remove(numero);
    }

    /** Limpia todo y detiene el ticker (al borrar todos los marcadores / destruir la vista). */
    public void limpiar() {
        animaciones.clear(); distAct.clear(); ultObj.clear();
        h.removeCallbacks(ticker);
        tickerActivo = false;
    }

    // ---- ticker compartido ----

    private void arrancarTicker() {
        if (tickerActivo) return;
        tickerActivo = true;
        h.postDelayed(ticker, TICK_MS);
    }

    /** Una vuelta: avanza cada unidad animándose y se reprograma solo si queda alguna viva. */
    private void tick() {
        if (animaciones.isEmpty()) { tickerActivo = false; return; }
        long ahora = SystemClock.uptimeMillis();
        Iterator<Map.Entry<String, Estado>> it = animaciones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Estado> entry = it.next();
            Estado e = entry.getValue();
            boolean seguir = e.recta ? tickRecta(e, ahora) : tickGrafo(entry.getKey(), e, ahora);
            if (!seguir) it.remove();
        }
        if (animaciones.isEmpty()) tickerActivo = false;
        else h.postDelayed(ticker, TICK_MS);
    }

    /** Respaldo en línea recta: desliza de {@code inicio} a {@code destino} en {@link Config#ANIM_MS}. */
    private boolean tickRecta(Estado e, long ahora) {
        float t = Math.min(1f, (ahora - e.t0) / (float) Config.ANIM_MS);
        double lat = e.inicio.latitude + t * (e.destino.latitude - e.inicio.latitude);
        double lon = e.inicio.longitude + t * (e.destino.longitude - e.inicio.longitude);
        try { e.marker.setPosition(new LatLng(lat, lon)); } catch (Exception ex) { return false; }
        return t < 1f;
    }

    /** Pegado al grafo: desliza hasta la posición reportada y luego avanza por velocidad (dead-reckoning). */
    private boolean tickGrafo(String numero, Estado e, long ahora) {
        long el = ahora - e.t0;
        double d;
        boolean seguir;
        if (el < Config.ANIM_MS) {
            double t = el / (double) Config.ANIM_MS;
            d = e.dIni + (e.dObj - e.dIni) * t;    // desliza por la RUTA hacia la posición reportada
            seguir = true;
        } else {
            // Autolimitado a DEAD_RECKONING_MS: igual que el bucle propio que tenía antes, no hace más
            // trabajo solo porque el ticker compartido pase cada 16 ms.
            if (e.ultDeadReckoning != 0 && ahora - e.ultDeadReckoning < DEAD_RECKONING_MS) {
                return e.vel > VEL_MIN_MS;   // sigue vivo, pero este tick no toca el marcador
            }
            e.ultDeadReckoning = ahora;
            d = e.dObj + e.dir * (e.vel * FACTOR_VEL) * ((el - Config.ANIM_MS) / 1000.0);
            seguir = e.vel > VEL_MIN_MS;     // detenida: no hace falta seguir animando
        }
        d = Math.max(0, Math.min(e.largo, d));
        distAct.put(numero, d);
        try { e.marker.setPosition(e.linea.puntoEn(d)); } catch (Exception ex) { return false; }
        return seguir;
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
        LatLng inicio = marker.getPosition();
        if (inicio.latitude == destino.latitude && inicio.longitude == destino.longitude) {
            animaciones.remove(numero);   // ya está en destino: nada que animar
            return;
        }
        distAct.remove(numero); ultObj.remove(numero);
        Estado e = new Estado();
        e.marker = marker;
        e.recta = true;
        e.inicio = inicio;
        e.destino = destino;
        e.t0 = SystemClock.uptimeMillis();
        animaciones.put(numero, e);
        arrancarTicker();
    }
}
