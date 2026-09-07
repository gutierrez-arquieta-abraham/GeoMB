package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Descarga el estado actual de afectaciones de Mexibús que publica el backend
 * ({@code /data/afectaciones_mexibus.json}, generado por mexibus_afectaciones.py) y lo
 * inyecta en {@link Manifestaciones} para que el panel de estado del servicio lo muestre.
 *
 * Formato:  {"actualizado":<epoch>, "afectaciones":[{"linea":102,"estado":"Sin servicio",
 *                                                    "lugar":"Tultitlán","info":"..."}]}
 */
public final class AfectacionesMexibus {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AfectacionesMexibus() {}

    /** Descarga en segundo plano, actualiza el panel y bloquea el ruteo de líneas "sin servicio".
     *  {@code alTerminar} corre en el hilo principal. */
    public static void refrescar(Context ctx, Runnable alTerminar) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            List<Manifestaciones.Afectacion> lista = new ArrayList<>();
            Set<String> bloq = new HashSet<>();   // estaciones a bloquear (líneas suspendidas)
            try {
                String json = Backend.descargar(Config.PATH_AFECT_MXB);   // con failover
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.optJSONArray("afectaciones");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        int linea = a.optInt("linea", 0);
                        if (linea <= 0) continue;
                        String estado = a.isNull("estado") ? "" : a.optString("estado", "");
                        String lugar  = a.isNull("lugar")  ? "" : a.optString("lugar", "");
                        String info   = a.isNull("info")   ? "" : a.optString("info", "");
                        // categoría ESTADO → aparece en la tabla de estado por línea (no en el panel de elevadores).
                        lista.add(new Manifestaciones.Afectacion("", linea, lugar, estado, "", info,
                                false, Manifestaciones.C_ESTADO));
                        // Bloqueo de ruteo según el caso (suspensión total / circuito / paso de largo).
                        Linea l = GtfsRepository.porNumero(app, linea);
                        if (l != null) bloqueoLinea(l, estado, a.optJSONArray("circuito"), lugar, bloq);
                    }
                }
            } catch (Exception e) {
                // Sin conexión o sin archivo aún: no es error fatal, solo no hay datos Mexibús.
                return;
            }
            boolean cambio = Manifestaciones.setMexibus(lista);
            Manifestaciones.setMexibusBloqueadas(bloq);
            if (cambio && alTerminar != null) MAIN.post(alTerminar);
        });
    }

    /**
     * Igual que {@link #bloqueoLinea} pero recibe los tramos de circuito ya parseados
     * (List de pares [terminal, estación]) en vez de un JSONArray. Lo usa el respaldo LOCAL
     * ({@link AfectMexibusFeed}) para bloquear el ruteo con la misma lógica que el panel del EC2.
     */
    static void bloqueoLineaLocal(Linea l, String estado, java.util.List<String[]> circ,
                                  String lugar, Set<String> bloq) {
        JSONArray arr = null;
        if (circ != null && !circ.isEmpty()) {
            arr = new JSONArray();
            for (String[] par : circ) {
                JSONArray p = new JSONArray();
                p.put(par.length > 0 ? par[0] : "");
                p.put(par.length > 1 ? par[1] : "");
                arr.put(p);
            }
        }
        bloqueoLinea(l, estado, arr, lugar, bloq);
    }

    /**
     * Calcula las estaciones a bloquear de una línea según el aviso:
     *  · con {@code circuito} (tramos "A-B") → habilita solo esos tramos y bloquea el resto;
     *  · "sin servicio" (sin circuito) → bloquea TODA la línea;
     *  · "paso de largo" → bloquea solo la estación afectada (la línea sigue).
     * Salvaguarda: si viene circuito pero NINGÚN tramo mapea a estaciones reales, no bloquea nada
     * (evita tumbar la línea por un texto no reconocido).
     */
    private static void bloqueoLinea(Linea l, String estado, JSONArray circuito, String lugar, Set<String> bloq) {
        String e = estado == null ? "" : estado.toLowerCase();
        java.util.List<Estacion> est = l.estaciones;
        if (circuito != null && circuito.length() > 0) {
            boolean[] hab = new boolean[est.size()];
            for (int i = 0; i < circuito.length(); i++) {
                JSONArray par = circuito.optJSONArray(i);
                if (par == null || par.length() < 2) return;   // tramo malformado → no arriesgar
                int ia = indiceEstacion(est, par.optString(0));
                int ib = indiceEstacion(est, par.optString(1));
                if (ia < 0 || ib < 0) return;   // si ALGÚN tramo no mapea, no bloquear nada (solo info)
                for (int k = Math.min(ia, ib); k <= Math.max(ia, ib); k++) hab[k] = true;
            }
            for (int i = 0; i < est.size(); i++)
                if (!hab[i]) bloq.add(Planificador.norm(est.get(i).nombre));
        } else if (e.contains("sin servicio")) {
            for (Estacion x : est) bloq.add(Planificador.norm(x.nombre));   // toda la línea
        } else if (e.contains("paso de largo")) {
            int i = indiceEstacion(est, lugar);
            if (i >= 0) bloq.add(Planificador.norm(est.get(i).nombre));      // solo esa estación
        }
    }

    /** Índice de una estación en la línea por nombre (sin 'MXB ', normalizado, ordinales colapsados). */
    private static int indiceEstacion(java.util.List<Estacion> est, String nombre) {
        String q = normEst(nombre);
        if (q.length() < 3) return -1;
        for (int i = 0; i < est.size(); i++)
            if (normEst(est.get(i).nombre).equals(q)) return i;
        for (int i = 0; i < est.size(); i++) {
            String nn = normEst(est.get(i).nombre);
            if (nn.contains(q) || q.contains(nn)) return i;
        }
        return -1;
    }

    /** Normaliza nombre de estación: sin 'MXB ', sin acentos/mayúsculas, y ordinales "1ro/1°/1o"→"1". */
    private static String normEst(String nombre) {
        String s = Planificador.norm(Planificador.sinMxb(nombre == null ? "" : nombre));
        return s.replaceAll("\\b(\\d+)(ro|do|er|to|vo|mo|no|ra|da|a|o)\\b", "$1");   // 1ro/1°(→1 )/1o → 1
    }
}
