package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Cálculo de "próximas llegadas" a una estación, por dirección (destino).
 *
 * Proyecta la estación y cada unidad sobre la polilínea de la línea para saber
 * su distancia a lo largo del recorrido, y estima el tiempo con una velocidad
 * promedio ({@link Config#LLEGADA_VEL_MS}). Solo considera unidades que van
 * hacia el mismo destino (mismo sentido) y que aún no pasaron la estación.
 */
public final class Llegadas {

    /** Máximo de llegadas a mostrar en la lista (ambos sentidos). */
    private static final int MAX_LISTA = 8;
    /** Rango máximo (m) para considerar una unidad como "próxima". */
    private static final double MAX_DIST_M = 6000;

    private Llegadas() {}

    /** Una unidad próxima a llegar. */
    public static final class Prox {
        public final String eco;
        public final String destino;   // hacia dónde va (para distinguir el sentido)
        public final int metros;
        public final int etaSeg;
        Prox(String eco, String destino, int metros, int etaSeg) {
            this.eco = eco; this.destino = destino; this.metros = metros; this.etaSeg = etaSeg;
        }
    }

    /** Compat: un solo sentido (el usado por el aviso/notificación). */
    public static List<Prox> proximas(Context ctx, int linea, LatLng estacion,
                                      String destino, List<UnidadReal> unidades) {
        return proximas(ctx, linea, estacion, destino, unidades, false);
    }

    /**
     * Próximas llegadas a {@code estacion} de la {@code linea}.
     *
     * <p>Si {@code ambosSentidos} es true, incluye las unidades que se acercan
     * por CUALQUIERA de los dos sentidos (según su rumbo), sin filtrar por
     * {@code destino}. Si es false, solo el sentido de {@code destino}.
     *
     * <p>Además de las unidades de la línea, integra las de RUTAS MIXTAS que
     * terminan en esta línea aunque el feed les ponga otro número de línea
     * (se identifican por el número de línea de salida).
     */
    public static List<Prox> proximas(Context ctx, int linea, LatLng estacion,
                                      String destino, List<UnidadReal> unidades,
                                      boolean ambosSentidos) {
        List<Prox> res = new ArrayList<>();
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null || estacion == null) return res;

        double dEst = l.distanciaEn(estacion);
        boolean adelante = sentidoAdelante(l, dEst, destino);

        for (UnidadReal u : unidades) {
            if (!perteneceALinea(u, linea)) continue;

            double dU = l.distanciaEn(u.posicion);
            double rem;
            if (ambosSentidos) {
                // El sentido de cada unidad sale de SU propio destino (fiable),
                // no del rumbo (el feed a veces manda bearings inválidos).
                boolean adel = sentidoAdelante(l, dEst, u.destino);
                rem = adel ? (dEst - dU) : (dU - dEst);
            } else {
                if (destino != null && !destino.equalsIgnoreCase(u.destino)) continue;
                rem = adelante ? (dEst - dU) : (dU - dEst);
            }
            if (rem < -60) continue;          // ya pasó la estación (va de salida)
            if (rem < 0) rem = 0;             // prácticamente en la estación
            if (ambosSentidos && rem > MAX_DIST_M) continue;   // demasiado lejos

            int metros = (int) Math.round(rem);
            int eta = (int) Math.round(rem / Config.LLEGADA_VEL_MS);
            res.add(new Prox(u.numero, u.destino, metros, eta));
        }

        Collections.sort(res, Comparator.comparingInt(p -> p.metros));
        if (ambosSentidos && res.size() > MAX_LISTA) {
            res = new ArrayList<>(res.subList(0, MAX_LISTA));
        }
        return res;
    }

    /** Pertenece a la línea por su número, o es una mixta que la toca (salida/término). */
    private static boolean perteneceALinea(UnidadReal u, int linea) {
        if (u.linea != null && u.linea == linea) return true;
        return RutasMixtas.tocaLinea(u.origen, u.destino, linea);
    }

    /** ¿El destino queda "adelante" (mayor distancia) que la estación en la polilínea? */
    private static boolean sentidoAdelante(Linea l, double dEst, String destino) {
        if (destino == null) return true;
        for (Estacion e : l.estaciones) {
            if (e.nombre != null && e.nombre.equalsIgnoreCase(destino)) {
                return l.distanciaEn(e.posicion) >= dEst;
            }
        }
        // Si el destino no es una estación exacta, compara contra los extremos.
        LatLng ini = l.ruta.get(0), fin = l.ruta.get(l.ruta.size() - 1);
        double dIni = l.distanciaEn(ini), dFin = l.distanciaEn(fin);
        // Heurística: asume que el destino está hacia el extremo final.
        return dFin >= dEst || dFin >= dIni;
    }
}
