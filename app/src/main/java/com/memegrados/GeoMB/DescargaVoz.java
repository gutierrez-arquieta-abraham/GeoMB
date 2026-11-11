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
 * {@link RecorridoService} ({@code Android/data/<paquete>/files/voz/Mia/<sistema>/L<línea>/<estación>/},
 * nombre {@code hash(texto).mp3} -- NO caché, para que no se borren solos bajo presión de espacio; es
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
// files/voz/Mia/<sistema>/L<línea>/<estación>/, nombre hash(texto).mp3 --
// NO caché), para que el recorrido se oiga OFFLINE sin que el sistema
// borre los audios ya descargados.
//
// Fase 1 = por cada estación: "Llegando a estación: X" y "Próxima
// estación: X" (los textos EXACTOS del recorrido). Las variantes de
// correspondencia/conexión son Fase 2 (ver Locuciones).
//
// Se baja en PAQUETES .zip (POST /api/tts/paquete del EC2, hasta 300 frases
// por petición) desde la caché permanente del servidor: cada frase se
// sintetiza con Polly/Fish Audio una sola vez para todos los dispositivos.
// Si el servidor no tiene ese endpoint, cae a una petición por frase.
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

    /** Conectores FIJOS (sin ningún %s) que usan los avisos ya migrados a Fragmento (ver
     *  RecorridoService.avisoAbordajeFragmentos()/vozProxima()/vozLlegada()) -- reproducirlos aquí
     *  con ctx.getString(id) a secas es IDÉNTICO byte a byte a lo que arma RecorridoService en vivo
     *  (mismo recurso, cero parámetros), así que no hay riesgo de que el hash no coincida. */
    private static final int[] CONECTORES_FIJOS = {
            R.string.voz_proxima_prefijo, R.string.voz_proxima_ximena_prefijo,
            R.string.voz_llegando_est_prefijo, R.string.voz_llegando_est_ximena_prefijo,
            R.string.voz_aborda_prefijo, R.string.voz_aborda_cabina_prefijo,
            R.string.voz_tip_espacios, R.string.voz_tip_objetos, R.string.voz_tip_correr, R.string.voz_tip_rosa,
            R.string.voz_ultima_est, R.string.voz_ultima_est_cabina,
    };

    /** Tope de "N estaciones" a predescargar (catálogo COMUN, ver Fragmento) -- la línea más larga
     *  hoy tiene 51 paradas; se deja margen. Un viaje real que necesitara más caería al TTS en vivo
     *  la primera vez -- no rompe nada, solo no queda predescargado. */
    private static final int MAX_N_ESTACIONES = 60;

    private DescargaVoz() {}

    public static void cancelar() { cancelar = true; }

    /** Un texto a pre-descargar junto con la ESTACIÓN a la que pertenece (ver la carpeta
     *  voz/<voz>/<sistema>/L<línea>/<estación>/ en archivoVoz() -- mismo esquema que
     *  RecorridoService.archivoVoz()/carpetaEstacion()). */
    static final class TextoEstacion {
        final String texto, estacion;
        TextoEstacion(String texto, String estacion) { this.texto = texto; this.estacion = estacion; }
    }

    /** Todos los textos (exactos) de una línea, cada uno con su estación: base por estación (Fase 1)
     *  + variantes de correspondencia/terminal (Fase 2), replicadas vía {@link Locuciones}. */
    public static List<TextoEstacion> textosLinea(Context ctx, int linea) {
        // Set por (estación+texto): en la práctica los nombres de estación ya son únicos dentro de
        // una línea, así que esto rara vez deduplica algo real -- pero es la clave CORRECTA ahora que
        // la carpeta de cada texto depende de su estación (antes deduplicaba por texto a secas, sin
        // esa dependencia).
        Set<String> vistos = new LinkedHashSet<>();
        List<TextoEstacion> t = new ArrayList<>();
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return t;
        // Preparación con la palabra de cada tipo de cambio (debe coincidir con RecorridoService.prepTransferencia()).
        java.util.Map<Integer, String> prepPor = new java.util.HashMap<>();
        for (int pal : new int[]{R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion})
            prepPor.put(pal, ctx.getString(R.string.voz_prep_transferencia, ctx.getString(pal)));
        // Mexicable (teleférico): se viaja en CABINA, no "unidad" (debe coincidir con RecorridoService).
        String ult  = ctx.getString(linea >= 200 ? R.string.voz_ultima_est_cabina : R.string.voz_ultima_est);
        // Solo transbordo/correspondencia: su instrucción de bajada es genérica (no nombra destino),
        // ver abajo. "conexión" (Metrobús↔Edomex) NO entra aquí -- su instrucción real SÍ nombra la
        // estación+línea de destino (RecorridoService.instruccionBajada()/nombreConexion()), así que se
        // genera aparte, una vez por cada línea de conexión real de la estación (ver más abajo).
        int[] palabras = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia};
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
            agregar(t, vistos, nom, llegandoBase);
            agregar(t, vistos, nom, llegandoBase + ". " + ult);
            for (int tipRes : TIPS_BASE) agregar(t, vistos, nom, llegandoBase + ". " + ctx.getString(tipRes));
            if (linea >= 100 && linea < 200) agregar(t, vistos, nom, llegandoBase + ". " + ctx.getString(R.string.voz_tip_rosa));
            agregar(t, vistos, nom, ctx.getString(R.string.voz_proxima, nom));
            // Fase 2: terminal o correspondencia
            if (Locuciones.esTerminal(linea, e.nombre)) {
                String tl = Locuciones.vozTerminal(ctx, linea, e.nombre, pos, true);   // llegada
                String tp = Locuciones.vozTerminal(ctx, linea, e.nombre, pos, false);  // próxima
                agregar(t, vistos, nom, tl); agregar(t, vistos, nom, tp); agregar(t, vistos, nom, tl + ". " + ult);
                // Terminal donde se cambia de línea: "Próxima estación terminal…" + preparación, y la
                // llegada + qué hacer (como RecorridoService.vozProxima()/vozLlegada()).
                for (int b : Locuciones.basesCorresp(ctx, linea, e.nombre, pos)) {
                    int pal = Locuciones.palabraTransferencia(linea, b);
                    agregar(t, vistos, nom, tp + ". " + prepPor.get(pal));
                    agregar(t, vistos, nom, tl + ". " + (pal == R.string.voz_palabra_conexion
                            ? ctx.getString(R.string.voz_camina_conexion, Locuciones.nombreConexion(ctx, b, e.nombre))
                            : ctx.getString(R.string.voz_baja_realiza, ctx.getString(pal))));
                }
            } else {
                java.util.TreeSet<Integer> bases = Locuciones.basesCorresp(ctx, linea, e.nombre, pos);
                String tf = Locuciones.transferenciaTexto(ctx, linea, bases);
                if (!tf.isEmpty()) {
                    String px = ctx.getString(R.string.voz_prox_base, nom) + tf;
                    agregar(t, vistos, nom, px);
                    for (int b : bases)   // una variante por cada palabra real de la estación
                        agregar(t, vistos, nom, px + ". " + prepPor.get(Locuciones.palabraTransferencia(linea, b)));
                    String lg = ctx.getString(R.string.voz_lleg_base, nom) + tf;
                    agregar(t, vistos, nom, lg);
                    agregar(t, vistos, nom, lg + ". " + ult);
                    for (int pal : palabras)
                        agregar(t, vistos, nom, lg + ". " + ctx.getString(R.string.voz_baja_realiza, ctx.getString(pal)));
                    // "conexión" (Metrobús↔Edomex): una variante por cada línea de conexión real de la
                    // estación, con el texto EXACTO que usa RecorridoService.instruccionBajada() (nombra
                    // la estación+línea de destino, no el genérico de arriba).
                    for (int b : bases) {
                        if (Locuciones.palabraTransferencia(linea, b) != R.string.voz_palabra_conexion) continue;
                        agregar(t, vistos, nom, lg + ". " + ctx.getString(R.string.voz_camina_conexion,
                                Locuciones.nombreConexion(ctx, b, e.nombre)));
                    }
                }
            }
        }
        return t;
    }

    /** Agrega (estacion, texto) a la lista si ese PAR no se había visto (dedup -- ver su comentario
     *  en textosLinea()). */
    private static void agregar(List<TextoEstacion> t, Set<String> vistos, String estacion, String texto) {
        if (vistos.add(estacion + "\u0000" + texto)) t.add(new TextoEstacion(texto, estacion));
    }

    /** Fragmentos GLOBALES que no dependen de la línea: conectores fijos, las 3 variantes de
     *  preparación de transferencia (transbordo/correspondencia/conexión -- también deterministas,
     *  mismo criterio que CONECTORES_FIJOS) y el catálogo de "N estaciones". Se piden UNA sola vez
     *  por voz real distinta en el lote (ver descargarVarias()), sin importar cuántas líneas se
     *  estén bajando -- ver Fragmento/RecorridoService.archivoVoz() para el porqué de la carpeta
     *  global voz/<voz>/_comunes/.
     *
     *  OJO: queda fuera a propósito el fragmento "resto" (servicio+rosa) de
     *  RecorridoService.avisoAbordajeFragmentos() -- depende de servicioNombre()/esRosa(), lógica de
     *  viaje real que no vive aquí; reproducirla a mano arriesgaría que el texto no coincida byte a
     *  byte con lo que arma RecorridoService en vivo, dejando ese fragmento predescargado inútil. Cae
     *  al TTS en vivo la primera vez, igual que hoy. */
    private static List<Fragmento> fragmentosComunes(Context ctx) {
        List<Fragmento> f = new ArrayList<>();
        for (int id : CONECTORES_FIJOS) f.add(Fragmento.comun(ctx.getString(id)));
        for (int pal : new int[]{R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion})
            f.add(Fragmento.comun(ctx.getString(R.string.voz_prep_transferencia, ctx.getString(pal))));
        for (int n = 1; n <= MAX_N_ESTACIONES; n++) f.add(Fragmento.comun(ctx.getString(R.string.voz_n_estaciones, n)));
        return f;
    }

    /** Nombre (ya "habla-ready", ver nomDe()) de cada estación de la línea, como Fragmento ESTACION
     *  -- carpeta GLOBAL voz/<voz>/_estaciones/, reusable aunque la misma estación ya se haya bajado
     *  para otra línea (ver descargarVarias()). Dedup dentro de ESTA línea (en la práctica los
     *  nombres ya son únicos dentro de una línea, igual que en textosLinea()). */
    private static List<Fragmento> fragmentosEstacionLinea(Context ctx, int linea) {
        List<Fragmento> f = new ArrayList<>();
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return f;
        LinkedHashSet<String> vistos = new LinkedHashSet<>();
        for (Estacion e : l.estaciones) {
            if (e.soloMapa) continue;
            String nom = nomDe(e.nombre);
            if (nom != null && !nom.isEmpty() && vistos.add(nom)) f.add(Fragmento.estacion(nom));
        }
        return f;
    }

    /** Archivo destino de un Fragmento GLOBAL (ESTACION/COMUN) para la voz dada -- mismo esquema que
     *  archivoVoz() (LEGACY) de más abajo, pero vía Fragmento.rutaRelativa() (el 0 no importa:
     *  ESTACION/COMUN lo ignoran). */
    private static File archivoFragmento(Context ctx, Fragmento frag, String voz) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File f = new File(base, "voz/" + voz + "/" + frag.rutaRelativa(0));
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        return f;
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

    /** Descarga los audios de VARIAS líneas con la voz indicada, en segundo plano. Los textos LEGACY
     *  (ver textosLinea()) van en SU PROPIA carpeta (voz/<voz>/<sistema>/L<línea>/): si el mismo
     *  texto se repite entre 2 líneas, se descarga una vez por cada una -- la carpeta de cada línea
     *  queda completa por sí sola. Los fragmentos nuevos (ver Fragmento/fragmentosComunes()/
     *  fragmentosEstacionLinea()) son distintos A PROPÓSITO: viven en carpetas GLOBALES
     *  (voz/<voz>/_estaciones/, voz/<voz>/_comunes/) y se piden UNA sola vez por voz real distinta
     *  en el lote, sin importar cuántas líneas se estén bajando -- son justo los que
     *  RecorridoService reusa entre avisos/líneas, así que descargarlos por línea sería desperdiciar
     *  ancho de banda repitiendo lo mismo. */
    public static void descargarVarias(Context ctx, List<Integer> lineas, String voz, Progreso cb) {
        final Context app = ctx.getApplicationContext();
        cancelar = false;
        EXEC.execute(() -> {
            try {
                // Falla RÁPIDO si la red activa no tiene internet real (Wi-Fi sin servicio, portal
                // cautivo, avión, etc.), en vez de intentar las centenas de audios uno por uno.
                if (!Red.hayInternetReal(app)) {
                    MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                    return;
                }
                // Cada frase con su archivo destino (la voz REAL por línea, ver vozReal()); las que ya
                // están en el teléfono cuentan como hechas desde el principio.
                List<Item> faltan = new ArrayList<>();
                int total = 0, ok = 0;
                // Dedup DENTRO de este lote: si 2 líneas comparten una estación, o si 2+ líneas resuelven
                // la MISMA voz real, los fragmentos globales correspondientes solo se piden una vez (ya
                // en disco, lo evita el chequeo de archivo existente igual que siempre).
                Set<String> vistoEstacionVoz = new LinkedHashSet<>();
                Set<String> comunesHechosPara = new LinkedHashSet<>();
                for (int ln : lineas) {
                    String v = vozReal(app, voz, ln);
                    for (TextoEstacion te : textosLinea(app, ln)) {
                        total++;
                        File out = archivoVoz(app, te.texto, ln, v, te.estacion);
                        if (out == null) continue;
                        if (out.exists() && out.length() > 0) { ok++; continue; }
                        faltan.add(new Item(te.texto, v, out));
                    }
                    for (Fragmento frag : fragmentosEstacionLinea(app, ln)) {
                        if (!vistoEstacionVoz.add(v + "\u0000" + frag.texto)) continue;
                        total++;
                        File out = archivoFragmento(app, frag, v);
                        if (out.exists() && out.length() > 0) { ok++; continue; }
                        faltan.add(new Item(frag.texto, v, out));
                    }
                    if (comunesHechosPara.add(v)) {
                        for (Fragmento frag : fragmentosComunes(app)) {
                            total++;
                            File out = archivoFragmento(app, frag, v);
                            if (out.exists() && out.length() > 0) { ok++; continue; }
                            faltan.add(new Item(frag.texto, v, out));
                        }
                    }
                }
                final int totalF = total;
                final int[] hechos = {ok};
                MAIN.post(() -> { if (cb != null) cb.avance(hechos[0], totalF); });

                // 1) Paquetes .zip del EC2 (POST /api/tts/paquete): pocas peticiones en vez de una por
                //    frase. Si el servidor aún no tiene ese endpoint, cae al modo de una por una (abajo).
                boolean sinPaquete = false;
                java.util.Map<String, List<Item>> porVoz = new java.util.LinkedHashMap<>();
                for (Item it : faltan) {
                    List<Item> l = porVoz.get(it.voz);
                    if (l == null) { l = new ArrayList<>(); porVoz.put(it.voz, l); }
                    l.add(it);
                }
                List<Item> resto = new ArrayList<>();
                for (java.util.Map.Entry<String, List<Item>> e : porVoz.entrySet()) {
                    List<Item> cola = new ArrayList<>(e.getValue());
                    while (!cola.isEmpty() && !cancelar && !sinPaquete) {
                        List<Item> lote = new ArrayList<>(cola.subList(0, Math.min(LOTE_PAQUETE, cola.size())));
                        List<Item> pendientes;
                        try {
                            pendientes = descargarPaquete(e.getKey(), lote, n -> {
                                hechos[0] += n;
                                MAIN.post(() -> { if (cb != null) cb.avance(hechos[0], totalF); });
                            });
                        } catch (PaqueteNoDisponible x) {
                            sinPaquete = true;
                            break;
                        } catch (Exception x) {
                            MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                            return;
                        }
                        cola.subList(0, lote.size()).clear();
                        // Las que el servidor no alcanzó a sintetizar vuelven a la cola; si ninguna del lote
                        // avanzó (todas fallaron), se dejan para el modo de una por una.
                        if (pendientes.size() == lote.size()) { resto.addAll(pendientes); continue; }
                        cola.addAll(0, pendientes);
                    }
                    if (sinPaquete) resto.addAll(cola);
                }
                if (sinPaquete) {   // servidor sin /api/tts/paquete: todo lo que falte, de a una frase
                    resto.clear();
                    for (Item it : faltan) if (!(it.out.exists() && it.out.length() > 0)) resto.add(it);
                }

                // 2) De a una (respaldo): lo que el paquete no pudo dar.
                int fallosSeguidos = 0;
                for (Item it : resto) {
                    if (cancelar) break;
                    boolean bien = false;
                    try { bien = descargarUno(it); } catch (Exception ignore) {}
                    if (bien) {
                        fallosSeguidos = 0;
                        hechos[0]++;
                        MAIN.post(() -> { if (cb != null) cb.avance(hechos[0], totalF); });
                    } else if (++fallosSeguidos >= FALLOS_SEGUIDOS_ABORTAR) {
                        MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
                        return;
                    }
                }
                ok = 0;
                Set<String> vistoEstacionVoz2 = new LinkedHashSet<>();
                Set<String> comunesHechosPara2 = new LinkedHashSet<>();
                for (int ln : lineas) {
                    String v = vozReal(app, voz, ln);
                    for (TextoEstacion te : textosLinea(app, ln)) {
                        File f = archivoVoz(app, te.texto, ln, v, te.estacion);
                        if (f != null && f.exists() && f.length() > 0) ok++;
                    }
                    for (Fragmento frag : fragmentosEstacionLinea(app, ln)) {
                        if (!vistoEstacionVoz2.add(v + "\u0000" + frag.texto)) continue;
                        File f = archivoFragmento(app, frag, v);
                        if (f.exists() && f.length() > 0) ok++;
                    }
                    if (comunesHechosPara2.add(v)) {
                        for (Fragmento frag : fragmentosComunes(app)) {
                            File f = archivoFragmento(app, frag, v);
                            if (f.exists() && f.length() > 0) ok++;
                        }
                    }
                }
                final int okF = ok, totF = totalF;
                MAIN.post(() -> { if (cb != null) cb.fin(okF, totF); });
            } catch (Throwable t) {   // un error aquí (hilo de fondo) cerraría la app: se avisa como falla
                Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "DescargaVoz.descargarVarias", String.valueOf(t));
                MAIN.post(() -> { if (cb != null) cb.error(app.getString(R.string.audios_sin_red)); });
            }
        });
    }

    /** Una frase por descargar: texto, voz REAL y archivo destino en el teléfono. */
    private static final class Item {
        final String texto, voz;
        final File out;
        Item(String texto, String voz, File out) { this.texto = texto; this.voz = voz; this.out = out; }
    }

    /** La voz REAL de una línea: `vozElegida` es la PREFERENCIA del usuario ("Mia" o "Ximena"), pero
     *  cae a "Mia" para cualquier línea cuyo sistema no tenga el switch de Ximena activo (ver
     *  Modos.vozXimenaPermitida()) -- así "descargar todas" puede pedir Ximena una sola vez y que
     *  cada línea respete su propio switch, en vez de forzar Ximena (de pago) donde no se activó. */
    private static String vozReal(Context ctx, String vozElegida, int linea) {
        return "Ximena".equals(vozElegida) && Modos.vozXimenaPermitida(ctx, linea) ? "Ximena" : "Mia";
    }

    /** Frases por petición de paquete (el servidor acepta hasta 600). */
    private static final int LOTE_PAQUETE = 300;
    private static final String URL_PAQUETE = "https://geomb.duckdns.org/api/tts/paquete";

    /** El servidor todavía no tiene POST /api/tts/paquete (404/405): usar el modo de una por una. */
    private static final class PaqueteNoDisponible extends Exception {}

    private interface Avance { void mas(int n); }

    /**
     * Pide al EC2 un .zip con los audios de {@code lote} (misma caché permanente que /api/tts: lo que
     * ya existe sale sin volver a sintetizarse) y escribe cada mp3 en su archivo. El zip trae
     * "NNNN.mp3" (NNNN = índice en el lote) y "manifest.json" con los índices pendientes (el servidor
     * no alcanzó a sintetizarlos en esta petición). Devuelve los Items que siguen faltando.
     */
    private static List<Item> descargarPaquete(String voz, List<Item> lote, Avance avance) throws Exception {
        org.json.JSONArray textos = new org.json.JSONArray();
        for (Item it : lote) textos.put(it.texto);
        org.json.JSONObject cuerpo = new org.json.JSONObject().put("voz", voz).put("textos", textos);
        byte[] datos = cuerpo.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(URL_PAQUETE).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);   // el servidor puede tardar ~20 s sintetizando lo que falte
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/zip");
        boolean[] escrito = new boolean[lote.size()];
        try {
            try (java.io.OutputStream os = c.getOutputStream()) { os.write(datos); }
            int code = c.getResponseCode();
            if (code == 404 || code == 405) throw new PaqueteNoDisponible();
            if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(c.getInputStream()))) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    if (!n.matches("\\d{4,}\\.mp3")) continue;   // manifest.json u otros: no hace falta
                    int idx = Integer.parseInt(n.substring(0, n.length() - 4));
                    if (idx < 0 || idx >= lote.size()) continue;
                    File out = lote.get(idx).out;
                    File tmp = new File(out.getPath() + ".tmp");
                    try (FileOutputStream fo = new FileOutputStream(tmp)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) fo.write(buf, 0, r);
                    }
                    if (tmp.length() > 0 && tmp.renameTo(out)) {
                        escrito[idx] = true;
                        avance.mas(1);
                    } else {
                        tmp.delete();
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        List<Item> faltan = new ArrayList<>();
        for (int i = 0; i < lote.size(); i++) if (!escrito[i]) faltan.add(lote.get(i));
        return faltan;
    }

    /** Baja UNA frase con GET /api/tts (respaldo del paquete). Devuelve true si quedó el archivo. */
    private static boolean descargarUno(Item it) throws java.io.IOException {
        File out = it.out;
        if (out.exists() && out.length() > 0) return true;   // ya cacheado
        URL url = new URL("https://geomb.duckdns.org/api/tts?voz=" + it.voz + "&texto="
                + android.net.Uri.encode(it.texto));
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

    /** Borra los audios (Fase 1) de una línea, en CUALQUIERA de las voces que se hayan descargado.
     *  A PROPÓSITO no toca los fragmentos globales (ESTACION/COMUN, ver Fragmento): son compartidos
     *  entre líneas -- si "borrar línea 1" borrara el nombre "Indios Verdes" y la línea 2 (que
     *  también lo necesita) ya estaba descargada, le rompería la línea 2 sin que se note. Solo
     *  borrarTodo() los limpia (recorre toda la carpeta voz/, globales incluidos). */
    public static int borrarLinea(Context ctx, int linea) {
        int n = 0;
        for (TextoEstacion te : textosLinea(ctx, linea)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoVoz(ctx, te.texto, linea, voz, te.estacion);
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
     *  estado). Si se descargó en las 2 voces, cuenta ambas (coherente con borrarLinea()). Incluye
     *  los fragmentos globales que esta línea necesita (nombres de estación + comunes): si no
     *  están, el aviso fragmentado de esta línea igual cae al TTS en vivo la primera vez, así que
     *  "completa" debe depender de ellos también -- coherente con lo que ya cuenta descargarVarias(). */
    public static int cuantosDescargados(Context ctx, int linea) {
        int n = 0;
        for (TextoEstacion te : textosLinea(ctx, linea)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoVoz(ctx, te.texto, linea, voz, te.estacion);
                if (f != null && f.exists() && f.length() > 0) n++;
            }
        }
        for (Fragmento frag : fragmentosEstacionLinea(ctx, linea)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoFragmento(ctx, frag, voz);
                if (f.exists() && f.length() > 0) n++;
            }
        }
        for (Fragmento frag : fragmentosComunes(ctx)) {
            for (String voz : VOCES_CONOCIDAS) {
                File f = archivoFragmento(ctx, frag, voz);
                if (f.exists() && f.length() > 0) n++;
            }
        }
        return n;
    }

    // --- MISMO esquema que RecorridoService.archivoVoz (para que coincidan los hashes y la carpeta).
    // Almacenamiento EXTERNO propio de la app (Android/data/<paquete>/files/voz/, NO getCacheDir()):
    // el sistema nunca lo borra bajo presión de espacio (solo al desinstalar la app), y a diferencia
    // de getFilesDir() sí es accesible con un explorador de archivos, sin permisos especiales. Si no
    // hay almacenamiento externo disponible, cae al interno para no perder la función.
    // Subcarpetas voz/<voz>/<sistema>/L<línea>/<estación>/ -- navegables/filtrables a mano (ver
    // Sistemas.carpeta()/numeroDeLinea()/Locuciones.carpetaEstacion()). Por DEFAULT la pre-descarga
    // masiva sigue siendo "Mia" (ver los overloads sin
    // `voz` de descargarLinea()/descargarVarias()): esta es una descarga de TODAS las estaciones de
    // una línea de un golpe, mientras que RecorridoService.decirConVoz() solo pide UNA frase a la vez
    // según el recorrido real -- el costo ahí queda acotado por cuánto de verdad viajas. Por eso
    // ConfiguracionFragment solo ofrece elegir "Ximena" aquí cuando el sistema de esa línea YA tiene
    // su propio switch activo (Modos.vozXimenaMetrobus()/Mexibus()/Mexicable()) -- nunca por default. ---
    private static File archivoVoz(Context ctx, String texto, int linea, String voz, String estacion) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "voz/" + voz + "/" + Sistemas.carpeta(Sistemas.sistemaDe(linea))
                + "/L" + Sistemas.numeroDeLinea(linea) + "/" + Locuciones.carpetaEstacion(estacion));
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
