package com.memegrados.GeoMB;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Catálogo de servicios finos de Mexibús (assets/servicios_mexibus.json): por línea base guarda los
 * servicios reales con su código (TR1/TR3/TR4, Express 1/2/3…), tipo (ordinario/express/mixto), si es
 * unidad Rosa y las terminales de la ruta.
 *
 * De momento solo se usa para NOMBRAR el servicio en los avisos de voz cuando es inequívoco (un único
 * servicio para esa línea+tipo+rosa con código). La SELECCIÓN fina por tramo (TR3 vs TR4, Express 1/2/3,
 * Mixto) requiere la lista de paradas propia de cada servicio: los que la tienen pendiente se marcan con
 * {@code pendiente_estaciones} y no se nombran por código todavía.
 */
public final class ServiciosMexibus {

    public static final class Svc {
        public final int linea;                 // línea base (101-104)
        public final String codigo;             // "TR1", "Express 1"… o null
        public final String tipo;               // "ordinario" | "express" | "mixto"
        public final boolean rosa;
        public final String origen, destino;    // terminales de la ruta (pueden ser null)
        public final boolean pendienteEstaciones;
        public final java.util.List<String> estaciones;   // paradas propias del servicio (o vacío)

        Svc(int linea, String codigo, String tipo, boolean rosa, String origen, String destino,
            boolean pend, java.util.List<String> estaciones) {
            this.linea = linea; this.codigo = codigo; this.tipo = tipo; this.rosa = rosa;
            this.origen = origen; this.destino = destino; this.pendienteEstaciones = pend;
            this.estaciones = estaciones;
        }
    }

    private static volatile List<Svc> LISTA = null;

    private ServiciosMexibus() {}

    public static synchronized void cargar(Context ctx) {
        if (LISTA != null) return;
        List<Svc> lista = new ArrayList<>();
        try (InputStream is = ctx.getAssets().open("servicios_mexibus.json")) {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String ln; while ((ln = br.readLine()) != null) sb.append(ln);
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("servicios");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                java.util.List<String> ests = new ArrayList<>();
                JSONArray ea = o.optJSONArray("estaciones");
                if (ea != null) for (int k = 0; k < ea.length(); k++) ests.add(ea.optString(k));
                lista.add(new Svc(
                        o.optInt("linea", 0),
                        o.isNull("codigo") ? null : o.optString("codigo", null),
                        o.optString("tipo", ""),
                        o.optBoolean("rosa", false),
                        o.isNull("origen") ? null : o.optString("origen", null),
                        o.isNull("destino") ? null : o.optString("destino", null),
                        o.optBoolean("pendiente_estaciones", false),
                        ests));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        LISTA = lista;
    }

    public static List<Svc> deLinea(Context ctx, int base) {
        cargar(ctx);
        List<Svc> res = new ArrayList<>();
        for (Svc s : LISTA) if (s.linea == base) res.add(s);
        return res;
    }

    /**
     * Código del servicio SOLO si es inequívoco: existe exactamente UN servicio para esa
     * línea base + tipo (express/ordinario) + rosa, y ese servicio tiene código y no está pendiente
     * de estaciones. Si hay ambigüedad (varios) o no tiene código, devuelve null (se nombra por tipo).
     */
    public static String codigoUnico(Context ctx, int base, boolean express, boolean rosa) {
        cargar(ctx);
        String tipo = express ? "express" : "ordinario";
        Svc unico = null;
        int n = 0;
        for (Svc s : LISTA) {
            if (s.linea != base || s.rosa != rosa || !tipo.equals(s.tipo)) continue;
            n++;
            unico = s;
        }
        if (n == 1 && unico != null && unico.codigo != null && !unico.codigo.isEmpty()
                && !unico.pendienteEstaciones) {
            return unico.codigo;
        }
        return null;
    }

    /**
     * Código del servicio cuyo patrón de paradas coincide con el TRAMO recorrido (lista de nombres de
     * estación en orden), para esa línea base + tipo + rosa. Solo devuelve código si UNA sola variante
     * contiene el tramo como subsecuencia contigua (en cualquier sentido). null si es ambiguo o ninguno.
     */
    public static String codigoPorTramo(Context ctx, int base, boolean express, boolean rosa,
                                        java.util.List<String> tramo) {
        cargar(ctx);
        if (tramo == null || tramo.size() < 2) return null;
        String tipo = express ? "express" : "ordinario";
        java.util.List<String> tq = new java.util.ArrayList<>();
        for (String s : tramo) tq.add(limpia(s));
        String code = null; int n = 0;
        for (Svc s : LISTA) {
            if (s.linea != base || s.rosa != rosa || !tipo.equals(s.tipo)) continue;
            if (s.codigo == null || s.estaciones == null || s.estaciones.size() < 2) continue;
            java.util.List<String> vq = new java.util.ArrayList<>();
            for (String e : s.estaciones) vq.add(limpia(e));
            java.util.List<String> tqr = new java.util.ArrayList<>(tq);
            java.util.Collections.reverse(tqr);
            if (subsecuencia(vq, tq) || subsecuencia(vq, tqr)) { n++; code = s.codigo; }
        }
        return n == 1 ? code : null;
    }

    /** ¿{@code tq} aparece como subsecuencia CONTIGUA dentro de {@code vq} (casando por nombre limpio)? */
    private static boolean subsecuencia(java.util.List<String> vq, java.util.List<String> tq) {
        if (tq.size() > vq.size()) return false;
        for (int inicio = 0; inicio + tq.size() <= vq.size(); inicio++) {
            boolean todo = true;
            for (int k = 0; k < tq.size(); k++) {
                if (!coincide(vq.get(inicio + k), tq.get(k))) { todo = false; break; }
            }
            if (todo) return true;
        }
        return false;
    }

    private static boolean coincide(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String limpia(String s) {
        return Planificador.norm(Planificador.sinMxb(s == null ? "" : s.replaceAll("\\(.*?\\)", "")));
    }
}
