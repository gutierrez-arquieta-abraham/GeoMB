package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Estaciones para las pantallas del estilo CITI (buscar, cercanas, favoritos, tiempos): Metrobús y,
 * según los ajustes "Mostrar Mexibús"/"Mostrar Mexicable", también esas redes.
 */
// ============================================================
// CLASE    : CitiDatos
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// La app de Indra pedía todo a su servidor (paradas, líneas de la parada,
// estimaciones). Aquí sale de los mismos datos que usa el resto de GeoMB:
// GtfsRepository.getRuteables() (Metrobús + Mexibús/Mexicable activos +
// parches de ruta vigentes) y Llegadas para los tiempos.
//
// Una "Est" es un andén: línea + nombre + posición. Los transbordos se
// detectan por nombre normalizado (Planificador.norm), y cada línea del
// transbordo conserva SU andén (pueden estar a cientos de metros).
// ============================================================
public final class CitiDatos {

    private CitiDatos() {}

    /** Un andén de una línea. */
    public static final class Est {
        public final String nombre;   // nombre a mostrar
        public final String canon;    // nombre del catálogo, el que entiende Planificador.calcular()
        public final String icono;    // pictograma (ic_est_*) o "" si no hay
        public final int linea;
        public final int color;       // color de línea en estilo CITI
        public final LatLng pos;

        Est(String nombre, String canon, String icono, int linea, int color, LatLng pos) {
            this.nombre = nombre; this.canon = canon != null ? canon : nombre;
            this.icono = icono == null ? "" : icono;
            this.linea = linea; this.color = color; this.pos = pos;
        }

        public Est(String nombre, String icono, int linea, int colorLinea, double lat, double lon) {
            this(nombre, null, icono, linea, colorLinea, lat, lon);
        }

        public Est(String nombre, String canon, String icono, int linea, int colorLinea, double lat, double lon) {
            this(nombre, canon, icono, linea, EstiloCiti.color(linea, colorLinea), new LatLng(lat, lon));
        }
    }

    /** Todas las estaciones (una por andén) de las líneas activas. */
    public static List<Est> todas(Context c) {
        List<Est> res = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (Linea l : GtfsRepository.getRuteables(c)) {
            for (Estacion e : l.estaciones) {
                String nombre = nombreDe(c, e, l.numero);
                String k = l.numero + "|" + e.posicion.latitude + "," + e.posicion.longitude;
                if (!vistas.add(k)) continue;   // el catálogo repite algunos andenes (p. ej. Indios Verdes L1)
                res.add(new Est(nombre, e.nombre, e.icono, l.numero, EstiloCiti.color(l.numero, l.color), e.posicion));
            }
        }
        return res;
    }

    /** Nombre a mostrar de una estación: el del catálogo en Metrobús; en Mexibús/Mexicable el mismo
     *  que pone el mapa (sin prefijo "MXB", desambiguado). Lo usan también las favoritas como clave,
     *  así que el mapa y las listas deben llamarlo igual. */
    public static String nombreDe(Context c, Estacion e, int linea) {
        return linea >= 100 ? Planificador.nombreMostrar(c, e.nombre, linea) : e.nombre;
    }

    /** Una sola entrada por (línea, nombre): para las listas, que no distinguen andenes. */
    public static List<Est> unicas(Context c) {
        List<Est> res = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (Est e : todas(c)) if (vistas.add(e.linea + "|" + Planificador.norm(e.nombre))) res.add(e);
        return res;
    }

    /** Las líneas que paran en la estación de {@code e}, cada una con su andén más cercano a {@code e}. */
    public static TreeMap<Integer, Est> lineasDe(Context c, Est e) {
        TreeMap<Integer, Est> res = new TreeMap<>();
        String n = Planificador.norm(e.nombre);
        for (Est o : todas(c)) {
            if (!Planificador.norm(o.nombre).equals(n)) continue;
            Est actual = res.get(o.linea);
            if (actual == null || Linea.distancia(o.pos, e.pos) < Linea.distancia(actual.pos, e.pos)) res.put(o.linea, o);
        }
        res.put(e.linea, e);
        return res;
    }

    /** Estaciones cuyo nombre contiene {@code texto} (sin acentos ni mayúsculas). */
    public static List<Est> buscar(Context c, String texto) {
        String t = Planificador.norm(texto);
        List<Est> res = new ArrayList<>();
        if (t.isEmpty()) return res;
        for (Est e : unicas(c)) if (Planificador.norm(e.nombre).contains(t)) res.add(e);
        return res;
    }

    /** Las {@code n} estaciones más cercanas a {@code pos}, con su distancia en metros. */
    public static List<Object[]> cercanas(Context c, LatLng pos, int n) {
        List<Object[]> res = new ArrayList<>();
        for (Est e : unicas(c)) res.add(new Object[]{e, Linea.distancia(e.pos, pos)});
        Collections.sort(res, (a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        return res.size() > n ? new ArrayList<>(res.subList(0, n)) : res;
    }

    /** Estaciones favoritas, en el orden en que existen en el catálogo. */
    public static List<Est> favoritas(Context c) {
        List<Est> res = new ArrayList<>();
        for (Est e : todas(c)) if (EstiloCiti.esFavorita(c, e.linea, e.nombre, e.pos)) res.add(e);
        return res;
    }

    /** Nombre de la ruta como en "Seleccione ruta": Metrobús "Indios Verdes-El Caminero" (su nombre);
     *  Mexibús/Mexicable no traen terminales en el nombre ("Mexibús L1"), así que se arma con las
     *  terminales oficiales: "Mexibús Ciudad Azteca-Ojo de Agua". */
    public static String nombreRuta(Context c, int linea) {
        Linea l = GtfsRepository.porNumero(c, linea);
        String nombre = l != null ? l.nombre : "";
        if (linea < 100) return EstiloCiti.nombreRuta(nombre);
        String par = Planificador.terminalesMexibusPar(linea);
        if (par == null) return nombre;
        String sistema = Sistemas.sistemaDe(linea) == Sistemas.MEXICABLE ? "Mexicable" : "Mexibús";
        return sistema + " " + EstiloCiti.nombreRuta(par);
    }

    /** ¿La línea va en "Seleccione ruta"? Las exprés de Mexibús (121–124) son un servicio de su línea
     *  base, no otra ruta: no se listan aparte. */
    public static boolean esRutaListable(int linea) {
        return linea < 121 || linea > 124;
    }

    /** "Línea 1" / "Mexibús 1" / "Mexicable 1". */
    public static String etiquetaSistema(int linea) {
        String n = etiqueta(linea);
        switch (Sistemas.sistemaDe(linea)) {
            case Sistemas.MEXIBUS:   return "Mexibús " + n;
            case Sistemas.MEXICABLE: return "Mexicable " + n;
            default:                 return "Línea " + n;
        }
    }

    /** Número de línea como se ve en el cuadro de ruta: Metrobús "1".."7"; Mexibús "1".."4", "1A"…;
     *  Mexicable "1"/"2". */
    public static String etiqueta(int linea) {
        return Planificador.etiquetaLineaCortaPub(linea);
    }
}
