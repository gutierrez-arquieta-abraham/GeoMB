package com.memegrados.GeoMB;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicios de una línea Mexibús para un viaje concreto (origen→destino sobre una sola línea base).
 * Con los datos actuales se distinguen dos recorridos REALES: Ordinario (línea 10X, para en todas) y
 * Express (línea 12X, salta estaciones). Cada uno puede tomarse en unidad Rosa (exclusiva para mujeres),
 * que es el MISMO recorrido en un vehículo distinto; por eso Rosa es un modificador, no otra ruta.
 *
 * Los servicios más finos (TR3 vs TR4 en L1; Mixto en L2; etc.) tienen distinta lista de paradas y
 * requieren capturar esos datos por servicio: se añadirán aquí cuando estén disponibles.
 */
public final class Servicios {

    private Servicios() {}

    public static final class Servicio {
        public final String nombre;   // etiqueta visible ("Ordinario", "Express", "Ordinario · Rosa"…)
        public final int linea;       // línea a rutear (10X ordinario, 12X exprés)
        public final boolean rosa;    // unidad exclusiva para mujeres (mismo recorrido)
        Servicio(String nombre, int linea, boolean rosa) { this.nombre = nombre; this.linea = linea; this.rosa = rosa; }
    }

    /** Línea base (ordinaria) de cualquier variante Mexibús: 121→101, 111→101, 101→101. */
    public static int base(int n) {
        if (n >= 100 && n < 200) return 100 + (n % 10);
        return n;
    }

    /** ¿La línea presta un servicio EXPRÉS con lista de paradas propia? (L1–L4 ⇒ 121–124) */
    private static int expresDe(int base) {
        return (base >= 101 && base <= 104) ? base + 20 : 0;
    }

    private static boolean sirve(Context ctx, int linea, String nombreOrigen, String nombreDestino) {
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return false;
        boolean o = false, d = false;
        String no = Planificador.norm(Planificador.sinMxb(nombreOrigen));
        String nd = Planificador.norm(Planificador.sinMxb(nombreDestino));
        for (Estacion e : l.estaciones) {
            String ne = Planificador.norm(Planificador.sinMxb(e.nombre));
            if (ne.equals(no)) o = true;
            if (ne.equals(nd)) d = true;
        }
        return o && d;
    }

    /**
     * Servicios disponibles para ir de {@code origen} a {@code destino} sobre la línea base {@code base}.
     * Incluye variantes Rosa solo si {@code incluirRosa}. Devuelve lista vacía si no hay elección real.
     */
    public static List<Servicio> disponibles(Context ctx, int base, String origen, String destino, boolean incluirRosa) {
        List<Servicio> res = new ArrayList<>();
        if (base < 100 || base >= 200) return res;   // solo Mexibús
        boolean hayOrd = sirve(ctx, base, origen, destino);
        int ex = expresDe(base);
        boolean hayExp = ex != 0 && sirve(ctx, ex, origen, destino);
        if (hayOrd) res.add(new Servicio("Ordinario", base, false));
        if (hayExp) res.add(new Servicio("Express", ex, false));
        if (incluirRosa) {
            if (hayOrd) res.add(new Servicio("Ordinario · Rosa", base, true));
            if (hayExp) res.add(new Servicio("Express · Rosa", ex, true));
        }
        // Si solo hay una forma de viajar y sin Rosa, no hay elección: se devuelve vacío.
        return res.size() <= 1 ? new ArrayList<>() : res;
    }
}
