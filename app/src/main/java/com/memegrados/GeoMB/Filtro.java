package com.memegrados.GeoMB;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// ============================================================
// CLASE    : Filtro
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Guarda el ESTADO de los filtros que el usuario aplica a las
// unidades del mapa: por línea, destino, ruta o empresa.
// Un campo en null significa "no filtrar por eso".
//
// MÉTODOS DE INSTANCIA:
//   - hayAlguno() : ¿hay al menos un filtro activo?
//   - activos()   : cuántos filtros están activos.
//   - limpiar()   : quita todos los filtros.
//   - cumple(u)   : ¿la unidad 'u' pasa TODOS los filtros activos?
//                   (incluye rutas mixtas que tocan la línea aunque el
//                    feed les ponga otro número).
//
// MÉTODOS ESTÁTICOS (pueblan los selectores desde la data en vivo):
//   - empresasDisponibles / destinosDisponibles / rutasDisponibles /
//     lineasDisponibles : lista ordenada y SIN repetidos (usan TreeSet,
//     que ordena y deduplica solo) de las unidades actuales.
// ============================================================
/**
 * Estado de los filtros aplicados a las unidades (línea / destino / ruta /
 * empresa). Un campo en null significa "sin filtrar por eso".
 */
public class Filtro {

    public Integer linea = null;    // 1..7
    public String destino = null;
    public String ruta = null;      // route_id
    public String empresa = null;

    public boolean hayAlguno() {
        return linea != null || destino != null || ruta != null || empresa != null;
    }

    public int activos() {
        int n = 0;
        if (linea != null) n++;
        if (destino != null) n++;
        if (ruta != null) n++;
        if (empresa != null) n++;
        return n;
    }

    public void limpiar() {
        linea = null; destino = null; ruta = null; empresa = null;
    }

    /** ¿La unidad cumple con todos los filtros activos? */
    public boolean cumple(UnidadReal u) {
        if (linea != null && !linea.equals(u.linea)) {
            // Integra las unidades de rutas mixtas que tocan esta línea aunque el
            // feed les ponga otro número de línea (se detecta por origen+destino).
            if (!RutasMixtas.tocaLinea(u.origen, u.destino, linea)) return false;
        }
        if (destino != null && !destino.equals(u.destino)) return false;
        if (ruta != null && !ruta.equals(u.ruta)) return false;
        if (empresa != null && !empresa.equals(u.empresa)) return false;
        return true;
    }

    // ---- valores disponibles para poblar los selectores (de la data en vivo) ----

    public static List<String> empresasDisponibles(List<UnidadReal> unidades) {
        TreeSet<String> set = new TreeSet<>();
        for (UnidadReal u : unidades) if (u.empresa != null) set.add(u.empresa);
        return new ArrayList<>(set);
    }

    public static List<String> destinosDisponibles(List<UnidadReal> unidades) {
        TreeSet<String> set = new TreeSet<>();
        for (UnidadReal u : unidades) if (u.destino != null && !u.destino.isEmpty()) set.add(u.destino);
        return new ArrayList<>(set);
    }

    public static List<String> rutasDisponibles(List<UnidadReal> unidades) {
        TreeSet<String> set = new TreeSet<>();
        for (UnidadReal u : unidades) if (u.ruta != null && !u.ruta.isEmpty()) set.add(u.ruta);
        return new ArrayList<>(set);
    }

    public static List<Integer> lineasDisponibles(List<UnidadReal> unidades) {
        TreeSet<Integer> set = new TreeSet<>();
        for (UnidadReal u : unidades) if (u.linea != null) set.add(u.linea);
        return new ArrayList<>(set);
    }
}
