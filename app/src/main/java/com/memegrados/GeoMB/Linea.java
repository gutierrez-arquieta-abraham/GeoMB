package com.memegrados.GeoMB;

import android.graphics.Color;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

import java.util.List;

/** Línea del Metrobús con su trazado y estaciones (datos del GTFS). */
public class Linea {

    /** Metros por grado de latitud (aprox. CDMX). */
    private static final double M_GRADO = 111320.0;
    private static final double COS_LAT = Math.cos(Math.toRadians(19.4));

    public final int numero;
    public final String nombre;
    public final int color;
    public final List<Estacion> estaciones;
    public final List<LatLng> ruta;

    /**
     * Trazado oficial dividido en tramos (ida/vuelta y ramales), tomado del
     * mapa oficial de Metrobús. Si está presente se dibuja en vez de {@link #ruta}
     * (que se conserva para los cálculos de proyección de llegadas/seguimiento).
     * Puede ser null si no hay datos de tramos para la línea.
     */
    public List<List<LatLng>> segmentos;

    /** Distancia acumulada (m) hasta cada punto de la ruta. */
    private final double[] acumulado;

    public Linea(int numero, String nombre, String colorHex,
                 List<Estacion> estaciones, List<LatLng> ruta) {
        this.numero = numero;
        this.nombre = nombre;
        this.color = Color.parseColor(colorHex);
        this.estaciones = estaciones;
        this.ruta = ruta;

        acumulado = new double[ruta.size()];
        for (int i = 1; i < ruta.size(); i++) {
            acumulado[i] = acumulado[i - 1] + distancia(ruta.get(i - 1), ruta.get(i));
        }
    }

    public double largoTotal() {
        return acumulado[acumulado.length - 1];
    }

    /** Punto interpolado a `metros` del inicio de la ruta. */
    public LatLng puntoEn(double metros) {
        if (metros <= 0) return ruta.get(0);
        if (metros >= largoTotal()) return ruta.get(ruta.size() - 1);
        int i = 1;
        while (acumulado[i] < metros) i++;
        double t = (metros - acumulado[i - 1]) / (acumulado[i] - acumulado[i - 1]);
        LatLng a = ruta.get(i - 1), b = ruta.get(i);
        return new LatLng(a.latitude + t * (b.latitude - a.latitude),
                a.longitude + t * (b.longitude - a.longitude));
    }

    /**
     * Distancia acumulada (m) desde el inicio de la ruta hasta el punto de la
     * ruta más cercano a {@code p} (proyección de p sobre la polilínea).
     * Sirve para ubicar unidades y estaciones a lo largo del recorrido.
     */
    public double distanciaEn(LatLng p) {
        double mejorPerp = Double.MAX_VALUE;
        double mejorAcum = 0;
        for (int i = 1; i < ruta.size(); i++) {
            LatLng a = ruta.get(i - 1), b = ruta.get(i);
            double[] pr = proyectar(p, a, b);   // {t, distPerp}
            if (pr[1] < mejorPerp) {
                mejorPerp = pr[1];
                mejorAcum = acumulado[i - 1] + pr[0] * distancia(a, b);
            }
        }
        return mejorAcum;
    }

    /** Proyecta p sobre el segmento a-b (en metros). Devuelve {t∈[0,1], distPerp}. */
    private static double[] proyectar(LatLng p, LatLng a, LatLng b) {
        double ax = a.longitude * M_GRADO * COS_LAT, ay = a.latitude * M_GRADO;
        double bx = b.longitude * M_GRADO * COS_LAT, by = b.latitude * M_GRADO;
        double px = p.longitude * M_GRADO * COS_LAT, py = p.latitude * M_GRADO;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double cx = ax + t * dx, cy = ay + t * dy;
        return new double[]{t, Math.hypot(px - cx, py - cy)};
    }

    public LatLngBounds limites() {
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : ruta) b.include(p);
        return b.build();
    }

    static double distancia(LatLng a, LatLng b) {
        double dy = (a.latitude - b.latitude) * M_GRADO;
        double dx = (a.longitude - b.longitude) * M_GRADO * COS_LAT;
        return Math.hypot(dx, dy);
    }
}
