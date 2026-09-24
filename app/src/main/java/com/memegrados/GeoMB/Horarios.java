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
// ============================================================
// CLASE    : Horarios   (subclases Ventana y Ruta)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Horarios de servicio del Metrobús (assets/horarios.json). Por cada
// RUTA guarda primera/última salida por tipo de día (LJ, V, S, D, LV)
// y por sentido (ida = origen→destino, vuelta = destino→origen).
//
// SIRVE PARA:
//   - mostrar la ventana de servicio de hoy por línea/ruta, y
//   - validar en el planificador si una línea aún circula a esta hora.
//
// REGLA: si un viernes no tiene su propio bloque, hereda el de Lun-Jue.
//
// SUBCLASES:
//   - Ventana : rango [prim, ult] en minutos desde medianoche; si ult<prim
//               la ventana cruza medianoche (contiene() lo tolera).
//   - Ruta    : una ruta de servicio con sus horarios por día/sentido.
// ============================================================
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

    // ---------------------------------------------------------------- terminal por dirección
    // (compartido por RecorridoService -- voz -- y Planificador -- chip "Aborda · Dirección").

    /** Alias de nombres de estación para casar horarios ↔ datos de línea (siglas/abreviaturas). */
    private static String aliasEst(String q) {
        if (q.equals("ipn") || q.equals("i p n") || q.contains("politecnico")) return "instituto politecnico nacional";
        if (q.contains("18 de marzo") || q.contains("18 mzo") || q.contains("18 mar")) return "dep 18 mzo";
        return q;
    }

    /** Índice en la línea de la estación cuyo nombre (normalizado, sin MXB, con alias) coincide con el de
     *  la parada. Aplica alias para siglas comunes de horarios (p. ej. "IPN" → Instituto Politécnico). */
    public static int idxEnLinea(Linea l, String nombreParada) {
        String q = aliasEst(Planificador.norm(Planificador.sinMxb(nombreParada)));
        for (int k = 0; k < l.estaciones.size(); k++)
            if (aliasEst(Planificador.norm(Planificador.sinMxb(l.estaciones.get(k).nombre))).equals(q)) return k;
        for (int k = 0; k < l.estaciones.size(); k++) {
            String nn = aliasEst(Planificador.norm(Planificador.sinMxb(l.estaciones.get(k).nombre)));
            if (!nn.isEmpty() && !q.isEmpty() && (nn.contains(q) || q.contains(nn))) return k;
        }
        return -1;
    }

    /** Nombre para mostrar/hablar desde un String de estación (sin "MXB " ni el paréntesis de conexión). */
    public static String nomNombre(String nombre) {
        if (nombre == null) return "";
        String s = Planificador.sinMxb(nombre);
        int par = s.indexOf('(');
        return par >= 0 ? s.substring(0, par).trim() : s;
    }

    /** ¿La ruta de horarios sigue circulando (en ida o vuelta) en el momento dado? Filtra los
     *  patrones ya cerrados (p. ej. una vuelta corta que dejó de circular) de {@link #terminalHorario},
     *  para que este devuelva la RUTA COMPLETA (o el que siga siendo) en vez de uno ya fuera de horario. */
    private static boolean rutaAbierta(Ruta r, Calendar momento) {
        Ventana[] v = ventanasHoy(r, momento);
        if (v == null) return false;
        int m = momento.get(Calendar.HOUR_OF_DAY) * 60 + momento.get(Calendar.MINUTE);
        return (v[0] != null && v[0].contiene(m)) || (v[1] != null && v[1].contiene(m));
    }

    /** Rango [lo,hi] (índices en {@code l}) que cubre la ruta {@code r}, o null si ninguno de sus
     *  extremos casa con una estación de esta línea (compartido por terminalHorario/estacionAbierta). */
    private static int[] rangoDe(Linea l, Ruta r, int last) {
        int oi = idxEnLinea(l, r.origen), di = idxEnLinea(l, r.destino);
        if (oi < 0 && di < 0) return null;
        if (oi < 0) oi = (di <= last - di) ? last : 0;   // extremo mixto (fuera de la línea): al extremo opuesto
        if (di < 0) di = (oi <= last - oi) ? last : 0;
        return new int[]{Math.min(oi, di), Math.max(oi, di)};
    }

    /**
     * ¿Sigue circulando, en {@code momento}, ALGÚN patrón de la línea que cubra ESTA estación en
     * particular? A diferencia de {@link #lineaCircula} (que basta con que CUALQUIER ruta de la línea
     * siga abierta -- correcto para el aviso general "Servicio hoy", y para líneas troncales lineales
     * donde cada patrón es solo un tramo del mismo corredor), esto es necesario para decidir si se
     * puede ABORDAR esa línea en ESA estación exacta: p. ej. Metrobús L4 agrupa bajo el mismo número
     * patrones con recorridos FÍSICAMENTE DISTINTOS (Ruta Norte, Ruta Sur, Aeropuerto–Amajac, Terminal
     * 1–Terminal 2) -- que la lanzadera del aeropuerto siga circulando no significa que también lo haga
     * la Ruta Norte en una estación que solo ella sirve. Si NINGÚN patrón documentado cubre esa
     * estación (sin datos), se asume permisivo (true), igual que el resto de esta clase.
     */
    public static boolean estacionAbierta(Context ctx, int linea, Linea l, String nombreEstacion, Calendar momento) {
        if (l == null) return lineaCircula(ctx, linea, momento);
        int idx = idxEnLinea(l, nombreEstacion);
        if (idx < 0) return lineaCircula(ctx, linea, momento);
        int last = l.estaciones.size() - 1;
        boolean algunaCubre = false;
        for (Ruta r : deLinea(ctx, linea)) {
            int[] rango = rangoDe(l, r, last);
            if (rango == null || idx < rango[0] || idx > rango[1]) continue;
            algunaCubre = true;
            if (rutaAbierta(r, momento)) return true;
        }
        return !algunaCubre;   // ningún patrón documentado cubre esta estación: permisivo
    }

    /** Como {@link #terminalHorario(Context, int, Linea, int, int, Calendar)}, evaluando "ahora". */
    public static String terminalHorario(Context ctx, int base, Linea l, int ia, int ib) {
        return terminalHorario(ctx, base, l, ia, ib, Calendar.getInstance());
    }

    /**
     * Terminal(es) de dirección para tu parada, según las rutas de horarios. Recolecta las rutas que
     * CUBREN tu parada (ia) Y siguen circulando en {@code momento} (una vuelta corta ya cerrada por hoy
     * no cuenta, así de noche esto cae en la RUTA COMPLETA que siga abierta), y toma, de cada una, su
     * extremo en tu sentido de viaje (por índice; no se asume que {@code destino} sea el extremo mayor).
     * Devuelve las terminales DISTINTAS de la más cercana a la más lejana, unidas con " o " (p. ej.
     * "Instituto Politécnico Nacional o El Rosario"). Si MÁS DE 3 rutas cubren la parada, devuelve solo
     * la más cercana. null si ninguna aplica (incluye: todas las que cubren la parada ya cerraron).
     *
     * Así una vuelta corta documentada (p. ej. L3 Tenayuca↔La Raza) sale como el destino real de la
     * unidad en vez del extremo absoluto de la troncal (Pueblo Sta. Cruz Atoyac), tanto en la voz del
     * recorrido (RecorridoService, con {@code momento} = ahora) como en el chip "Aborda · Dirección" del
     * Planificador (con {@code momento} = la hora estimada en que de verdad abordarías ese tramo).
     */
    public static String terminalHorario(Context ctx, int base, Linea l, int ia, int ib, Calendar momento) {
        boolean forward = ib > ia;               // hacia el extremo de índice MAYOR de la línea
        int last = l.estaciones.size() - 1;
        List<Integer> idxs = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        int rutasCubren = 0;
        for (Ruta r : deLinea(ctx, base)) {
            int oi = idxEnLinea(l, r.origen), di = idxEnLinea(l, r.destino);
            if (oi < 0 && di < 0) continue;
            if (oi < 0) oi = (di <= last - di) ? last : 0;   // extremo mixto (fuera de la línea): al extremo opuesto
            if (di < 0) di = (oi <= last - oi) ? last : 0;
            int lo = Math.min(oi, di), hi = Math.max(oi, di);
            if (ia < lo || ia > hi) continue;                // esta ruta no cubre tu parada
            int termIdx = forward ? hi : lo;                 // extremo de la ruta en tu sentido
            // Si el extremo EN TU SENTIDO es tu propia parada (ia), esta ruta no te sirve de nada para
            // saber hacia dónde ir: es un patrón corto cuyo límite coincide con donde ya estás (p. ej.
            // "Buenavista → El Caminero" cubre [8,45] y tú abordas justo en el 8 yendo HACIA ABAJO, fuera
            // de ese rango) -- mostrar tu propia estación como "dirección" no tiene sentido. Se descarta.
            if (termIdx == ia) continue;
            if (!rutaAbierta(r, momento)) continue;          // este patrón ya cerró por hoy: no lo propongas
            rutasCubren++;
            // Nombre REAL de la estación (l.estaciones), nunca el crudo de horarios.json: origen/destino
            // ahí puede venir abreviado (p. ej. "Dep. 18 de marzo", "IPN") porque solo sirve para
            // IDENTIFICAR el extremo por alias (idxEnLinea) -- una vez resuelto el índice, se muestra
            // siempre el nombre completo tal como lo tiene la línea (p. ej. "Deportivo 18 de Marzo").
            String nombre = nomNombre(l.estaciones.get(termIdx).nombre);
            if (!idxs.contains(termIdx)) { idxs.add(termIdx); nombres.add(nombre); }
        }
        if (idxs.isEmpty()) return null;
        // ordena por cercanía a tu parada (ia)
        for (int a = 0; a < idxs.size(); a++)
            for (int b = a + 1; b < idxs.size(); b++)
                if (Math.abs(idxs.get(b) - ia) < Math.abs(idxs.get(a) - ia)) {
                    int ti = idxs.get(a); idxs.set(a, idxs.get(b)); idxs.set(b, ti);
                    String tn = nombres.get(a); nombres.set(a, nombres.get(b)); nombres.set(b, tn);
                }
        if (rutasCubren > 3) return nombres.get(0);          // demasiadas rutas: solo la más cercana
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nombres.size(); i++) sb.append(i > 0 ? " o " : "").append(nombres.get(i));
        return sb.toString();
    }
}
