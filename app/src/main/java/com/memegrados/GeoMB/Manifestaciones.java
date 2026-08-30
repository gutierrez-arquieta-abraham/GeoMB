package com.memegrados.GeoMB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado compartido de afectaciones al servicio leídas de la página oficial
 * (https://www.metrobus.cdmx.gob.mx/ServicioMB). Se guardan TODAS las afectaciones,
 * sin importar la razón, con estación/tramo, dirección y razón. El planificador usa
 * las estaciones afectadas para trazar rutas alternas.
 */
public final class Manifestaciones {

    /** Marcador de "ambos sentidos": bloquea la estación completa (sin importar terminal). */
    public static final String AMBOS = "*";

    /**
     * Una afectación del servicio, tal como aparece en la tabla oficial:
     * Línea · Estado (razón general) · Estaciones/tramo afectado · Información adicional.
     */
    public static final int C_ESTADO = 0, C_ELEVADOR = 1, C_MANTENIMIENTO = 2;

    public static final class Afectacion {
        public final String linea;      // "Línea 7" (o "")
        public final int lineaNum;      // 1..7 (o 0) para el logo/color de línea
        public final String lugar;      // estación afectada
        public final String estado;     // encabezado (Estado): "Estación sin servicio", etc.
        public final String direccion;  // sentido / dirección (o "")
        public final String info;       // información adicional
        public final boolean elevador;  // elevador/ascensor (solo movilidad reducida)
        public final int categoria;     // C_ESTADO / C_ELEVADOR / C_MANTENIMIENTO

        public Afectacion(String linea, int lineaNum, String lugar, String estado,
                          String direccion, String info, boolean elevador) {
            this(linea, lineaNum, lugar, estado, direccion, info, elevador,
                    elevador ? C_ELEVADOR : C_ESTADO);
        }

        public Afectacion(String linea, int lineaNum, String lugar, String estado,
                          String direccion, String info, boolean elevador, int categoria) {
            this.linea = linea != null ? linea : "";
            this.lineaNum = lineaNum;
            this.lugar = lugar != null ? lugar : "";
            this.estado = estado != null ? estado : "";
            this.direccion = direccion != null ? direccion : "";
            this.info = info != null ? info : "";
            this.elevador = elevador;
            this.categoria = categoria;
        }

        /** Clave estable de la situación (para no repetir avisos). */
        public String clave() {
            return lineaNum + "|" + estado.toLowerCase() + "|" + lugar.toLowerCase();
        }

        /** Renglón compacto para resúmenes: "Línea N · Estado · Estación". */
        public String resumen() {
            StringBuilder b = new StringBuilder();
            if (!linea.isEmpty()) b.append(linea);
            if (!estado.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(estado);
            if (!lugar.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(lugar);
            return b.toString();
        }
    }

    private static final Set<String> afectadas = ConcurrentHashMap.newKeySet();  // nombres normalizados (ambos sentidos)
    // Bloqueo POR SENTIDO: estación(norm) -> {terminal(norm) bloqueada | AMBOS}. General y de movilidad reducida.
    private static final Map<String, Set<String>> porSentido = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> porSentidoMR = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> simulado = new ConcurrentHashMap<>();   // inyección de prueba
    private static final Set<String> cortes = ConcurrentHashMap.newKeySet();   // "linea|nnA|nnB": corte de tramo (parte la línea) — panel de pruebas
    private static final Set<String> cortesReales = ConcurrentHashMap.newKeySet();   // cortes detectados del feed real (ServicioMB)
    private static final List<Afectacion> lista = new CopyOnWriteArrayList<>();
    private static volatile String resumen = "";       // nombres/lugares legibles, separados por coma
    private static volatile long actualizado = 0L;

    private Manifestaciones() {}

    /** Nombres de estación (normalizados) bloqueados en AMBOS sentidos (compatibilidad). */
    public static Set<String> bloqueadas() {
        Set<String> s = new HashSet<>(afectadas);
        for (Map.Entry<String, Set<String>> e : porSentido.entrySet()) {
            if (e.getValue().contains(AMBOS)) s.add(e.getKey());
        }
        return s;
    }

    /**
     * ¿La estación está bloqueada para viajar HACIA la terminal indicada? Considera AMBOS
     * (estación completa) y el sentido específico. Si movilidadReducida, suma los elevadores.
     * terminalNn = nombre normalizado de la terminal del sentido de viaje (o null).
     */
    public static boolean bloqueadoHacia(String estacionNn, String terminalNn, boolean movilidadReducida) {
        if (contiene(simulado, estacionNn, terminalNn)) return true;   // simulación de prueba (panel oculto)
        if (afectadas.contains(estacionNn)) return true;   // estado en tiempo real: ambos sentidos
        if (contiene(porSentido, estacionNn, terminalNn)) return true;
        return movilidadReducida && contiene(porSentidoMR, estacionNn, terminalNn);
    }

    /** Simula una afectación por sentido para PROBAR el planificador (terminalNn null/"" = ambos). */
    public static void simular(String estacionNn, String terminalNn) {
        simulado.computeIfAbsent(estacionNn, z -> ConcurrentHashMap.newKeySet())
                .add(terminalNn == null || terminalNn.isEmpty() ? AMBOS : terminalNn);
        actualizado = System.currentTimeMillis();
    }

    public static void limpiarSimulado() {
        simulado.clear();
        cortes.clear();
        actualizado = System.currentTimeMillis();
    }

    public static boolean haySimulado() { return !simulado.isEmpty() || !cortes.isEmpty(); }

    /** Corta un tramo entre dos estaciones adyacentes de una línea (no se puede pasar por ahí). */
    public static void cortar(int linea, String nn1, String nn2) {
        if (nn1 == null || nn2 == null) return;
        boolean o = nn1.compareTo(nn2) <= 0;
        cortes.add(linea + "|" + (o ? nn1 : nn2) + "|" + (o ? nn2 : nn1));
        actualizado = System.currentTimeMillis();
    }

    /** ¿El paso entre estas dos estaciones (de esta línea) está cortado? (pruebas o feed real) */
    public static boolean corte(int linea, String nn1, String nn2) {
        if (cortes.isEmpty() && cortesReales.isEmpty()) return false;
        boolean o = nn1.compareTo(nn2) <= 0;
        String k = linea + "|" + (o ? nn1 : nn2) + "|" + (o ? nn2 : nn1);
        return cortes.contains(k) || cortesReales.contains(k);
    }

    /** Reemplaza el conjunto de cortes REALES (partición de línea) detectados del feed ServicioMB.
     *  Se llama en cada ciclo del servicio; si el conjunto cambia, invalida la caché del grafo. */
    public static void reemplazarCortesReales(Set<String> nuevos) {
        Set<String> n = (nuevos == null) ? Collections.<String>emptySet() : nuevos;
        if (cortesReales.equals(n)) return;   // sin cambios: no toca la caché
        cortesReales.clear();
        cortesReales.addAll(n);
        actualizado = System.currentTimeMillis();
    }

    /** Clave de corte estable "linea|nnMenor|nnMayor" para dos estaciones adyacentes. */
    public static String claveCorte(int linea, String nn1, String nn2) {
        if (nn1 == null || nn2 == null) return null;
        boolean o = nn1.compareTo(nn2) <= 0;
        return linea + "|" + (o ? nn1 : nn2) + "|" + (o ? nn2 : nn1);
    }

    /** Firma estable del conjunto de cortes: sirve para invalidar cachés cuando cambia la
     *  topología del grafo (los cortes parten líneas). No cambia con afectaciones normales. */
    public static String cortesClave() {
        if (cortes.isEmpty() && cortesReales.isEmpty()) return "";
        Set<String> u = new HashSet<>(cortes);
        u.addAll(cortesReales);
        List<String> l = new ArrayList<>(u);
        Collections.sort(l);
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('|');
        return b.toString();
    }

    /** Sentidos bloqueados en una estación: terminales (norm) afectadas, o AMBOS. Vacío = libre. */
    public static Set<String> sentidosBloqueados(String estacionNn, boolean movilidadReducida) {
        Set<String> out = new HashSet<>();
        if (afectadas.contains(estacionNn)) out.add(AMBOS);
        Set<String> a = simulado.get(estacionNn);   if (a != null) out.addAll(a);
        Set<String> b = porSentido.get(estacionNn);  if (b != null) out.addAll(b);
        if (movilidadReducida) { Set<String> c = porSentidoMR.get(estacionNn); if (c != null) out.addAll(c); }
        return out;
    }

    /** Motivo por el que una estación está afectada: C_MANTENIMIENTO, C_ELEVADOR o C_ESTADO
     *  (bloqueo/manifestación). Devuelve -1 si no aparece en la lista. Compara por nombre normalizado. */
    public static int razonCierre(String estacionNn) {
        if (estacionNn == null || estacionNn.isEmpty()) return -1;
        int mejor = -1;
        for (Afectacion a : lista) {
            String ln = Planificador.norm(a.lugar == null ? "" : a.lugar);
            if (ln.length() >= 3 && (ln.contains(estacionNn) || estacionNn.contains(ln))) {
                // Prioriza estado (bloqueo/manifestación) sobre mantenimiento y elevador.
                if (a.categoria == C_ESTADO) return C_ESTADO;
                if (mejor == -1 || a.categoria == C_MANTENIMIENTO) mejor = a.categoria;
            }
        }
        return mejor;
    }

    private static boolean contiene(Map<String, Set<String>> m, String est, String term) {
        Set<String> dirs = m.get(est);
        if (dirs == null) return false;
        return dirs.contains(AMBOS) || (term != null && dirs.contains(term));
    }

    public static boolean hay() {
        return !lista.isEmpty() || !afectadas.isEmpty() || !porSentido.isEmpty()
                || !simulado.isEmpty() || !cortes.isEmpty() || !cortesReales.isEmpty();
    }

    public static String resumen() { return resumen; }

    public static long actualizado() { return actualizado; }

    /** Lista completa de afectaciones (solo lectura). */
    public static List<Afectacion> lista() {
        return Collections.unmodifiableList(new ArrayList<>(lista));
    }

    /**
     * Actualiza el estado con las estaciones bloqueadas (para ruteo) y la lista completa
     * de afectaciones (para mostrar). Devuelve true si algo cambió respecto al ciclo anterior.
     */
    static boolean actualizar(Set<String> nuevasBloqueadas,
                              Map<String, Set<String>> nuevoPorSentido,
                              Map<String, Set<String>> nuevoPorSentidoMR,
                              List<Afectacion> nuevas, String texto) {
        boolean cambio = !afectadas.equals(nuevasBloqueadas)
                || !porSentido.equals(nuevoPorSentido)
                || !porSentidoMR.equals(nuevoPorSentidoMR)
                || lista.size() != nuevas.size();
        afectadas.clear();
        afectadas.addAll(nuevasBloqueadas);
        porSentido.clear();
        if (nuevoPorSentido != null) porSentido.putAll(nuevoPorSentido);
        porSentidoMR.clear();
        if (nuevoPorSentidoMR != null) porSentidoMR.putAll(nuevoPorSentidoMR);
        lista.clear();
        lista.addAll(nuevas);
        resumen = texto;
        actualizado = System.currentTimeMillis();
        return cambio;
    }
}
