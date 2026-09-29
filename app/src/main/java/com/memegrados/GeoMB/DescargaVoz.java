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
 * Pre-descarga (Fase 1) los audios base del recorrido de una LÍNEA al MISMO almacenamiento que usa
 * {@link RecorridoService} ({@code Android/data/<paquete>/files/voz/}, nombre
 * {@code hash("Mia|"+texto).mp3} -- NO caché, para que no se borren solos bajo presión de espacio;
 * es almacenamiento externo PROPIO de la app, visible con un explorador de archivos y sin permisos
 * especiales), para que el recorrido los reproduzca offline. Fase 1 = por cada estación: "Llegando
 * a estación: X" y "Próxima estación: X" (los strings exactos del recorrido). Las variantes de
 * correspondencia/conexión son Fase 2.
 */
// ============================================================
// CLASE    : DescargaVoz   (interfaz Progreso)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Pre-descarga (Fase 1) los audios base del recorrido de UNA línea al
// MISMO almacenamiento que usa RecorridoService (Android/data/<paquete>/
// files/voz/, nombre hash("Mia|"+texto).mp3 -- NO caché), para que el
// recorrido se oiga OFFLINE sin que el sistema borre los audios ya
// descargados.
//
// Fase 1 = por cada estación: "Llegando a estación: X" y "Próxima
// estación: X" (los textos EXACTOS del recorrido). Las variantes de
// correspondencia/conexión son Fase 2 (ver Locuciones).
//
// La interfaz Progreso reporta avance/fin para mostrar el porcentaje.
// Descarga de a una (no satura el ancho de banda). Clase de UTILIDAD.
// ============================================================
public final class DescargaVoz {

    public interface Progreso {
        void avance(int hechos, int total);
        void fin(int descargados, int total);
        void error(String msg);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean cancelar = false;
    /** Si se acumulan estos fallos SEGUIDOS, se asume que la red murió a media descarga (o nunca
     *  sirvió) y se aborta el resto -- sin esto, una Wi-Fi "conectada" pero sin internet real (portal
     *  cautivo, router sin servicio) intentaba las CENTENAS de audios uno por uno, cada uno tardando
     *  hasta ~23 s en fallar (los timeouts de descargarUno), sintiéndose "trabada" por mucho tiempo. */
    private static final int FALLOS_SEGUIDOS_ABORTAR = 6;
    /** Mismos consejos de seguridad que RecorridoService.tipAleatorio() (sin el "rosa", que solo
     *  aplica a Mexibús -- ver textosLinea()), para predescargar TODAS las variantes posibles. */
    private static final int[] TIPS_BASE = {R.string.voz_tip_espacios, R.string.voz_tip_objetos, R.string.voz_tip_correr};

    private DescargaVoz() {}

    public static void cancelar() { cancelar = true; }

    /** Todos los textos (exactos) de una línea: base por estación (Fase 1) + variantes de
     *  correspondencia/terminal (Fase 2), replicadas vía {@link Locuciones}. */
    public static List<String> textosLinea(Context ctx, int linea) {
        Set<String> t = new LinkedHashSet<>();   // sin duplicar, en orden
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return new ArrayList<>(t);
        String prep = ctx.getString(R.string.voz_transbordo_prep);
        // Mexicable (teleférico): se viaja en CABINA, no "unidad" (debe coincidir con RecorridoService).
        String ult  = ctx.getString(linea >= 200 ? R.string.voz_ultima_est_cabina : R.string.voz_ultima_est);
        int[] palabras = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion};
        for (Estacion e : l.estaciones) {
            if (e.soloMapa) continue;
            String nom = nomDe(e.nombre);
            if (nom == null || nom.isEmpty()) continue;
            com.google.android.gms.maps.model.LatLng pos = e.posicion;
            // Fase 1: avisos base. "Llegando a estación: X" real casi nunca suena a secas -- ver
            // RecorridoService.vozLlegada(): si es la última parada del VIAJE (que puede ser
            // cualquier estación, no solo una terminal de línea) se le añade "última estación de tu
            // recorrido", y si no, tipAleatorio() le añade un consejo de seguridad al azar la mitad
            // de las veces. Sin predescargar esas combinaciones, casi CUALQUIER llegada sin
            // correspondencia terminaba en TTS por defecto, no solo las que sí son
            // correspondencia/conexión/transbordo (esas se aceptan con TTS por lo combinatorio).
            String llegandoBase = ctx.getString(R.string.voz_llegando_est, nom);
            t.add(llegandoBase);
            t.add(llegandoBase + ". " + ult);
            for (int tipRes : TIPS_BASE) t.add(llegandoBase + ". " + ctx.getString(tipRes));
            if (linea >= 100 && linea < 200) t.add(llegandoBase + ". " + ctx.getString(R.string.voz_tip_rosa));
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
            // Falla RÁPIDO si la red activa no tiene internet real (Wi-Fi sin servicio, portal
            // cautivo, avión, etc.), en vez de intentar las centenas de audios uno por uno.
            if (!Red.hayInternetReal(app)) {
                MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                return;
            }
            Set<String> set = new LinkedHashSet<>();
            for (int ln : lineas) set.addAll(textosLinea(app, ln));   // unión sin duplicar
            List<String> textos = new ArrayList<>(set);
            int total = textos.size(), hechos = 0, ok = 0, fallosSeguidos = 0;
            for (String t : textos) {
                if (cancelar) break;
                boolean bien = false;
                try { bien = descargarUno(app, t); } catch (Exception ignore) {}
                if (bien) { ok++; fallosSeguidos = 0; }
                else if (++fallosSeguidos >= FALLOS_SEGUIDOS_ABORTAR) {
                    MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                    return;
                }
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

    /** Borra TODOS los audios descargados (toda la carpeta de voz). Devuelve cuántos borró. */
    public static int borrarTodo(Context ctx) {
        int n = 0;
        File dir = new File(ctx.getFilesDir(), "voz");
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

    // --- MISMO esquema que RecorridoService.archivoVoz (para que coincidan los hashes y la carpeta).
    // Almacenamiento EXTERNO propio de la app (Android/data/<paquete>/files/voz/, NO getCacheDir()):
    // el sistema nunca lo borra bajo presión de espacio (solo al desinstalar la app), y a diferencia
    // de getFilesDir() sí es accesible con un explorador de archivos, sin permisos especiales. Si no
    // hay almacenamiento externo disponible, cae al interno para no perder la función. ---
    private static File archivoVoz(Context ctx, String texto) {
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            File dir = new File(base, "voz");
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
        return respellVoz(par >= 0 ? s.substring(0, par).trim() : s);
    }

    /** Corrige la ortografía SOLO para la voz de siglas/palabras que el TTS lee mal (acrónimos sin vocales
     *  que pronunciar, o palabras en inglés leídas con reglas fonéticas del español). Debe mantenerse en
     *  sincronía con RecorridoService.nom() y Locuciones.nom(). */
    private static String respellVoz(String nombre) {
        if (nombre == null) return null;
        return nombre
                .replace("ISSEMYM", "Isemim")
                .replace("CCH", "Cecehache")
                .replace("SCOP", "Escop")
                .replace("New's Divine", "Niuz Diváin");
    }
}
