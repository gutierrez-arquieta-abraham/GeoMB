package com.memegrados.GeoMB;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Horarios de servicio del Metrobús (assets/horarios.json): por cada RUTA de servicio guarda
 * primera/última salida por tipo de día (LJ=Lun-Jue, V=Vie, S=Sáb, D=Dom, LV=Lun-Vie) y por
 * sentido (ida=origen→destino, vuelta=destino→origen). Sirve para:
 *   · información adicional (ventana de servicio de hoy por línea/ruta), y
 *   · validar en el planificador si una línea aún circula a la hora actual.
 * Regla de defecto: si un viernes no tiene su propio bloque, hereda el de Lun-Jue.
 */
public final class Horarios {

    /** Ventana de un sentido en minutos desde medianoche; ult<prim ⇒ cruza medianoche. */
    public static final class Ventana {
        public final int prim, ult;
        Ventana(int prim, int ult) { this.prim = prim; this.ult = ult; }
        /** ¿La hora (min) cae dentro de la ventana, tolerando el cruce de medianoche? */
        boolean contiene(int min) {
            return ult >= prim ? (min >= prim && min <= ult) : (min >= prim || min <= ult);
        }
    }

    /** Una ruta de servicio con sus horarios por día/sentido. */
    public static final class Ruta {
        public final int linea;
        public final int[] lineas;      // corredores que toca (mixtas: p. ej. {1,3})
        public final String origen, destino;
        public final boolean mixta;
        public final String variante, nota;   // pueden ser null
        // horarios[dia] = {ida, vuelta}; vuelta puede ser null.
        private final java.util.Map<String, Ventana[]> horarios = new java.util.HashMap<>();

        Ruta(int linea, int[] lineas, String origen, String destino, boolean mixta, String variante, String nota) {
            this.linea = linea; this.lineas = lineas; this.origen = origen; this.destino = destino;
            this.mixta = mixta; this.variante = variante; this.nota = nota;
        }
        boolean tocaLinea(int n) { for (int x : lineas) if (x == n) return true; return linea == n; }
    }

    private static volatile List<Ruta> RUTAS = null;

    private Horarios() {}

    /** Carga perezosa (sincrónica) desde assets. Idempotente. */
    public static synchronized void cargar(Context ctx) {
        if (RUTAS != null) return;
        List<Ruta> lista = new ArrayList<>();
        try (InputStream is = ctx.getAssets().open("horarios.json")) {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String ln; while ((ln = br.readLine()) != null) sb.append(ln);
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("rutas");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int linea = o.optInt("linea", 0);
                JSONArray la = o.optJSONArray("lineas");
                int[] lineas = la != null ? new int[la.length()] : new int[]{linea};
                if (la != null) for (int k = 0; k < la.length(); k++) lineas[k] = la.getInt(k);
                Ruta r = new Ruta(linea, lineas,
                        o.optString("origen", ""), o.optString("destino", ""),
                        o.optBoolean("mixta", false),
                        o.isNull("variante") ? null : o.optString("variante", null),
                        o.isNull("nota") ? null : o.optString("nota", null));
                JSONObject h = o.optJSONObject("horarios");
                if (h != null) {
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String dia = it.next();
                        JSONObject dd = h.getJSONObject(dia);
                        Ventana ida = parseVent(dd.optJSONObject("ida"));
                        Ventana vue = parseVent(dd.optJSONObject("vuelta"));
                        r.horarios.put(dia, new Ventana[]{ida, vue});
                    }
                }
                lista.add(r);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        RUTAS = lista;
    }

    private static Ventana parseVent(JSONObject v) {
        if (v == null) return null;
        int p = min(v.optString("prim", null)), u = min(v.optString("ult", null));
        return (p >= 0 && u >= 0) ? new Ventana(p, u) : null;
    }

    /** "HH:MM" → minutos desde medianoche; -1 si inválido. */
    private static int min(String hhmm) {
        if (hhmm == null) return -1;
        String[] p = hhmm.trim().split(":");
        if (p.length != 2) return -1;
        try { return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Claves de día candidatas (en orden de preferencia) para una fecha. */
    private static String[] clavesDia(Calendar c) {
        switch (c.get(Calendar.DAY_OF_WEEK)) {
            case Calendar.SATURDAY: return new String[]{"S"};
            case Calendar.SUNDAY:   return new String[]{"D"};
            case Calendar.FRIDAY:   return new String[]{"V", "LV", "LJ"};   // viernes ausente hereda Lun-Jue
            default:                return new String[]{"LJ", "LV"};        // Lun-Jue
        }
    }

    /** Ventanas [ida,vuelta] aplicables hoy para una ruta, o null si no circula ese día. */
    private static Ventana[] ventanasHoy(Ruta r, Calendar c) {
        for (String k : clavesDia(c)) {
            Ventana[] v = r.horarios.get(k);
            if (v != null) return v;
        }
        return null;
    }

    public static List<Ruta> deLinea(Context ctx, int linea) {
        cargar(ctx);
        List<Ruta> res = new ArrayList<>();
        for (Ruta r : RUTAS) if (r.tocaLinea(linea)) res.add(r);
        return res;
    }

    /** ¿Existe alguna ruta con horario para este código de línea (p. ej. 124 exprés propio)? */
    public static boolean tieneLinea(Context ctx, int linea) {
        cargar(ctx);
        for (Ruta r : RUTAS) if (r.tocaLinea(linea)) return true;
        return false;
    }

    /** ¿Al menos un servicio de la línea circula en este momento? */
    public static boolean lineaCircula(Context ctx, int linea, Calendar ahora) {
        int m = ahora.get(Calendar.HOUR_OF_DAY) * 60 + ahora.get(Calendar.MINUTE);
        for (Ruta r : deLinea(ctx, linea)) {
            Ventana[] v = ventanasHoy(r, ahora);
            if (v == null) continue;
            if (v[0] != null && v[0].contiene(m)) return true;
            if (v[1] != null && v[1].contiene(m)) return true;
        }
        return false;
    }

    /** ¿La línea tiene ALGÚN horario para hoy (aunque ya haya cerrado)? */
    public static boolean lineaOperaHoy(Context ctx, int linea, Calendar dia) {
        for (Ruta r : deLinea(ctx, linea)) if (ventanasHoy(r, dia) != null) return true;
        return false;
    }

    /** Ventana de servicio de HOY para la línea: [primera salida más temprana, última más tardía]. */
    public static String ventanaHoy(Context ctx, int linea, Calendar dia) {
        int prim = Integer.MAX_VALUE, ult = Integer.MIN_VALUE;
        for (Ruta r : deLinea(ctx, linea)) {
            Ventana[] v = ventanasHoy(r, dia);
            if (v == null) continue;
            for (Ventana w : v) {
                if (w == null) continue;
                prim = Math.min(prim, w.prim);
                // La última que cruza medianoche cuenta como más tardía (+24 h).
                int fin = w.ult >= w.prim ? w.ult : w.ult + 24 * 60;
                ult = Math.max(ult, fin);
            }
        }
        if (prim == Integer.MAX_VALUE) return null;
        return hhmm(prim) + "–" + hhmm(ult % (24 * 60));
    }

    private static String hhmm(int min) {
        int h = (min / 60) % 24, m = min % 60;
        return String.format(Locale.US, "%02d:%02d", h, m);
    }
}
