package com.memegrados.GeoMB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pre-descarga (Fase 1) los audios base del recorrido de una LÍNEA a la MISMA caché que usa
 * {@link RecorridoService} ({@code getCacheDir()/voz/}, nombre {@code hash("Mia|"+texto).mp3}),
 * para que el recorrido los reproduzca offline. Fase 1 = por cada estación: "Llegando a
 * estación: X" y "Próxima estación: X" (los strings exactos del recorrido). Las variantes de
 * correspondencia/conexión son Fase 2.
 */
public final class DescargaVoz {

    public interface Progreso {
        void avance(int hechos, int total);
        void fin(int descargados, int total);
        void error(String msg);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean cancelar = false;

    private DescargaVoz() {}

    public static void cancelar() { cancelar = true; }

    /** Todos los textos (exactos) de una línea: base por estación (Fase 1) + variantes de
     *  correspondencia/terminal (Fase 2), replicadas vía {@link Locuciones}. */
    public static List<String> textosLinea(Context ctx, int linea) {
        Set<String> t = new LinkedHashSet<>();   // sin duplicar, en orden
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return new ArrayList<>(t);
        String prep = ctx.getString(R.string.voz_transbordo_prep);
        String ult  = ctx.getString(R.string.voz_ultima_est);
        int[] palabras = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion};
        for (Estacion e : l.estaciones) {
            if (e.soloMapa) continue;
            String nom = nomDe(e.nombre);
            if (nom == null || nom.isEmpty()) continue;
            com.google.android.gms.maps.model.LatLng pos = e.posicion;
            // Fase 1: avisos base
            t.add(ctx.getString(R.string.voz_llegando_est, nom));
            t.add(ctx.getString(R.string.voz_proxima, nom));
            // Fase 2: terminal o correspondencia
            if (Locuciones.esTerminal(linea, e.nombre)) {
                String tl = Locuciones.vozTerminal(ctx, linea, e.nombre, pos, true);   // llegada
                String tp = Locuciones.vozTerminal(ctx, linea, e.nombre, pos, false);  // próxima
                t.add(tl); t.add(tp); t.add(tl + ". " + ult);
            } else {
                String tf = Locuciones.transferenciaTexto(ctx, linea,
                        Locuciones.basesCorresp(ctx, linea, e.nombre, pos));
                if (!tf.isEmpty()) {
                    String px = ctx.getString(R.string.voz_prox_base, nom) + tf;
                    t.add(px);
                    t.add(px + ". " + prep);
                    String lg = ctx.getString(R.string.voz_lleg_base, nom) + tf;
                    t.add(lg);
                    t.add(lg + ". " + ult);
                    for (int pal : palabras)
                        t.add(lg + ". " + ctx.getString(R.string.voz_baja_realiza, ctx.getString(pal)));
                }
            }
        }
        return new ArrayList<>(t);
    }

    /** Descarga los audios de UNA línea. */
    public static void descargarLinea(Context ctx, int linea, Progreso cb) {
        descargarVarias(ctx, java.util.Collections.singletonList(linea), cb);
    }

    /** Descarga los audios de VARIAS líneas (para "descargar todas"), en segundo plano. */
    public static void descargarVarias(Context ctx, List<Integer> lineas, Progreso cb) {
        final Context app = ctx.getApplicationContext();
        cancelar = false;
        EXEC.execute(() -> {
            Set<String> set = new LinkedHashSet<>();
            for (int ln : lineas) set.addAll(textosLinea(app, ln));   // unión sin duplicar
            List<String> textos = new ArrayList<>(set);
            int total = textos.size(), hechos = 0, ok = 0;
            for (String t : textos) {
                if (cancelar) break;
                try { if (descargarUno(app, t)) ok++; } catch (Exception ignore) {}
                hechos++;
                final int h = hechos;
                MAIN.post(() -> { if (cb != null) cb.avance(h, total); });
            }
            final int okF = ok, totF = total;
            MAIN.post(() -> { if (cb != null) cb.fin(okF, totF); });
        });
    }

    /** Baja un texto a la caché de voz (si no está ya). Devuelve true si quedó el archivo. */
    private static boolean descargarUno(Context ctx, String texto) throws Exception {
        File out = archivoVoz(ctx, texto);
        if (out == null) return false;
        if (out.exists() && out.length() > 0) return true;   // ya cacheado
        URL url = new URL("https://geomb.duckdns.org/api/tts?voz=Mia&texto="
                + android.net.Uri.encode(texto));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "audio/mpeg");
        try {
            if (c.getResponseCode() / 100 != 2) return false;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            if (out.length() == 0) { out.delete(); return false; }
            return true;
        } finally {
            c.disconnect();
        }
    }

    /** Borra los audios (Fase 1) de una línea. */
    public static int borrarLinea(Context ctx, int linea) {
        int n = 0;
        for (String t : textosLinea(ctx, linea)) {
            File f = archivoVoz(ctx, t);
            if (f != null && f.exists() && f.delete()) n++;
        }
        return n;
    }

    /** Borra TODOS los audios cacheados (toda la carpeta de voz). Devuelve cuántos borró. */
    public static int borrarTodo(Context ctx) {
        int n = 0;
        File dir = new File(ctx.getCacheDir(), "voz");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.delete()) n++;
        return n;
    }

    /** ¿Cuántos audios de la línea ya están descargados? (para mostrar estado). */
    public static int cuantosDescargados(Context ctx, int linea) {
        int n = 0;
        for (String t : textosLinea(ctx, linea)) {
            File f = archivoVoz(ctx, t);
            if (f != null && f.exists() && f.length() > 0) n++;
        }
        return n;
    }

    // --- MISMO esquema de caché que RecorridoService.archivoVoz (para que coincidan los hashes) ---
    private static File archivoVoz(Context ctx, String texto) {
        try {
            File dir = new File(ctx.getCacheDir(), "voz");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, Integer.toHexString(("Mia|" + texto).hashCode()) + ".mp3");
        } catch (Exception e) {
            return null;
        }
    }

    /** Réplica exacta de RecorridoService.nom(): sin 'MXB ' y cortando en '('. */
    private static String nomDe(String nombre) {
        if (nombre == null) return null;
        String s = Planificador.sinMxb(nombre);
        int par = s.indexOf('(');
        return par >= 0 ? s.substring(0, par).trim() : s;
    }
}
