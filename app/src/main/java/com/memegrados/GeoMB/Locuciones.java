package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Réplica EXACTA (aislada) de la generación de textos de correspondencia/terminal de
 * {@link RecorridoService}, para pre-descargar sus audios (Fase 2) con los mismos strings.
 * Debe mantenerse en sincronía con RecorridoService; si algún string divergiera, solo se
 * pierde el acierto de caché (se baja online), nunca truena.
 */
public final class Locuciones {

    private static final double CORRESP_VOZ_M = 600.0;

    private Locuciones() {}

    /** nom(): sin 'MXB ' y cortando en '('. */
    public static String nom(String nombre) {
        if (nombre == null) return null;
        String s = Planificador.sinMxb(nombre);
        int par = s.indexOf('(');
        return par >= 0 ? s.substring(0, par).trim() : s;
    }

    static int baseLinea(int n) {
        if (n >= 200) return 200 + (n % 10);
        if (n >= 100) return 100 + (n % 10);
        return n;
    }
    static int sistemaDe(int n) { return n < 100 ? 0 : (n < 200 ? 1 : 2); }
    static String numLinea(int base) {
        return String.valueOf(base >= 200 ? base - 200 : (base >= 100 ? base - 100 : base));
    }
    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String unir(List<String> ls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return sb.toString();
    }

    static int palabraTransferencia(int a, int b) {
        boolean aM = a < 100, bM = b < 100;
        if (aM && bM) return R.string.voz_palabra_transbordo;
        if (!aM && !bM) return R.string.voz_palabra_correspondencia;
        return R.string.voz_palabra_conexion;
    }

    private static final Set<String> STOP_NUCLEO = new HashSet<>(Arrays.asList(
            "mxb", "mxc", "mb", "conexion", "mexibus", "meksibus", "mexicable", "meksicable",
            "metrobus", "linea", "y", "e"));
    private static String nucleo(String nombre) {
        StringBuilder sb = new StringBuilder();
        for (String w : Planificador.norm(nombre).split(" ")) {
            if (w.isEmpty() || STOP_NUCLEO.contains(w) || w.matches("l[0-9]+a?")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }
    private static boolean nucleoCoincide(String a, String b) {
        String na = nucleo(a), nb = nucleo(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** Líneas con las que la estación tiene correspondencia (mismo criterio que el recorrido). */
    static TreeSet<Integer> lineasEnEstacion(Context ctx, int linea, String nombre, LatLng pos) {
        TreeSet<Integer> s = new TreeSet<>();
        if (pos == null) return s;
        int bp = baseLinea(linea), sisP = sistemaDe(linea);
        try {
            String pn = Planificador.norm(Planificador.sinMxb(nombre));
            List<Linea> todas = new ArrayList<>(GtfsRepository.getLineas(ctx));
            todas.addAll(GtfsRepository.getMexibus(ctx));
            for (Linea l : todas) {
                if (baseLinea(l.numero) == bp) continue;
                boolean mismoSistema = sistemaDe(l.numero) == sisP;
                for (Estacion e : l.estaciones) {
                    if (e.soloMapa) continue;
                    boolean nombreOk = mismoSistema
                            ? Planificador.norm(Planificador.sinMxb(e.nombre)).equals(pn)
                            : nucleoCoincide(nombre, e.nombre);
                    if (!nombreOk) continue;
                    if (Linea.distancia(pos, e.posicion) <= CORRESP_VOZ_M) { s.add(baseLinea(l.numero)); break; }
                }
            }
        } catch (Exception ignore) {}
        return s;
    }

    private static void correspManuales(String nombre, int linea, TreeSet<Integer> bases) {
        String nn = Planificador.norm(nombre);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(metrobus|mexibus|mexicable)\\s+l\\s*([0-9]+)").matcher(nn);
        while (m.find()) {
            int off = m.group(1).equals("metrobus") ? 0 : (m.group(1).equals("mexibus") ? 100 : 200);
            try { bases.add(off + Integer.parseInt(m.group(2))); } catch (NumberFormatException ignore) {}
        }
        bases.remove(baseLinea(linea));
    }

    static TreeSet<Integer> basesCorresp(Context ctx, int linea, String nombre, LatLng pos) {
        TreeSet<Integer> bases = lineasEnEstacion(ctx, linea, nombre, pos);
        correspManuales(nombre, linea, bases);
        if (!Modos.mostrarMexibus(ctx)) {
            java.util.Iterator<Integer> it = bases.iterator();
            while (it.hasNext()) if (it.next() >= 100) it.remove();
        }
        return bases;
    }

    private static String listaLineas(Context ctx, int lineaActual, TreeSet<Integer> bases) {
        int sisAct = sistemaDe(lineaActual);
        LinkedHashMap<Integer, List<String>> porSis = new LinkedHashMap<>();
        for (int n : bases) porSis.computeIfAbsent(sistemaDe(n), k -> new ArrayList<>()).add(numLinea(n));
        List<String> partes = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : porSis.entrySet()) {
            List<String> nums = e.getValue();
            boolean varias = nums.size() > 1;
            if (e.getKey() == sisAct) {
                partes.add(ctx.getString(varias ? R.string.voz_lineas_num : R.string.voz_linea_num, unir(nums)));
            } else {
                String sis = ctx.getString(e.getKey() == 0 ? R.string.voz_sis_mb
                        : e.getKey() == 1 ? R.string.voz_sis_mxb : R.string.voz_sis_mxc);
                partes.add(ctx.getString(varias ? R.string.voz_sis_lineas : R.string.voz_sis_linea, sis, unir(nums)));
            }
        }
        return unir(partes);
    }

    static String transferenciaTexto(Context ctx, int lineaActual, TreeSet<Integer> bases) {
        bases.remove(baseLinea(lineaActual));
        if (bases.isEmpty()) return "";
        int[] orden = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion};
        Map<Integer, TreeSet<Integer>> porPalabra = new HashMap<>();
        for (int r : orden) porPalabra.put(r, new TreeSet<>());
        for (int n : bases) porPalabra.get(palabraTransferencia(lineaActual, n)).add(n);
        StringBuilder v = new StringBuilder();
        boolean primero = true;
        for (int r : orden) {
            TreeSet<Integer> g = porPalabra.get(r);
            if (g.isEmpty()) continue;
            String lista = listaLineas(ctx, lineaActual, g);
            String palabra = ctx.getString(r);
            if (primero) { v.append(ctx.getString(R.string.voz_tf_primera, palabra, lista)); primero = false; }
            else { v.append(ctx.getString(R.string.voz_tf_sig, cap(palabra), lista)); }
        }
        return v.toString();
    }

    static boolean esTerminal(int linea, String nombre) {
        if ((linea == 104 || linea == 124) && Planificador.norm(nombre).contains("indios verdes")) return false;
        return Planificador.esTerminalDe(linea, nombre);
    }

    static String vozTerminal(Context ctx, int linea, String nombre, LatLng pos, boolean llegada) {
        StringBuilder v = new StringBuilder(ctx.getString(
                llegada ? R.string.voz_term_lleg : R.string.voz_term_prox, nom(nombre)));
        return v.append(transferenciaTexto(ctx, linea, basesCorresp(ctx, linea, nombre, pos))).toString();
    }
}
