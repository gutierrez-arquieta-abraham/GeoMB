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
 * {@link RecorridoService} ({@code Android/data/<paquete>/files/voz/Mia/<sistema>/L<línea>/}, nombre
 * {@code hash(texto).mp3} -- NO caché, para que no se borren solos bajo presión de espacio; es
 * almacenamiento externo PROPIO de la app, visible con un explorador de archivos y sin permisos
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
// files/voz/Mia/<sistema>/L<línea>/, nombre hash(texto).mp3 -- NO caché),
// para que el recorrido se oiga OFFLINE sin que el sistema borre los
// audios ya descargados.
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

    /** Descarga los audios de UNA línea, con la voz "Mia" (ver {@link #descargarLinea(Context, int,
     *  String, Progreso)} para elegir otra). */
    public static void descargarLinea(Context ctx, int linea, Progreso cb) {
        descargarLinea(ctx, linea, "Mia", cb);
    }

    /** Descarga los audios de UNA línea con la voz indicada (p. ej. "Ximena" si ese sistema tiene
     *  el modo experimental activo -- ver ConfiguracionFragment.menuAudios()). */
    public static void descargarLinea(Context ctx, int linea, String voz, Progreso cb) {
        descargarVarias(ctx, java.util.Collections.singletonList(linea), voz, cb);
    }

    /** Descarga los audios de VARIAS líneas (para "descargar todas") con la voz "Mia" (ver el
     *  overload con `voz` para elegir otra). */
    public static void descargarVarias(Context ctx, List<Integer> lineas, Progreso cb) {
        descargarVarias(ctx, lineas, "Mia", cb);
    }

    /** Descarga los audios de VARIAS líneas con la voz indicada, en segundo plano. Por cada línea en
     *  SU PROPIA carpeta (voz/<voz>/<sistema>/L<línea>/): si el mismo texto se repite entre 2 líneas
     *  (p. ej. mismo nombre de estación), se descarga una vez por cada una -- la carpeta de cada
     *  línea queda completa por sí sola, en vez de depender de qué otras líneas ya bajaste. */
    public static void descargarVarias(Context ctx, List<Integer> lineas, String voz, Progreso cb) {
        final Context app = ctx.getApplicationContext();
        cancelar = false;
        EXEC.execute(() -> {
            // Falla RÁPIDO si la red activa no tiene internet real (Wi-Fi sin servicio, portal
            // cautivo, avión, etc.), en vez de intentar las centenas de audios uno por uno.
            if (!Red.hayInternetReal(app)) {
                MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                return;
            }
            List<String> textosPorPendiente = new ArrayList<>();
            List<Integer> lineaPorPendiente = new ArrayList<>();
            for (int ln : lineas) for (String t : textosLinea(app, ln)) {
                textosPorPendiente.add(t);
                lineaPorPendiente.add(ln);
            }
            int total = textosPorPendiente.size(), hechos = 0, ok = 0, fallosSeguidos = 0;
            for (int i = 0; i < total; i++) {
                if (cancelar) break;
                String t = textosPorPendiente.get(i);
                int ln = lineaPorPendiente.get(i);
                boolean bien = false;
                try { bien = descargarUno(app, t, ln, voz); } catch (Exception ignore) {}
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
    private static boolean descargarUno(Context ctx, String texto, int linea, String voz) throws java.io.IOException {
        File out = archivoVoz(ctx, texto, linea, voz);
        if (out == null) return false;
        if (out.exists() && out.length() > 0) return true;   // ya cacheado
        URL url = new URL("https://geomb.duckdns.org/api/tts?voz=" + voz + "&texto="
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

    /** Voces que puede haber descargado la pre-descarga masiva (ver menuAudios(): "Mia" siempre
     *  disponible; "Ximena" solo si el usuario la elige cuando el sistema de esa línea tiene el
     *  modo experimental activo) -- borrarLinea()/cuantosDescargados() revisan AMBAS, porque la
     *  misma línea pudo haberse descargado primero en una y después en la otra. */
    private static final String[] VOCES_CONOCIDAS = {"Mia", "Ximena"};

    /** Borra los audios (Fase 1) de una línea, en CUALQUIERA de las voces que se hayan descargado. */
    public static int borrarLinea(Context ctx, int linea) {
        int n = 0;
        for (String t : textosLinea(ctx, linea)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoVoz(ctx, t, linea, voz);
                if (f != null && f.exists() && f.delete()) n++;
            }
        }
        return n;
    }

    /** Borra TODOS los audios descargados (toda la carpeta voz/, recursivo: ya tiene subcarpetas
     *  <voz>/<sistema>/<línea>/). Devuelve cuántos ARCHIVOS borró (no cuenta carpetas). */
    public static int borrarTodo(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return borrarRecursivo(new File(base, "voz"));
    }

    private static int borrarRecursivo(File dir) {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) n += borrarRecursivo(f);
            else if (f.delete()) n++;
        }
        return n;
    }

    /** ¿Cuántos audios de la línea ya están descargados, en CUALQUIERA de las voces? (para mostrar
     *  estado). Si se descargó en las 2 voces, cuenta ambas (coherente con borrarLinea()). */
    public static int cuantosDescargados(Context ctx, int linea) {
        int n = 0;
        for (String t : textosLinea(ctx, linea)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoVoz(ctx, t, linea, voz);
                if (f != null && f.exists() && f.length() > 0) n++;
            }
        }
        return n;
    }

    // --- MISMO esquema que RecorridoService.archivoVoz (para que coincidan los hashes y la carpeta).
    // Almacenamiento EXTERNO propio de la app (Android/data/<paquete>/files/voz/, NO getCacheDir()):
    // el sistema nunca lo borra bajo presión de espacio (solo al desinstalar la app), y a diferencia
    // de getFilesDir() sí es accesible con un explorador de archivos, sin permisos especiales. Si no
    // hay almacenamiento externo disponible, cae al interno para no perder la función.
    // Subcarpetas voz/<voz>/<sistema>/L<línea>/ -- navegables/filtrables a mano (ver Sistemas.carpeta()/
    // numeroDeLinea()). Por DEFAULT la pre-descarga masiva sigue siendo "Mia" (ver los overloads sin
    // `voz` de descargarLinea()/descargarVarias()): esta es una descarga de TODAS las estaciones de
    // una línea de un golpe, mientras que RecorridoService.decirConVoz() solo pide UNA frase a la vez
    // según el recorrido real -- el costo ahí queda acotado por cuánto de verdad viajas. Por eso
    // ConfiguracionFragment solo ofrece elegir "Ximena" aquí cuando el sistema de esa línea YA tiene
    // su propio switch activo (Modos.vozXimenaMetrobus()/Mexibus()/Mexicable()) -- nunca por default. ---
    private static File archivoVoz(Context ctx, String texto, int linea, String voz) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "voz/" + voz + "/" + Sistemas.carpeta(Sistemas.sistemaDe(linea))
                + "/L" + Sistemas.numeroDeLinea(linea));
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Integer.toHexString(texto.hashCode()) + ".mp3");
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
                .replace("ISSEMYM", "I-semim")        // "Isemim" (sin guión) sonaba "esemim": se perdía la I inicial
                .replace("CCH", "Cecehache")
                .replace("SCOP", "Escop")
                .replace("New's Divine", "Niuz Diváin")
                .replace("1ro de Mayo", "Primero de Mayo")   // "uno Ro" leído literal -- L1/L1 Exprés
                .replace("1° de Mayo", "Primero de Mayo")    // mismo caso, L2
                .replace(" - ", ", ");   // "Bandera - Tultitlán", "Héroes - Canosas", "Lindavista - Vallejo"
    }
}
