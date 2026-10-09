package com.memegrados.GeoMB;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Revisa cada minuto, en segundo plano, la página oficial de estado del servicio
 * (manifestaciones, cierres, mantenimiento) con un WebView oculto, detecta las
 * estaciones afectadas y avisa. El planificador las usa para rutas alternas.
 */
// ============================================================
// CLASE    : ManifestacionesService   (extends Service)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Servicio que revisa cada minuto, en segundo plano, la página OFICIAL de
// estado del servicio (manifestaciones, cierres, mantenimiento) usando un
// WebView OCULTO, detecta las estaciones afectadas y avisa. El planificador
// las usa para rutas alternas.
//
// ¿POR QUÉ WEBVIEW? La página del gobierno se arma con JavaScript; un
// WebView invisible la ejecuta y lee el resultado (scraping). Este monitoreo
// LOCAL es un respaldo: cuando el EC2 vive, manda los push y este no duplica.
// También dispara AfectMexibusFeed (respaldo Mexibús) solo si el EC2 está caído.
// ============================================================
public class ManifestacionesService extends Service {

    // Estado del Servicio: iframe de incidentesmovilidad (tabla limpia Línea·Estado·Estaciones·Info).
    private static final String URL_ESTADO =
            "https://incidentesmovilidad.cdmx.gob.mx/public/bandejaEstadoServicio.xhtml?idMedioTransporte=mb";
    // Elevadores y estaciones en mantenimiento: tablas nativas de la página de ServicioMB.
    private static final String URL_SERVICIOMB = "https://www.metrobus.cdmx.gob.mx/ServicioMB";

    /** Extractor del iframe "Estado del Servicio" (logo MB{n} · Estado · Estaciones afectadas · Info). */
    private static final String JS_ESTADO =
            "(function(){" +
            "function tx(e){if(!e)return'';var c=e.cloneNode(true);var q=c.querySelectorAll?c.querySelectorAll('.ui-column-title'):[];for(var i=0;i<q.length;i++)q[i].parentNode.removeChild(q[i]);return (c.textContent||'').replace(/\\s+/g,' ').trim();}" +
            "function nu(s){var m=(s||'').match(/\\d+/);return m?m[0]:'';}" +
            "var out=[];var trs=document.querySelectorAll('table tr');" +
            "for(var i=0;i<trs.length;i++){var cs=trs[i].querySelectorAll('td');if(cs.length<4)continue;" +
            "var l=nu(tx(cs[0]));if(!l){var im=cs[0].querySelector('img');if(im)l=nu((im.getAttribute('src')||'')+' '+(im.alt||''));}" +
            "out.push({tipo:'estado',linea:l,estado:tx(cs[1]),estaciones:tx(cs[2]),info:cs[3]?tx(cs[3]):''});}" +
            "return JSON.stringify({rows:out});})();";

    /** Extractor de las tablas de ServicioMB: elevadores (Línea N …) y mantenimiento (Periodo …). */
    private static final String JS_TABLAS =
            "(function(){" +
            "function tx(e){if(!e)return'';var c=e.cloneNode(true);var q=c.querySelectorAll?c.querySelectorAll('.ui-column-title'):[];for(var i=0;i<q.length;i++)q[i].parentNode.removeChild(q[i]);return (c.textContent||'').replace(/\\s+/g,' ').trim();}" +
            "function nu(s){var m=(s||'').match(/\\d+/);return m?m[0]:'';}" +
            "var out=[];var tbs=document.querySelectorAll('table');" +
            "for(var t=0;t<tbs.length;t++){var tb=tbs[t];var esMant=tx(tb).toLowerCase().indexOf('periodo de cierre')>=0;" +
            "var trs=tb.querySelectorAll('tr');" +
            "for(var i=0;i<trs.length;i++){var cs=trs[i].querySelectorAll('td');if(cs.length<4)continue;var c0=tx(cs[0]);" +
            "if(esMant){var ln=nu(tx(cs[1]));if(!ln)continue;" +
            "out.push({tipo:'mantenimiento',extra:c0,linea:ln,estacion:tx(cs[2]),direccion:tx(cs[3]),motivo:cs[4]?tx(cs[4]):''});}" +
            "else if(/l\\u00ednea\\s*\\d|linea\\s*\\d/i.test(c0)){" +
            "out.push({tipo:'elevador',linea:nu(c0),estacion:tx(cs[1]),direccion:tx(cs[2]),motivo:tx(cs[3]),extra:cs[4]?tx(cs[4]):''});}" +
            "}}return JSON.stringify({rows:out});})();";
    private static final long INTERVALO_MS = 60_000L;   // cada minuto
    private static final String CANAL = "manifestaciones";       // ongoing (silencioso)
    private static final String CANAL_AVISO = "manifestaciones_avisos"; // tarjetas de afectación
    private static final int ID_ONGOING = 4301;
    private static final int ID_ESTADO_BASE = 4310;              // (heredado) estado por línea
    private static final int ID_ESTADO_CLAVE_BASE = 43100;       // estado por AFECTACIÓN (43100..44099), evita duplicados
    private static final int ID_OTROS_BASE = 4330;              // elevadores + mantenimiento (2 al día)
    private static final int ID_OTROS_SUMMARY = 4329;
    private static final String GRUPO_OTROS = "afectaciones_otros";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean cargando = false;
    private List<Manifestaciones.Afectacion> ultimaLista = new ArrayList<>();

    private int fase = 0;                                         // 0 = tablas (ServicioMB), 1 = estado (iframe)
    private Set<String> afectAcc = new HashSet<>();
    // Bloqueo POR SENTIDO: estación(norm) -> {terminal(norm) | AMBOS}. General y de movilidad reducida.
    private java.util.Map<String, java.util.Set<String>> porSentidoAcc = new java.util.HashMap<>();
    private java.util.Map<String, java.util.Set<String>> porSentidoMRAcc = new java.util.HashMap<>();
    // Claves "linea|estacion" donde el Estado del Servicio (fuente EN VIVO) ya dio un sentido
    // específico (p. ej. "hacia El Caminero"). Se usa para no dejar que Mantenimiento/Elevadores
    // (tablas de calendario, que suelen traer "Dirección" vacía → AMBOS por defecto) tumben ese
    // sentido específico y bloqueen la estación completa por el MISMO cierre real.
    private Set<String> direccionEspecificaAcc = new HashSet<>();
    private List<Manifestaciones.Afectacion> listaAcc = new ArrayList<>();
    private List<String> resumenAcc = new ArrayList<>();
    // Afectación inferida por estación (L4 "por servicios", ver bloquearRutaL4()): aparte de
    // listaAcc para no duplicar cada estación en la tabla de "Estado del servicio" ni en el panel
    // de elevadores/otras (ambos recorren listaAcc completa) -- solo alimenta afectacionEstacion().
    private java.util.Map<String, Manifestaciones.Afectacion> extraPorEstacionAcc = new java.util.HashMap<>();
    private Set<String> cortesAcc = new HashSet<>();             // cortes reales (partición de línea) del ciclo
    private int estadoFilas = 0;                                  // filas leídas del Estado del Servicio
    // Estado ya notificado por línea (clave de la situación) para no repetir el aviso.
    private final java.util.Set<String> notifClaves = new java.util.HashSet<>();   // claves de afectación ya avisadas
    private boolean notifEstadoCargado = false;   // ¿ya se restauró el dedup persistido de esta sesión?
    // Identifica el ciclo de consulta VIGENTE. Solo avanza en revisar() (ciclo nuevo), en
    // candadoVencido()/seguridadVencida() (invalidan el ciclo atorado) y en onDestroy() (invalida
    // cualquiera en curso). Cada callback asíncrono (el evaluateJavascript agendado en
    // onPageFinished, onTablas, onEstado) captura su propio "gen" en el momento en que se agenda y
    // lo compara contra este campo ANTES de tocar "fase"/los acumuladores o publicar en
    // Manifestaciones -- si no coincide, el ciclo al que pertenecía ya fue reemplazado o invalidado
    // y el callback no hace nada.
    //
    // ESTO SOLO (como quedó la ronda anterior) NO BASTA: "gen"/"fase" son solo números que se
    // reescriben con cada ciclo nuevo, así que un onPageFinished TARDÍO de una navegación vieja,
    // si llega después de que un ciclo nuevo YA reescribió "fase" (0 ó 1, los únicos valores que
    // ese "if" acepta) y "generacion" a un valor que coincide por pura casualidad con el ciclo
    // nuevo, puede leer esos valores YA actualizados, calzar como si fuera de ese ciclo, y agendar
    // un evaluateJavascript DUPLICADO para un ciclo que ya tenía el suyo en curso -- ver "web" abajo,
    // que es la protección que realmente cierra esto.
    private int generacion = 0;
    private Runnable candadoPendiente;      // referencia del candado agendado para el ciclo vigente
    private Runnable seguridadPendiente;    // referencia de "seguridad" agendado para el ciclo vigente
    // Última generación para la que onTablas()/onEstado() YA corrieron procesarFilas()/guardarStore().
    // Guarda de PROCESO (por identidad de ciclo+fase), no de CONTENIDO: si por lo que sea
    // onTablas/onEstado se invocan dos veces con el MISMO "gen" (un evaluateJavascript duplicado
    // que de todos modos pasara el chequeo de "web"/"gen" de abajo, o cualquier otra causa), la
    // segunda vez no vuelve a correr procesarFilas() ni a publicar -- nunca inspecciona ni filtra
    // filas por su contenido, así que no oculta ni descarta datos legítimos repetidos en la fuente.
    private int ultimoOnTablasGen = -1;
    private int ultimoOnEstadoGen = -1;

    /**
     * WebView ACTUAL: la única fuente de verdad sobre qué navegación es la vigente. A diferencia
     * de "generacion" (un contador que se reescribe y puede coincidir por casualidad con un ciclo
     * distinto), comparar {@code v == web} es una garantía del lenguaje (identidad de objeto): un
     * callback de un WebView que YA NO es el de este campo (porque {@link #candadoVencido}/
     * {@link #seguridadVencida} lo reemplazaron, ver {@link #recrearWebView}) jamás puede volver a
     * calzar, sin importar qué valores tengan "fase"/"generacion" en ese momento -- no depende de
     * ninguna suposición sobre si stopLoading()/destroy() alcanzan a cancelar la navegación o el
     * evaluateJavascript ya entregado al motor de JS; el objeto viejo simplemente deja de ser "web".
     */
    private WebView web;

    /** Construye y configura un WebView nuevo (el mismo armado que antes vivía en onCreate()). */
    private WebView crearWebView() {
        WebView w = new WebView(getApplicationContext());
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public void onPageFinished(WebView v, String url) {
                // Guarda PRINCIPAL (ver el javadoc de "web"): si este callback no es del WebView
                // vigente, ninguna navegación suya puede ya avanzar ningún ciclo, sin importar fase.
                if (v != web) return;
                final String u = url != null ? url : "";
                final int gen = generacion;
                // Fase 0: tablas de ServicioMB (elevadores + mantenimiento). Fase 1: iframe Estado del Servicio.
                if (fase == 0 && u.contains("metrobus.cdmx.gob.mx/ServicioMB")) {
                    handler.postDelayed(() -> {
                        if (v != web || gen != generacion) return;
                        v.evaluateJavascript(JS_TABLAS, valor -> onTablas(gen, valor));
                    }, 2500);
                } else if (fase == 1 && u.contains("bandejaEstadoServicio")) {
                    handler.postDelayed(() -> {
                        if (v != web || gen != generacion) return;
                        v.evaluateJavascript(JS_ESTADO, valor -> onEstado(gen, valor));
                    }, 2500);
                }
            }
        });
        return w;
    }

    /**
     * Reemplaza el WebView actual por uno NUEVO -- se llama SOLO al invalidar un ciclo cuya
     * navegación podía seguir en curso ({@link #candadoVencido}/{@link #seguridadVencida}), nunca
     * en el camino normal (onEstado() terminando bien no deja ninguna navegación ambigua detrás,
     * así que reutiliza el mismo WebView sin necesidad de recrearlo).
     *
     * <p>{@code destroy()} es la operación de WebView con comportamiento documentado de verdad
     * ("no deben esperarse más callbacks después de llamarlo"), a diferencia de {@code
     * stopLoading()} (que NO lo garantiza) -- pero aun si algo del WebView viejo (destruido o no)
     * siguiera disparando un callback, esta función NO depende de eso para ser segura: una vez que
     * "web" apunta al objeto nuevo, {@code v == web} en {@code onPageFinished} es FALSO para
     * cualquier callback del objeto viejo, sin excepción posible -- identidad de objeto, no un
     * valor (fase/generación) que una coincidencia de timing pudiera igualar.
     */
    private void recrearWebView() {
        WebView viejo = web;
        web = crearWebView();
        if (viejo != null) viejo.destroy();
    }

    // Si la fase 0 falla en cargar del todo -- error de red, redirección a una página de error,
    // timeout del WebView -- onPageFinished puede no disparar nunca con la URL esperada (ver el
    // "if" de fase en onPageFinished): ni onTablas ni "seguridadVencida" llegarían a ejecutarse,
    // "cargando" se quedaría en true para siempre y revisar() dejaría de intentar CUALQUIER ciclo
    // futuro -- en silencio, sin vaciar ningún estado, pero también sin volver a actualizarlo
    // jamás. Este candado cubre el ciclo COMPLETO (ambas fases); se agenda al iniciar cada ciclo
    // (ver revisar()) con un margen por debajo de INTERVALO_MS para no pisar el siguiente tick normal.
    private static final long CANDADO_MS = 50_000L;

    /**
     * El candado venció para el ciclo "gen". NO asume que la consulta terminó: si ese ciclo sigue
     * siendo el vigente ({@code gen == generacion}) y sigue "cargando" (si ya terminó por su
     * cuenta, o ya fue reemplazado, no hay nada que hacer), invalida el ciclo en vez de darlo por
     * completado -- {@code fase = -1} y {@code generacion++} como defensa adicional, pero la
     * protección real contra que la navegación vieja interfiera con el ciclo nuevo es {@link
     * #recrearWebView}: aísla el WebView viejo por IDENTIDAD, no por un valor que pudiera coincidir.
     */
    private void candadoVencido(int gen) {
        if (gen != generacion || !cargando) return;
        Telemetria.registrarError(this, Telemetria.ERR_RED, "ManifestacionesService.candado",
                "ciclo sin completar tras " + CANDADO_MS + " ms (fase=" + fase + ")");
        fase = -1;
        generacion++;
        cargando = false;
        recrearWebView();
    }

    // Igual que CANDADO_MS, pero solo para cuando la FASE 1 (iframe de Estado del Servicio) no
    // responde (se agenda desde onTablas, una vez que la fase 0 ya completó y ya se publicó).
    private static final long SEGURIDAD_MS = 15_000L;

    /** Como {@link #candadoVencido}, pero para cuando solo la fase 1 no responde: lo de la fase 0
     *  ya se publicó (onTablas ya llamó guardarStore()), así que basta avisar con lo que haya y
     *  dejar que el siguiente ciclo reintente la fase 1. Misma invalidación por el mismo motivo. */
    private void seguridadVencida(int gen) {
        if (gen != generacion || !cargando) return;
        notificar();
        fase = -1;
        generacion++;
        cargando = false;
        recrearWebView();
    }

    private final Runnable tick = this::revisar;

    public static void iniciar(android.content.Context c) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                    c, new Intent(c, ManifestacionesService.class));
        } catch (IllegalStateException e) {   // Android puede negar el arranque del foreground service
            Telemetria.registrarError(c, Telemetria.ERR_EXCEPCION, "ManifestacionesService.iniciar", String.valueOf(e));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal(this);
        web = crearWebView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        arrancarPrimerPlano();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void revisar() {
        refrescarEc2();   // actualiza en segundo plano la liveness del EC2 (para el gate de notificar())
        if (!cargando && web != null) {
            cargando = true;
            generacion++;                 // ciclo nuevo: invalida cualquier callback tardío del anterior
            final int gen = generacion;
            fase = 0;
            estadoFilas = 0;
            afectAcc = new HashSet<>();
            porSentidoAcc = new java.util.HashMap<>();
            porSentidoMRAcc = new java.util.HashMap<>();
            direccionEspecificaAcc = new HashSet<>();
            listaAcc = new ArrayList<>();
            resumenAcc = new ArrayList<>();
            cortesAcc = new HashSet<>();
            extraPorEstacionAcc = new java.util.HashMap<>();
            if (candadoPendiente != null) handler.removeCallbacks(candadoPendiente);
            if (seguridadPendiente != null) handler.removeCallbacks(seguridadPendiente);
            candadoPendiente = () -> candadoVencido(gen);
            handler.postDelayed(candadoPendiente, CANDADO_MS);   // ver comentario en candadoVencido()
            // "web" aquí SIEMPRE está en un estado idle conocido: si el ciclo anterior terminó bien
            // (onEstado) nunca dejó una navegación pendiente; si se invalidó (candadoVencido/
            // seguridadVencida), recrearWebView() ya lo reemplazó por uno nuevo, nunca navegado.
            // No hace falta (ni se presupone) un stopLoading() defensivo aquí.
            web.loadUrl(URL_SERVICIOMB);   // primero las tablas (funciona seguro); luego el iframe de estado
        }
        handler.postDelayed(tick, INTERVALO_MS);
    }

    /** Fase 0 listo: elevadores + mantenimiento. Actualiza el estado (para la app) y va por el Estado del Servicio.
     *  Corre en el callback ASÍNCRONO de evaluateJavascript (fuera de cualquier try/catch de arriba), y
     *  procesarFilas() depende del HTML real de una página externa que puede cambiar sin aviso -- si
     *  truena, no debe dejar el servicio bloqueado (cargando=true para siempre). "gen" es la
     *  generación capturada en onPageFinished cuando se agendó este callback: si para cuando el
     *  motor de JS por fin entrega el resultado el ciclo ya fue invalidado o reemplazado, no se
     *  toca "fase" ni los acumuladores ni se publica nada -- ver el comentario de "generacion".
     *  "ultimoOnTablasGen" es una guarda de PROCESO, no de contenido: si este "gen" YA se procesó
     *  (p. ej. un evaluateJavascript agendado dos veces que de todos modos pasara el chequeo de
     *  "web"/"gen" en onPageFinished), la segunda vez no vuelve a correr procesarFilas()/
     *  guardarStore() ni a avanzar de fase -- nunca inspecciona el CONTENIDO de jsonValue, así que
     *  no oculta ni descarta filas legítimas repetidas en la fuente, solo evita reprocesar la MISMA
     *  fase del MISMO ciclo dos veces. */
    private void onTablas(int gen, String jsonValue) {
        if (gen != generacion || gen == ultimoOnTablasGen) return;
        ultimoOnTablasGen = gen;
        try {
            procesarFilas(jsonValue);
            guardarStore();                              // panel en la app se actualiza siempre
        } catch (Exception e) {
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ManifestacionesService.onTablas", String.valueOf(e.getMessage()));
        }
        fase = 1;
        if (web != null) web.loadUrl(URL_ESTADO);
        if (seguridadPendiente != null) handler.removeCallbacks(seguridadPendiente);
        seguridadPendiente = () -> seguridadVencida(gen);
        handler.postDelayed(seguridadPendiente, SEGURIDAD_MS);   // si el iframe no responde, no bloquear el ciclo
    }

    /** Fase 1 listo: Estado del Servicio. Actualiza el estado y decide qué notificar. Mismo motivo que
     *  onTablas: procesarFilas()/notificar() no deben tumbar el servicio, y cargando debe liberarse
     *  siempre (si no, el ciclo de refresco queda bloqueado para siempre). Mismo chequeo de "gen"
     *  que onTablas, por el mismo motivo. Misma guarda de PROCESO que onTablas (ver su comentario)
     *  con "ultimoOnEstadoGen" -- tampoco mira el contenido de jsonValue. */
    private void onEstado(int gen, String jsonValue) {
        if (gen != generacion || gen == ultimoOnEstadoGen) return;
        ultimoOnEstadoGen = gen;
        if (seguridadPendiente != null) handler.removeCallbacks(seguridadPendiente);
        try {
            procesarFilas(jsonValue);
            guardarStore();
            notificar();
        } catch (Exception e) {
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ManifestacionesService.onEstado", String.valueOf(e.getMessage()));
        } finally {
            cargando = false;
        }
    }

    /** Actualiza el estado compartido (panel en la app + ruteo). NO notifica. */
    private void guardarStore() {
        // Si el Estado del Servicio (fuente EN VIVO) ya dio un sentido específico para una estación,
        // que NO se pise por un "ambos sentidos" de Mantenimiento/Elevadores del MISMO cierre (esas
        // tablas suelen traer "Dirección" vacía y caen a AMBOS por defecto — ver direccionEfectiva()).
        // Caso real: Línea 1 · Euzkaro, "Sin servicio hacia El Caminero" en Estado del Servicio, pero
        // Mantenimiento repite el mismo cierre sin dirección propia → bloqueaba Euzkaro en AMBOS
        // sentidos e impedía llegar ahí incluso desde el norte (sentido nunca afectado).
        for (String k : direccionEspecificaAcc) {
            java.util.Set<String> s = porSentidoAcc.get(k);
            if (s != null) s.remove(Manifestaciones.AMBOS);
        }
        Manifestaciones.actualizar(afectAcc, porSentidoAcc, porSentidoMRAcc, listaAcc, join(resumenAcc));
        Manifestaciones.setExtraPorEstacion(extraPorEstacionAcc);   // L4 "por servicios" (bloquearRutaL4)
        Manifestaciones.reemplazarCortesReales(cortesAcc);   // parte la(s) línea(s) donde hay cierre total
        ultimaLista = listaAcc;
    }

    /**
     * Dirección real de un cierre de elevador/mantenimiento, para no bloquear AMBOS sentidos de más.
     * La columna "Dirección" de esas tablas suele traer YA el nombre de una terminal (p. ej. "Hacia
     * El Caminero" o solo "El Caminero"): si lo reconocemos, se usa esa terminal tal cual. Si la
     * columna viene vacía (caso real: un mismo cierre aparece en Estado del Servicio CON dirección en
     * el texto —"Sin servicio hacia El Caminero..."— pero la fila de Mantenimiento/Elevador no repite
     * esa dirección en su propia columna), se intenta leer la terminal del texto libre del "motivo"
     * con la misma heurística que {@link #terminalEnTexto}; sin eso, se cae al valor original (vacío
     * o "ambos" = cierra la estación completa, como antes). Evita que la tabla de mantenimiento
     * bloquee TODA la estación cuando el propio Estado del Servicio ya aclaró que es solo un sentido.
     */
    private String direccionEfectiva(int nlinea, String direccion, String motivo) {
        Linea l = GtfsRepository.porNumero(this, nlinea);
        if (l != null && !l.estaciones.isEmpty()) {
            String nd = Planificador.norm(direccion);
            String t1 = Planificador.norm(l.estaciones.get(0).nombre);
            String t2 = Planificador.norm(l.estaciones.get(l.estaciones.size() - 1).nombre);
            if (t1.length() >= 4 && nd.contains(t1)) return t1;
            if (t2.length() >= 4 && nd.contains(t2)) return t2;
        }
        String t = terminalEnTexto(nlinea, Planificador.norm(motivo));
        return t != null ? t : direccion;
    }

    /**
     * Registra un bloqueo por sentido: "linea|estacion" -> {terminal(norm) | AMBOS}. La línea es
     * SIEMPRE parte de la clave (nunca solo el nombre): dos líneas pueden compartir nombre de
     * estación (p. ej. "La Raza" en L1 y L3) sin ser la misma parada física. La dirección "ambos
     * sentidos"/vacía marca la estación completa; cualquier otra se toma como el nombre de la
     * terminal del carril afectado. Se quitan paréntesis del nombre (p. ej. "(Escaleras Sur)").
     */
    private void agregarSentido(java.util.Map<String, java.util.Set<String>> mapa,
                                int linea, String estacion, String direccion) {
        if (estacion == null) return;
        String est = Planificador.norm(estacion.replaceAll("\\(.*?\\)", ""));
        if (est.length() < 3) return;
        String d = Planificador.norm(direccion);
        String clave = (d.isEmpty() || d.contains("ambos") || d.contains("ambas")
                || d.contains("todos") || d.contains("todas") || d.contains("dos sentidos"))
                ? Manifestaciones.AMBOS : d;
        mapa.computeIfAbsent(linea + "|" + est, z -> new java.util.HashSet<>()).add(clave);
    }

    /** Separadores de una LISTA de nombres de estación en la columna "estaciones" del feed: coma,
     *  punto y coma, salto de línea, o la conjunción " y "/" e " entre nombres. Exige mayúscula
     *  tras la conjunción porque el texto scrapeado conserva las mayúsculas de los nombres propios
     *  (ver {@link #limpiar}, que solo recorta espacios) -- evita partir en dos un nombre que
     *  tuviera esa conjunción en minúscula dentro de sí mismo (ninguna estación de Metrobús la
     *  tiene hoy, comprobado contra el catálogo, pero así no se asume para L4/L7 ni para futuras). */
    private static final java.util.regex.Pattern P_SEP_LISTA_ESTACIONES =
            java.util.regex.Pattern.compile("[,;\\n]+|\\s+(?:y|e)\\s+(?=[A-ZÁÉÍÓÚÑ])");

    /**
     * Separa el texto de la columna "estaciones" en nombres de estación INDEPENDIENTES, cada uno
     * ya normalizado ({@link Planificador#norm}) -- para compararlos por IGUALDAD EXACTA contra el
     * nombre normalizado de una estación del catálogo.
     *
     * <p>Antes, la comparación era "¿el texto de la fila CONTIENE este nombre?" ({@code
     * nEst.contains(nn)}, sobre el texto YA normalizado completo). Eso cruza nombres que comparten
     * SOLO una palabra completa: "Insurgentes" es subcadena literal (como palabra completa) de
     * "Teatro de los Insurgentes" -- dos estaciones reales y distintas de Línea 1, a ~6 km de
     * distancia -- así que una fila que solo mencionara "Teatro de los Insurgentes" bloqueaba
     * TAMBIÉN "Insurgentes" sin relación alguna. Separar primero en elementos de lista y exigir
     * igualdad exacta por elemento evita ese cruce sin perder la función de reconocer una LISTA
     * ("El Chopo, Hamburgo y La Raza") ni nombres compuestos ("Teatro de los Insurgentes" sigue
     * siendo un solo elemento, comparado completo contra el catálogo).
     *
     * <p>LIMITACIÓN DOCUMENTADA (no se inventa una interpretación): si el feed usa un separador
     * distinto de coma/punto y coma/salto de línea/" y "/" e " (p. ej. una lista en prosa libre sin
     * puntuación "Durango Chilpancingo Nápoles"), esta función no reconstruye esos nombres por
     * separado y el elemento combinado no calzará con ningún nombre exacto del catálogo -- esa fila
     * no bloqueará esas estaciones por esta vía. Se prefiere no bloquear a inventar una partición.
     */
    static List<String> itemsEstaciones(String estacionesRaw) {
        if (estacionesRaw == null || estacionesRaw.isEmpty()) return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : P_SEP_LISTA_ESTACIONES.split(estacionesRaw)) {
            String nn = Planificador.norm(p);
            if (!nn.isEmpty()) out.add(nn);
        }
        return out;
    }

    // Guion RODEADO DE ESPACIOS/NBSP como separador de RANGO en la columna "estaciones" -- misma
    // convención ya usada para "A - B" en "info" (ver más abajo en este archivo): uno pegado a
    // letras ("Ex-Hacienda") no cuenta.
    private static final java.util.regex.Pattern P_RANGO_ESTACIONES =
            java.util.regex.Pattern.compile("[\\s\\u00A0]+-[\\s\\u00A0]+");

    /**
     * Defecto confirmado (caso real en dispositivo: L3, estado "Manifestación", columna
     * "Estaciones afectadas" = "Poniente 128 - Cuitláhuac"): el guion ahí NO es un nombre compuesto
     * ni una lista -- es un RANGO entre dos estaciones reales de la línea (la primera y la última
     * del tramo afectado). {@link #itemsEstaciones} no lo reconocía (solo separa por coma/"y"/"e"),
     * así que ese texto nunca calzaba con ningún nombre exacto del catálogo y la fila no bloqueaba
     * nada por esta vía -- dejando sin marcar el tramo real que el propio aviso oficial nombra.
     *
     * <p>Se resuelve aparte de {@code info} (que para el mismo aviso real decía "Pueblo de Santa
     * Cruz", un nombre que NUNCA calza con el real del catálogo, "Pueblo Sta. Cruz Atoyac") porque
     * la columna "Estaciones afectadas" usa, en los casos reales observados, el nombre EXACTO del
     * catálogo en cada extremo -- más confiable que parafrasear "información adicional" en prosa
     * libre, que es donde vive esa discrepancia de abreviatura.
     *
     * <p>Si no hay guion rodeado de espacios, o si alguno de los dos extremos no resuelve contra el
     * catálogo de ESTA línea, devuelve {@code null} -- no se inventa un rango sin ambos nombres
     * confirmados; el llamador cae de vuelta a {@link #itemsEstaciones} (comportamiento de siempre).
     */
    static int[] rangoEstacionesCerradas(String estacionesRaw, List<Estacion> catalogoLinea) {
        if (estacionesRaw == null) return null;
        java.util.regex.Matcher m = P_RANGO_ESTACIONES.matcher(estacionesRaw);
        if (!m.find()) return null;
        String a = estacionesRaw.substring(0, m.start()).trim();
        String b = estacionesRaw.substring(m.end()).trim();
        if (a.length() < 3 || b.length() < 3) return null;
        int ia = idxEstacion(catalogoLinea, Planificador.norm(a));
        int ib = idxEstacion(catalogoLinea, Planificador.norm(b));
        if (ia < 0 || ib < 0) return null;
        return new int[]{Math.min(ia, ib), Math.max(ia, ib)};
    }

    /** Bloquea una estación DE ESA LÍNEA: por sentido (hacia esa terminal) si se detectó una, o
     *  ambos si no. La clave lleva la línea para no cruzarse con otra línea del mismo nombre. */
    private void bloquearNn(int linea, String nn, String terminalSentido) {
        if (nn == null || nn.length() < 3) return;
        // claveTerminal(): sin efecto en Metrobús (1..7, sin variantes), pero mantiene la clave
        // consistente con Manifestaciones.clave()/AfectacionesMexibus (ordinario↔exprés normalizado).
        String k = Planificador.claveTerminal(linea) + "|" + nn;
        if (terminalSentido != null) {
            porSentidoAcc.computeIfAbsent(k, z -> new java.util.HashSet<>()).add(terminalSentido);
            direccionEspecificaAcc.add(k);   // el Estado del Servicio ya dio un sentido: ver guardarStore()
        } else {
            afectAcc.add(k);
        }
    }


    /**
     * Terminal (norm) del SENTIDO afectado, o null = ambos sentidos. Solo se considera "un sentido"
     * si el texto lo dice explícitamente (dirección / sentido / hacia). Esto evita confundir una
     * descripción por RANGOS ("Servicio de Indios Verdes a Plaza de la República y de El Caminero a
     * Insurgentes") —donde las terminales son extremos de tramo, no un sentido— con un cierre de un
     * solo carril: en ese caso se corta AMBOS sentidos (se parte la línea).
     */
    // static: no usa Context ni estado de instancia (solo Planificador.terminales(), estático) --
    // puede probarse directamente en JUnit puro, igual que filaDescribeCierre()/segmentoParcial().
    static String terminalEnTexto(int linea, String textoNorm) {
        // OJO: las terminales NO se sacan de l.estaciones.get(0)/get(size-1) -- estaciones.json (si
        // existe) reemplaza la lista de esa línea y agrega andenes "soloMapa" (2ª plataforma) AL FINAL,
        // así que el último elemento deja de ser la terminal real (p. ej. L1 termina en 51 estaciones
        // con "Insurgentes soloMapa" al final, no "El Caminero"). Planificador.terminales() son los
        // nombres canónicos curados a mano, inmunes a ese reordenamiento.
        String[] term = Planificador.terminales(linea);
        if (term == null) return null;
        boolean direccional = textoNorm.contains("direccion") || textoNorm.contains("sentido")
                || textoNorm.contains("hacia");
        if (!direccional) return null;   // sin marca de sentido = ambos (cierre total, se parte la línea)
        String t1 = Planificador.norm(term[0]);
        String t2 = Planificador.norm(term[1]);
        int dpos = Integer.MAX_VALUE;
        for (String w : new String[]{"direccion", "sentido", "hacia"}) {
            int p = textoNorm.indexOf(w);
            if (p >= 0 && p < dpos) dpos = p;
        }
        // La terminal del sentido es la que se nombra JUSTO tras la marca de dirección.
        String pick = null; int best = Integer.MAX_VALUE;
        for (String t : new String[]{t1, t2}) {
            if (t.length() < 4) continue;
            int p = textoNorm.indexOf(t);
            if (p >= dpos && p < best) { best = p; pick = t; }
        }
        if (pick != null) return pick;
        if (t1.length() >= 4 && textoNorm.contains(t1)) return t1;
        if (t2.length() >= 4 && textoNorm.contains(t2)) return t2;
        return null;
    }

    /**
     * Lee las 3 tablas de ServicioMB (elevadores, estaciones en mantenimiento y estado del
     * servicio) y arma las afectaciones. Solo el estado del servicio con "sin servicio"
     * bloquea estaciones para el ruteo (en tiempo real); elevadores y cierres programados no.
     */
    private void procesarFilas(String jsonValue) {
        String payload;
        try { payload = (String) new JSONTokener(jsonValue).nextValue(); }
        catch (Exception e) { payload = jsonValue != null ? jsonValue : ""; }

        JSONArray rows = null;
        try { rows = new JSONObject(payload).optJSONArray("rows"); } catch (org.json.JSONException ignore) {}

        Set<String> afect = afectAcc;
        List<Manifestaciones.Afectacion> lista = listaAcc;
        List<String> resumenLista = resumenAcc;

        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String tipo = r.optString("tipo", "");
                int nlinea = -1;
                try { nlinea = Integer.parseInt(r.optString("linea", "").trim()); } catch (NumberFormatException ignore) {}
                String lineaLabel = nlinea > 0 ? getString(R.string.manifest_linea_fmt, String.valueOf(nlinea)) : "";

                Manifestaciones.Afectacion a;
                if ("elevador".equals(tipo)) {
                    String estacion = limpiar(r.optString("estacion", ""));
                    if (estacion.isEmpty() || Planificador.norm(estacion).equals("estacion")) continue;
                    String direccion = limpiar(r.optString("direccion", ""));
                    String motivo = limpiar(r.optString("motivo", ""));
                    String fecha = limpiar(r.optString("extra", ""));
                    String info = motivo;
                    if (!fecha.isEmpty() && !Planificador.norm(fecha).equals("por definir"))
                        info = info.isEmpty() ? fecha : info + " · " + fecha;
                    // Elevador = solo para movilidad reducida (se filtra al mostrar).
                    a = new Manifestaciones.Afectacion(lineaLabel, nlinea, estacion,
                            getString(R.string.afect_elevador), direccion, info, true);
                    // Bloqueo de movilidad reducida SOLO en líneas con elevador (L1,2,3,5,6).
                    // L4 y L7 son de piso bajo a nivel de suelo: sin esa barrera.
                    if (nlinea == 1 || nlinea == 2 || nlinea == 3 || nlinea == 5 || nlinea == 6)
                        agregarSentido(porSentidoMRAcc, nlinea, estacion, direccionEfectiva(nlinea, direccion, motivo));

                } else if ("mantenimiento".equals(tipo)) {
                    String estacion = limpiar(r.optString("estacion", ""));
                    if (estacion.isEmpty() || Planificador.norm(estacion).equals("estacion")) continue;
                    String direccion = limpiar(r.optString("direccion", ""));
                    String motivo = limpiar(r.optString("motivo", ""));
                    String periodo = limpiar(r.optString("extra", ""));
                    if (!vigenteHoy(periodo)) continue;   // solo cierres de hoy, no los programados a futuro
                    String info = motivo;
                    if (!periodo.isEmpty()) info = info.isEmpty() ? periodo : info + " · " + periodo;
                    a = new Manifestaciones.Afectacion(lineaLabel, nlinea, estacion,
                            getString(R.string.afect_mantenimiento_est), direccion, info, false,
                            Manifestaciones.C_MANTENIMIENTO);
                    // Cierre por mantenimiento vigente hoy: bloquea el ruteo por sentido
                    // ("ambos sentidos" = toda la estación; una terminal = solo ese carril).
                    agregarSentido(porSentidoAcc, nlinea, estacion, direccionEfectiva(nlinea, direccion, motivo));

                } else if ("estado".equals(tipo)) {
                    String estado = limpiar(r.optString("estado", ""));
                    String estaciones = limpiar(r.optString("estaciones", ""));
                    String info = limpiar(r.optString("info", ""));
                    estadoFilas++;   // fila de estado leída (confirma que se pudo cargar la tabla)
                    String ne = Planificador.norm(estado);
                    String nEsta = Planificador.norm(estaciones);
                    // Normal = sin afectación: NO se avisa (única excepción "L# Servicio Regular / Ninguna").
                    boolean normal = ne.isEmpty() || ne.equals("estado") || ne.contains("estado del servicio")
                            || ne.contains("actualizacion") || ne.contains("servicio regular")
                            || ne.contains("sin afect") || nEsta.equals("estaciones afectadas")
                            || nEsta.equals("ninguna");
                    if (normal) continue;
                    // La tabla de Estado del Servicio no tiene columna de dirección (va en la info).
                    a = new Manifestaciones.Afectacion(lineaLabel, nlinea, estaciones, estado, "", info, false);

                    // Ruteo en tiempo real: bloquea (y parte la línea) si el servicio está CORTADO.
                    // OJO: "Retraso en el servicio ... por manifestantes" = la línea SIGUE corriendo
                    // (solo con demora) → NO se corta. Solo se corta cuando el ESTADO es Manifestación /
                    // Sin servicio / Cerrado / Suspendido, o el texto dice explícitamente sin servicio.
                    // 'retraso' se checa contra sev (estado+info+estaciones), NO solo el estado: el
                    // "Estado" puede venir como "Intervención en la estación" (categoría genérica de la
                    // página oficial) mientras la columna "info" aclara que es solo demora ("retraso en
                    // el servicio por congestionamiento vial... servicio lento").
                    String sev = Planificador.norm(estado + " " + info + " " + estaciones);
                    boolean retraso = sev.contains("retraso") || sev.contains("demora")
                            || sev.contains("lento") || ne.contains("regular");
                    // OJO: "intervención en la estación" YA NO cuenta como cierre por sí sola. La página
                    // oficial suele partir un solo incidente en VARIAS filas (una por estación/aspecto):
                    // la fila con esa etiqueta puede no traer su propia explicación de que es demora (esa
                    // vive en OTRA fila del mismo ciclo, p. ej. "Obstrucción de carril"/"Retraso en el
                    // servicio") -- caso real: Indios Verdes/Deportivo 18 de Marzo con L1 en servicio
                    // normal, bloqueada de más y partiendo la línea (impedía CUALQUIER ruta por ahí, ni
                    // siquiera las que no necesitan el tramo realmente afectado). Nunca fue parte de la
                    // lista oficial de cierres reales (ver comentario de arriba: Manifestación/Sin
                    // servicio/Cerrado/Suspendido/bloqueo/plantón) y es demasiado ambigua para bloquear
                    // sola: puede ser desde una intervención médica hasta un objeto olvidado.
                    // Única fuente de verdad (ver filaDescribeCierre más abajo): bloquearRutaL4() la
                    // reutiliza exactamente igual, para que su propio chequeo de cierre nunca pueda
                    // divergir del de aquí (defecto confirmado y corregido: antes bloquearRutaL4() tenía
                    // su propia lista de palabras, más corta y con una palabra ajena -- "cancela" -- que
                    // esta condición nunca comprueba).
                    boolean sinServicio = filaDescribeCierre(sev, estado);
                    // Obstrucción de carril: afecta UN solo sentido (un carril), NO toda la estación ni
                    // parte la línea. Sin dirección clara en el texto, no se bloquea nada.
                    boolean obstruccionCarril = !retraso && sev.contains("obstru") && sev.contains("carril");
                    // Solo un bloqueo FÍSICO real (manifestación/bloqueo/plantón: gente/objetos en la
                    // vía) justifica PARTIR la línea (cortarAlrededor/cortarLineaServicios): ahí la
                    // unidad literalmente no puede pasar. "Sin servicio"/"cerrada"/"suspendida" en una
                    // estación puntual normalmente solo impide SUBIR/BAJAR ahí -- el camión puede seguir
                    // de largo hacia las demás estaciones sin problema, así que NO debe desconectar el
                    // resto de la línea (caso real: "Ayuntamiento" sin servicio hacia El Caminero volvía
                    // "no hay ruta" incluso viajes que ni pasaban por ese tramo).
                    boolean bloqueoFisico = !retraso && (ne.contains("manifestacion")
                            || sev.contains("bloqueo") || sev.contains("bloquead") || sev.contains("planton"));
                    if (sinServicio || obstruccionCarril) {
                        // El sentido va en el TEXTO (p. ej. "sin servicio en sentido a Indios Verdes"):
                        // si menciona una terminal, se bloquea SOLO ese carril; si no, ambos sentidos
                        // (salvo obstrucción de carril, que jamás bloquea ambos ni parte la línea).
                        String terminalSentido = terminalEnTexto(nlinea, sev);
                        boolean ambos = !obstruccionCarril && terminalSentido == null;   // cierre total = parte la línea
                        boolean sinSentido = obstruccionCarril && terminalSentido == null; // no atribuible: no bloquea
                        String nEst = Planificador.norm(estaciones);
                        if (sinSentido) {
                            // Obstrucción de carril sin dirección: solo se muestra, no bloquea ruteo.
                        } else if (nEst.contains("linea completa") || nEst.contains("toda la linea")
                                || (nEst.isEmpty() && terminalSentido != null)) {
                            // Caso real confirmado: "Por mantenimiento sin servicio en dirección a El
                            // Caminero" (L1) -- SIN una lista de estaciones puntual en la columna
                            // "estaciones" (nEst vacío), pero CON un cierre real (sinServicio) y una
                            // terminal identificada en el texto. Antes esto no marcaba NINGUNA estación
                            // (ni "línea completa" ni la lista puntual aplicaban): se trata igual que un
                            // cierre de línea completa, pero terminalSentido ya restringe el bloqueo a
                            // ESE sentido nada más (ver bloquearNn), no a ambos.
                            if (nlinea > 0) {
                                Linea l = GtfsRepository.porNumero(this, nlinea);
                                if (l != null) {
                                    for (Estacion e : l.estaciones)
                                        bloquearNn(nlinea, Planificador.norm(e.nombre), terminalSentido);
                                    // Línea completa fuera: corta todos los tramos (queda intransitable).
                                    // L4/L7 se rutean por servicios (couplet): los cortes se generan de la
                                    // secuencia real, no de la lista plana, para atrapar AMBAS ramas.
                                    // Solo si es un bloqueo FÍSICO real (ver bloqueoFisico arriba).
                                    if (ambos && bloqueoFisico) {
                                        if (porServicios(nlinea)) cortarLineaServicios(nlinea);
                                        else for (int k = 0; k + 1 < l.estaciones.size(); k++) {
                                            String key = Manifestaciones.claveCorte(nlinea,
                                                    Planificador.norm(l.estaciones.get(k).nombre),
                                                    Planificador.norm(l.estaciones.get(k + 1).nombre));
                                            if (key != null) cortesAcc.add(key);
                                        }
                                    }
                                }
                            }
                        } else if (!nEst.isEmpty() && !nEst.equals("ninguna")) {
                            // SOLO las estaciones de ESTA línea (nlinea): antes se buscaba en el catálogo
                            // de TODAS las líneas de Metrobús juntas, así que una afectación de L1 en
                            // "La Raza" también bloqueaba la "La Raza" de L3 (mismo nombre, otra línea).
                            Linea l = nlinea > 0 ? GtfsRepository.porNumero(this, nlinea) : null;
                            if (l != null) {
                                // Caso real confirmado en dispositivo: la columna "Estaciones afectadas"
                                // puede nombrar un RANGO "A - B" (p. ej. "Poniente 128 - Cuitláhuac"), no
                                // una lista ni un nombre compuesto -- ver rangoEstacionesCerradas(). Si
                                // ambos extremos resuelven contra el catálogo de esta línea, se bloquea el
                                // tramo completo (inclusive); si no, se cae al camino de siempre.
                                int[] rango = rangoEstacionesCerradas(estaciones, l.estaciones);
                                if (rango != null) {
                                    for (int k = rango[0]; k <= rango[1]; k++) {
                                        String nn = Planificador.norm(l.estaciones.get(k).nombre);
                                        bloquearNn(nlinea, nn, terminalSentido);
                                        if (ambos && bloqueoFisico) cortarAlrededor(nlinea, nn);
                                    }
                                } else {
                                    // Elementos de la lista, YA separados (ver itemsEstaciones()): la
                                    // comparación es por IGUALDAD EXACTA de nombre normalizado contra el
                                    // catálogo, nunca por subcadena -- evita que "Insurgentes" calce contra
                                    // una fila que en realidad dice "Teatro de los Insurgentes" (estación
                                    // real y distinta de L1, a ~6 km).
                                    java.util.Set<String> items = new HashSet<>(itemsEstaciones(estaciones));
                                    for (Estacion e : l.estaciones) {
                                        String nn = Planificador.norm(e.nombre);
                                        if (!nn.isEmpty() && items.contains(nn)) {
                                            bloquearNn(nlinea, nn, terminalSentido);
                                            // Parte la línea SOLO si es un bloqueo físico real (ver
                                            // bloqueoFisico arriba); una estación "sin servicio"/cerrada
                                            // normal se puede seguir de largo sin bajar/subir, así que no
                                            // se desconecta el resto.
                                            if (ambos && bloqueoFisico) cortarAlrededor(nlinea, nn);
                                        }
                                    }
                                }
                            }
                        }
                        // Un carril obstruido puede degradar el servicio a un tramo reducido ("servicio
                        // de A a B" en la info, p. ej. "servicio de El Caminero a Buenavista, por
                        // operativo en Insurgentes Norte"): ahí SÍ conviene enrutar solo por ese tramo
                        // y dejar bloqueado (inhabilitado) el resto, igual que con un corte total.
                        // "A - B" (guion como separador de extremos, p. ej. "Indios Verdes - Dr Gálvez")
                        // se convierte a "A a B" ANTES de normalizar: Planificador.norm() reemplaza '-'
                        // por espacio (destruye el separador) y bloquearTramos() reconoce el tramo por la
                        // palabra " a " entre los dos nombres -- sin esto el chunk quedaba sin "a" y se
                        // descartaba entero (ap<3), aunque el marcador "servicio de"/"provisional de" sí
                        // se reconociera. Solo el guion RODEADO DE ESPACIOS cuenta como separador de rango
                        // (uno pegado a letras, como "Ex-Hacienda", no se toca).
                        //
                        // Defecto confirmado: "info" puede traer VARIAS oraciones ajenas entre sí,
                        // separadas por punto en el texto original (caso real de L7: "...a Hamburgo. Sin
                        // servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac") -- Planificador.norm()
                        // quita los puntos, así que si se normalizara "info" COMPLETO de una sola vez, la
                        // segunda oración quedaba pegada como cola de la primera ANTES de que
                        // segmentoParcial()/" a "/" y " pudieran distinguirlas: el fragmento resultante ya
                        // no es ninguna de las dos oraciones reales, y bloquearTramosServicios() podía
                        // resolver un tramo por COINCIDENCIA DE SUBCADENA contra ese texto mezclado (p. ej.
                        // "Glorieta Cuitláhuac" apareciendo al final de un bloque que en realidad describía
                        // un tramo distinto). Se procesa cada oración de "info" POR SEPARADO (ver
                        // oracionesDe(), que no parte abreviaturas reales del catálogo como "Av."/"Dr."/
                        // "Sta."/"Gustavo A. Madero"); una oración sin marcador de tramo reconocido
                        // (segmentoParcial devuelve null) simplemente no aporta nada, en vez de arriesgar
                        // un rango mal armado por fusión accidental con la oración siguiente.
                        //
                        // Defecto confirmado adicional: el texto oficial a veces usa COMA en vez de punto
                        // para separar dos afirmaciones ajenas (caso real: "...a Hamburgo, Sin servicio la
                        // ruta..."). oracionesDe() solo divide por punto, así que sin esto el mismo defecto
                        // de fusión reaparecía -- ver P_COMA_NUEVA_AFIRMACION.
                        for (String oracion : oracionesDe(conLimitesDeOracion(info))) {
                            String infoParaTramos = oracion.replaceAll("[\\s\\u00A0]+-[\\s\\u00A0]+", " a ");
                            bloquearTramos(Planificador.norm(infoParaTramos), afect, ambos ? nlinea : 0);
                            // L4 y L7 se rutean por SERVICIOS (couplet/ramas), no por un tramo lineal:
                            // bloquearTramos las salta. Un aviso que nombre ESTACIONES reales ("Servicio de
                            // Campo Marte a Glorieta Cuitláhuac") se resuelve aquí usando las secuencias de
                            // RutasMixtas como referencia de orden (ver bloquearTramosServicios). Sigue su
                            // propio Planificador.norm(oracion) sin la sustitución de guion -- ver nota en
                            // bloquearTramosServicios sobre ese gap, ya acotado por oración.
                            if (porServicios(nlinea)) bloquearTramosServicios(nlinea, Planificador.norm(oracion), afect);
                        }
                        // L4 además puede nombrar la ruta por su NOMBRE ("Ruta Norte"/"Ruta Sur") en vez
                        // de estaciones: bloquea sus estaciones exclusivas (ver bloquearRutaL4).
                        if (sinServicio) bloquearRutaL4(nlinea, sev, info, lineaLabel, estado, afect, extraPorEstacionAcc);
                    }
                } else {
                    continue;
                }

                lista.add(a);
                resumenLista.add((lineaLabel.isEmpty() ? "" : lineaLabel)
                        + (a.lugar.isEmpty() ? "" : " · " + a.lugar));
            }
        }
    }

    private static final java.util.regex.Pattern P_DIR = java.util.regex.Pattern.compile(
            "(?:direcci[oó]n|sentido|hacia)\\s*:?\\s*([^.;\\n]{2,40})", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    /** ¿El "Periodo de Cierre" incluye la fecha de hoy? (si no se puede interpretar, se muestra). */
    private static boolean vigenteHoy(String periodo) {
        if (periodo == null || periodo.trim().isEmpty()) return true;
        String p = Planificador.norm(periodo);
        int mes = -1;
        for (int m = 0; m < 12; m++) if (p.contains(MESES[m])) { mes = m; break; }
        if (mes < 0) return true;
        java.util.Calendar c = java.util.Calendar.getInstance();
        if (mes != c.get(java.util.Calendar.MONTH)) return false;
        java.util.List<Integer> dias = new java.util.ArrayList<>();
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\d{1,2}").matcher(p);
        while (mm.find()) { dias.add(Integer.parseInt(mm.group())); }
        if (dias.isEmpty()) return true;
        int hoy = c.get(java.util.Calendar.DAY_OF_MONTH);
        return hoy >= java.util.Collections.min(dias) && hoy <= java.util.Collections.max(dias);
    }

    /**
     * Filtro por palabras: quita una etiqueta de columna que se haya pegado al valor
     * (respaldo por si el layout responsivo de la fuente cambia) para que la notificación
     * salga limpia. También colapsa espacios.
     */
    private static String limpiar(String v) {
        if (v == null) return "";
        v = v.replace(' ', ' ').replaceAll("[\\s\\u00A0]+", " ").trim();
        v = v.replaceFirst("(?i)^(estaciones afectadas|informaci[oó]n adicional|sentido de circulaci[oó]n"
                + "|periodo de cierre|direcci[oó]n\\s*/?\\s*sentido|direcci[oó]n|estado|estaci[oó]n|motivo|l[ií]nea)\\s*", "");
        return v.trim();
    }

    /** Extrae "Dirección/Sentido/Hacia X" del texto (o "" si no aparece). */
    private static String extraerDireccion(String texto) {
        if (texto == null) return "";
        java.util.regex.Matcher m = P_DIR.matcher(texto);
        return m.find() ? m.group(1).trim() : "";
    }

    // Palabra justo antes de un punto que NO marca fin de oración -- son abreviaturas REALES del
    // catálogo (confirmadas por grep contra estaciones.json): "Av. Talismán" (L7), "Dr. Gálvez" (L1),
    // "Dr. Márquez" (L3), "Pueblo Sta. Cruz Atoyac" (L3). Una sola letra ("Gustavo A. Madero", L6/L7)
    // se cubre aparte, no por esta lista.
    private static final Set<String> ABREV_PUNTO = new HashSet<>(Arrays.asList("av", "dr", "sta"));

    /**
     * Divide un texto en "oraciones" por punto, sin partir una abreviatura real del catálogo (ver
     * {@link #ABREV_PUNTO} y el caso de una sola letra, "Gustavo A. Madero"). Usada para que un
     * "info" con VARIAS afirmaciones ajenas entre sí, separadas por punto en el texto original
     * ("Servicio de A a B. Sin servicio la ruta C a D."), no se procese como un solo bloque fusionado
     * -- {@link Planificador#norm} quita los puntos, así que normalizar el texto COMPLETO de una
     * sola vez pegaría la segunda oración como cola de la primera ANTES de que la extracción de
     * tramos pudiera distinguirlas (riesgo real: coincidencia de subcadena entre fragmentos de
     * oraciones distintas -- ver la nota en el llamador de {@link #bloquearTramosServicios}).
     *
     * <p>Sin punto en el texto, devuelve una lista de un solo elemento (el texto completo), igual
     * que el comportamiento de siempre.
     */
    static List<String> oracionesDe(String texto) {
        List<String> out = new ArrayList<>();
        if (texto == null) return out;
        int inicio = 0;
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) != '.') continue;
            int j = i - 1;
            while (j >= inicio && Character.isLetter(texto.charAt(j))) j--;
            String palabra = texto.substring(j + 1, i).toLowerCase();
            boolean abreviatura = palabra.length() == 1 || ABREV_PUNTO.contains(palabra);
            if (abreviatura) continue;
            out.add(texto.substring(inicio, i + 1));
            inicio = i + 1;
        }
        if (inicio < texto.length()) out.add(texto.substring(inicio));
        return out;
    }

    /**
     * Defecto confirmado (caso real de L7: "...servicio de Indios Verdes y Hospital Infantil la
     * Villa a Hamburgo, Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac."): el texto
     * oficial a veces separa dos afirmaciones AJENAS entre sí con COMA en vez de punto --
     * {@link #oracionesDe} solo reconoce el punto como límite de oración, así que sin esto las dos
     * quedaban pegadas y se reproducía el mismo defecto de fusión de oraciones ya corregido para el
     * caso con punto (coincidencia de subcadena entre fragmentos de afirmaciones distintas).
     *
     * <p>Se trata una coma como límite SOLO cuando va seguida de una de las frases que
     * {@link #segmentoParcial} ya reconoce como inicio de una afirmación de tramo/cierre propia
     * ("sin servicio", "servicio de", "servicio provisional", "servicio parcial", "solo hay
     * servicio", "opera de") -- nunca una coma cualquiera (p. ej. "Por bloqueo, servicio de..." SÍ
     * separa porque "servicio de" es una de esas frases; "México-Tenochtitlán, Línea 3 y..." NO,
     * porque "Línea 3" no lo es). Evita partir listas o aposiciones comunes del texto oficial que no
     * tienen nada que ver con un límite de oración.
     */
    private static final java.util.regex.Pattern P_COMA_NUEVA_AFIRMACION = java.util.regex.Pattern.compile(
            "(?i),\\s+(?=(sin servicio|servicio de|servicio provisional|servicio parcial"
                    + "|solo hay servicio|opera de)\\b)");

    /** Convierte, dentro de "texto", una coma seguida de una frase de inicio de afirmación
     *  reconocida (ver {@link #P_COMA_NUEVA_AFIRMACION}) en un punto -- paso previo a
     *  {@link #oracionesDe}, extraído aparte para poder probarlo directamente. */
    static String conLimitesDeOracion(String texto) {
        if (texto == null) return null;
        return P_COMA_NUEVA_AFIRMACION.matcher(texto).replaceAll(". ");
    }

    /**
     * Extrae el segmento "A a B (y de C a D)" de un texto de afectación ya normalizado, listo para
     * partir por {@code " y "} y sacar los tramos "X a Y" -- compartido por {@link #bloquearTramos}
     * (líneas troncales lineales) y {@link #bloquearTramosServicios} (L4/L7, ruteadas por servicios).
     * {@code null} si el texto no describe un servicio parcial.
     */
    static String segmentoParcial(String normFull) {   // paquete-visible: reutilizada por las pruebas JUnit
        // "Servicio de A a B" a secas (sin "solo hay"/"provisional" delante) TAMBIÉN cuenta como
        // tramo reducido -- caso real confirmado: circuitos de emergencia reales ("Servicio de
        // Tenayuca a Buenavista Y de Pueblo de Santa Cruz a Cuauhtémoc") no siempre incluyen una
        // palabra como "circuito"/"manifestación" en el texto, así que exigirla (intento anterior)
        // dejaba SIN bloquear las estaciones intermedias de un circuito real. La protección contra
        // falsos positivos ya la da el propio requisito de quien use el resultado: X y Y deben mapear
        // a estaciones REALES -- si no, no se arma ningún rango y no se bloquea nada (autolimitado).
        boolean parcial = normFull.contains("solo hay servicio") || normFull.contains("servicio provisional")
                || normFull.contains("servicio parcial") || normFull.contains("opera de")
                || normFull.contains("provisional")
                || (normFull.contains("servicio de") && normFull.contains(" a "));
        if (!parcial) return null;

        // El marcador se busca en orden de especificidad: "servicio provisional/parcial de" puede
        // aparecer en el mismo lugar que un "servicio de" más genérico (p. ej. "servicio provisional
        // de Indios Verdes - Dr Gálvez"), y buscar solo "servicio de" nunca encontraba esa frase real
        // porque la palabra intermedia ("provisional"/"parcial") rompe la subcadena contigua -- el
        // texto SÍ se detectaba como parcial (ver 'parcial' arriba) pero la extracción fallaba y
        // devolvía null sin bloquear nada.
        String[] marcadores = {"servicio provisional de", "servicio parcial de", "servicio de", "opera de"};
        int idx = -1;
        String marcador = null;
        for (String m : marcadores) {
            int p = normFull.indexOf(m);
            if (p >= 0 && (idx < 0 || p < idx)) { idx = p; marcador = m; }
        }
        if (idx < 0) return null;
        String seg = normFull.substring(idx);
        if (seg.length() > 300) seg = seg.substring(0, 300);
        seg = seg.replaceFirst("^" + java.util.regex.Pattern.quote(marcador) + "\\s*", "");
        // Caso real confirmado: un aviso puede traer VARIAS oraciones "Servicio de X a Y" seguidas
        // (p. ej. "...y de El Caminero a Sonora. Servicio de Cuauhtémoc a Pueblo de Santa Cruz.") en
        // vez de una sola unida con "y" -- norm() ya quitó los puntos, así que la segunda oración
        // quedaba pegada como cola del tramo anterior (un texto larguísimo que no calzaba con NINGUNA
        // estación), perdiendo esos tramos reales y bloqueando de más casi toda la troncal. Cualquier
        // marcador POSTERIOR también separa tramos, igual que "y".
        return seg.replaceAll("\\bservicio provisional de\\b", " y ")
                .replaceAll("\\bservicio parcial de\\b", " y ")
                .replaceAll("\\bservicio de\\b", " y ")
                .replaceAll("\\bopera de\\b", " y ");
    }

    private static final String[] MARCADORES_RUTA_SIN_SERVICIO =
            {"sin servicio la ruta", "sin servicio en la ruta"};

    /**
     * Reconoce "sin servicio la ruta X a Y" (y de Z a W...) como un CIERRE DIRECTO del tramo X-Y --
     * semántica OPUESTA a {@link #segmentoParcial}: ahí "servicio de X a Y" describe el tramo que SÍ
     * opera (el resto se bloquea); aquí el texto declara explícitamente el tramo que NO opera. Caso
     * real confirmado: "Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac" (H72, L7) -- no
     * contiene "servicio de" (es "sin servicio la ruta"), así que {@link #segmentoParcial} siempre
     * devuelve {@code null} para este texto y, hasta esta corrección, la oración no bloqueaba nada.
     * {@code null} si el texto no describe este patrón. Paquete-visible: reutilizada por las pruebas.
     */
    static String rutaSinServicio(String normFull) {
        int idx = -1;
        String marcador = null;
        for (String m : MARCADORES_RUTA_SIN_SERVICIO) {
            int p = normFull.indexOf(m);
            if (p >= 0 && (idx < 0 || p < idx)) { idx = p; marcador = m; }
        }
        if (idx < 0) return null;
        String seg = normFull.substring(idx + marcador.length()).trim();
        if (seg.length() > 300) seg = seg.substring(0, 300);
        if (!seg.contains(" a ")) return null;
        return seg;
    }

    /**
     * Detecta "solo hay servicio de A a B (y de C a D)" y bloquea el complemento
     * (las estaciones del tramo sin servicio) en la línea correspondiente.
     */
    private void bloquearTramos(String normFull, Set<String> afect, int cortarLineaHint) {
        String seg = segmentoParcial(normFull);
        if (seg == null) return;

        List<Linea> lineas = GtfsRepository.getLineas(this);
        List<CandidatoLinea> candidatos = new ArrayList<>();
        for (Linea l : lineas) if (!porServicios(l.numero)) candidatos.add(new CandidatoLinea(l.numero, l.estaciones));
        java.util.Map<Integer, List<Integer>> fueraPorLinea = indicesFueraDeRangoPorLinea(seg, candidatos);

        // Bloquea las estaciones fuera de los rangos en servicio y CORTA los tramos que quedan
        // sin servicio (servicio parcial = la línea está físicamente partida entre los rangos).
        for (Linea l : lineas) {
            List<Integer> fuera = fueraPorLinea.get(l.numero);
            if (fuera == null) continue;
            for (int k : fuera) {
                String nn = Planificador.norm(l.estaciones.get(k).nombre);
                // OJO: la clave lleva la línea (igual que bloquearNn/Manifestaciones.clave()) -- sin
                // el prefijo, esta estación nunca calzaba con "linea|estacion" y el bloqueo real
                // (Manifestaciones.bloqueadas(), usado por el mapa y el planificador) no la veía,
                // aunque el corte físico (cortesAcc, abajo) sí la aislara del ruteo.
                afect.add(Planificador.claveTerminal(l.numero) + "|" + nn);
                // Aísla el tramo muerto cortando sus tramos adyacentes en esta línea.
                if (k > 0) {
                    String key = Manifestaciones.claveCorte(l.numero, nn,
                            Planificador.norm(l.estaciones.get(k - 1).nombre));
                    if (key != null) cortesAcc.add(key);
                }
                if (k + 1 < l.estaciones.size()) {
                    String key = Manifestaciones.claveCorte(l.numero, nn,
                            Planificador.norm(l.estaciones.get(k + 1).nombre));
                    if (key != null) cortesAcc.add(key);
                }
            }
        }
    }

    /** Candidato de línea para resolver un tramo: su número y su lista de estaciones (en orden),
     *  sin necesitar una {@link Linea} real -- esta última no se puede construir en una prueba JVM
     *  pura (su constructor llama a {@code android.graphics.Color.parseColor()}, que revienta sin
     *  Robolectric). {@link #indicesFueraDeRangoPorLinea} se prueba con una lista de
     *  {@code CandidatoLinea} armada a mano; {@link #bloquearTramos} la alimenta con
     *  {@code GtfsRepository.getLineas(this)} -- mismo algoritmo, sin duplicarlo. */
    static final class CandidatoLinea {
        final int numero;
        final List<Estacion> estaciones;
        CandidatoLinea(int numero, List<Estacion> estaciones) { this.numero = numero; this.estaciones = estaciones; }
    }

    /**
     * Núcleo PURO de {@link #bloquearTramos} (sin Context/GtfsRepository/efectos sobre
     * afect/cortesAcc): dado el segmento de tramos ya extraído por {@link #segmentoParcial} y las
     * líneas candidatas YA FILTRADAS por {@link #porServicios} (en el mismo orden en que
     * bloquearTramos() las recorre), resuelve cada tramo "X a Y" contra la PRIMERA línea candidata
     * cuyo catálogo contenga ambos extremos (ver {@link #idxEstacion}) -- igual que antes, una
     * línea que ya resolvió un tramo no vuelve a probarse para los tramos siguientes de esa MISMA
     * línea (si hay varios tramos "y"-separados, cada uno busca su propia línea desde el principio
     * de la lista) -- y devuelve, por cada línea que resolvió AL MENOS un tramo, los índices que
     * quedan fuera de los rangos "en servicio" resultantes.
     *
     * <p>Una línea que no resolvió NINGÚN tramo (ni el suyo ni ninguno) no aparece en el resultado
     * -- {@code bloquearTramos()} la salta por completo, no bloquea nada por esta vía (distinto de
     * aparecer con una lista vacía, que significaría "sí se reconoció un rango, y cubre toda la
     * línea").
     *
     * <p>Esta función es la MISMA que usa producción (ver {@link #bloquearTramos}, que la llama
     * directamente) -- no hay una copia aparte en las pruebas.
     */
    static java.util.Map<Integer, List<Integer>> indicesFueraDeRangoPorLinea(String seg, List<CandidatoLinea> lineas) {
        java.util.Map<Integer, List<int[]>> corridos = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> resueltosPorLinea = new java.util.HashMap<>();
        int totalTramos = 0;
        for (String chunk : seg.split("\\s+y\\s+")) {
            int ap = chunk.indexOf(" a ");
            if (ap < 3) continue;
            String x = chunk.substring(0, ap).trim();
            String y = chunk.substring(ap + 3).trim();
            if (x.length() < 3 || y.length() < 3) continue;
            totalTramos++;
            for (CandidatoLinea l : lineas) {
                int ix = idxEstacion(l.estaciones, x), iy = idxEstacion(l.estaciones, y);
                if (ix >= 0 && iy >= 0) {
                    corridos.computeIfAbsent(l.numero, z -> new ArrayList<>())
                            .add(new int[]{Math.min(ix, iy), Math.max(ix, iy)});
                    resueltosPorLinea.merge(l.numero, 1, Integer::sum);
                    break;   // primera línea que contiene ambos extremos
                }
            }
        }
        java.util.Map<Integer, List<Integer>> fueraPorLinea = new java.util.HashMap<>();
        for (CandidatoLinea l : lineas) {
            List<int[]> rangos = corridos.get(l.numero);
            if (rangos == null) continue;
            // Defecto confirmado (caso real: L3, "Servicio de Tenayuca a Poniente 134 y de Pueblo de
            // Santa Cruz a Héroe Nacozari" -- el nombre oficial "Pueblo de Santa Cruz" no calza con el
            // nombre real del catálogo, "Pueblo Sta. Cruz Atoyac"): si el aviso describe VARIOS tramos
            // "en servicio" unidos por "y" pero esta línea solo resolvió ALGUNOS de ellos, el tramo no
            // resuelto NO significa "fuera de servicio" -- tratar el resto de la línea como bloqueado
            // por esta vía bloqueaba de más toda la mitad de la línea (en el caso real, desde Poniente
            // 128 hasta el propio Pueblo Sta. Cruz Atoyac, la terminal). Se prefiere no bloquear nada
            // por esta vía a bloquear de más por una resolución parcial: solo se calcula "fuera" para
            // una línea que resolvió TODOS los tramos descritos en el aviso.
            if (!resueltosPorLinea.getOrDefault(l.numero, 0).equals(totalTramos)) continue;
            List<Integer> fuera = new ArrayList<>();
            for (int k = 0; k < l.estaciones.size(); k++) {
                boolean corre = false;
                for (int[] r : rangos) if (k >= r[0] && k <= r[1]) { corre = true; break; }
                if (!corre) fuera.add(k);
            }
            fueraPorLinea.put(l.numero, fuera);
        }
        return fueraPorLinea;
    }

    /**
     * Corta los tramos adyacentes a una estación cerrada en AMBOS sentidos: parte la línea ahí,
     * de modo que el planificador NO pueda pasar de largo por la zona (situación de manifestación /
     * "sin servicio"). El mantenimiento NO llama aquí (la estación se salta, pero el corredor sigue).
     */
    /** ¿La línea se rutea por servicios mixtos (couplet)? L4 y L7 no siguen el orden de l.estaciones. */
    private static boolean porServicios(int nlinea) { return nlinea == 4 || nlinea == 7; }

    /** Corta TODOS los tramos de una línea ruteada por servicios (L4/L7) usando la secuencia real
     *  de cada servicio (RutasMixtas), que es la adyacencia que ve el grafo (ambas ramas del couplet). */
    private void cortarLineaServicios(int nlinea) {
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            for (int k = 0; k + 1 < sm.estaciones.length; k++) {
                if (sm.lineas[k] != nlinea || sm.lineas[k + 1] != nlinea) continue;
                String key = Manifestaciones.claveCorte(nlinea,
                        Planificador.norm(sm.estaciones[k]), Planificador.norm(sm.estaciones[k + 1]));
                if (key != null) cortesAcc.add(key);
            }
        }
    }

    /**
     * L4 no es troncal lineal (ver RutasMixtas): cuando el texto de la afectación dice que se
     * CANCELA una de sus rutas con nombre real (Ruta Norte / Ruta Sur), bloquea las estaciones
     * EXCLUSIVAS de esa ruta ({@link RutasMixtas#rutaL4}) y corta sus tramos -- deja la troncal
     * compartida (Buenavista, Delegación Cuauhtémoc, México Tenochtitlan, San Lázaro) en servicio
     * por la otra ruta. Caso real confirmado: "se cancela el servicio de la ruta sur" -> de Plaza
     * de la República a Moctezuma fuera de servicio (la Ruta Aeropuerto comparte ese mismo trazo
     * ahí, así que ya queda cubierta: rutaL4() agrupa Ruta Sur y Ruta Aeropuerto como "sur").
     *
     * <p>Además de bloquear (afect) y cortar el grafo, registra una {@link Manifestaciones.Afectacion}
     * SINTÉTICA por estación en {@code extra}: el texto real de ServicioMB solo dice "Ruta Sur", sin
     * nombrar cada parada, así que sin esto {@link Manifestaciones#afectacionEstacion} (usada por la
     * carta del mapa) no encontraba ningún motivo que mostrar para, p. ej., "Eje Central" -- pese a
     * que la estación sí quedaba bloqueada/gris (eso lee {@code afect}). {@code extra} es un mapa
     * APARTE de listaAcc a propósito: si estas ~15 estaciones se agregaran a listaAcc, la tabla de
     * "Estado del servicio" (que junta el 'lugar' de TODAS las filas de esa línea) terminaría
     * listando las 15 una por una en vez de solo la fila real scrapeada.
     */
    private void bloquearRutaL4(int nlinea, String sev, String infoRaw, String lineaLabel, String estado,
                                Set<String> afect, java.util.Map<String, Manifestaciones.Afectacion> extra) {
        if (nlinea != 4) return;
        if (!filaDescribeCierre(sev, estado)) return;
        boolean[] rutas = rutasL4Cerradas(infoRaw);
        boolean sur = rutas[0], norte = rutas[1];
        if (!sur && !norte) return;
        Linea l = GtfsRepository.porNumero(this, nlinea);
        if (l == null) return;
        String info = sur ? "Servicio de la Ruta Sur cancelado" : "Servicio de la Ruta Norte cancelado";
        for (Estacion e : l.estaciones) {
            String nn = Planificador.norm(e.nombre);
            int r = RutasMixtas.rutaL4(nn);
            if (!((sur && r == 2) || (norte && r == 1))) continue;
            String key = Planificador.claveTerminal(nlinea) + "|" + nn;
            afect.add(key);
            cortarAlrededor(nlinea, nn);
            extra.put(key, new Manifestaciones.Afectacion(lineaLabel, nlinea, e.nombre, estado, "", info, false));
        }
    }

    /**
     * ÚNICA FUENTE DE VERDAD de "¿esta fila describe un cierre real del servicio?" -- exactamente
     * la misma lógica que antes vivía solo inline en {@code procesarFilas()} como la variable local
     * "sinServicio" (ver esa línea más arriba, ahora delegada aquí): ambos llamadores
     * ({@code procesarFilas()} para el ruteo general y {@link #bloquearRutaL4} para L4) comparten
     * esta función, así que no pueden divergir por construcción.
     *
     * <p>Defecto corregido en esta ronda: una versión anterior de este método ({@code
     * filaL4DescribeCierre(String sev)}, de un solo parámetro) reimplementaba su propia lista de
     * palabras -- más corta que "sinServicio" (le faltaban "cerrad", "no hay servicio", "planton" y
     * la detección de "Manifestación" como ESTADO) y con una palabra ajena ("cancela") que
     * "sinServicio" nunca comprueba -- y además no aplicaba la exclusión de "retraso". Un aviso real
     * de cierre que usara solo esas palabras faltantes (p. ej. "Ruta Sur cerrada por obras") hacía
     * que el gate general de procesarFilas() SÍ llamara a {@link #bloquearRutaL4} (sinServicio=true),
     * pero dentro de ella este método devolvía false y la función retornaba sin bloquear nada --
     * una regresión silenciosa introducida por mantener dos listas de palabras independientes.
     *
     * @param sev    "estado"+" "+"info"+" "+"estaciones" de la fila, YA normalizado
     *               ({@link Planificador#norm}) -- igual que el "sev" de procesarFilas().
     * @param estado columna "estado" de la fila, SIN normalizar (se normaliza aquí mismo): el
     *               exclusión de "retraso" por "regular" y la detección de "Manifestación" como
     *               ESTADO miran solo esta columna, no el texto combinado -- igual que "ne" en
     *               procesarFilas().
     */
    static boolean filaDescribeCierre(String sev, String estado) {
        String s = sev == null ? "" : sev;
        String ne = Planificador.norm(estado == null ? "" : estado);
        boolean retraso = s.contains("retraso") || s.contains("demora")
                || s.contains("lento") || ne.contains("regular");
        return !retraso && (
                   s.contains("sin servicio") || s.contains("cerrad")
                || s.contains("suspend") || s.contains("no hay servicio")
                || ne.contains("manifestacion")        // Manifestación como ESTADO = corta
                || s.contains("bloqueo") || s.contains("bloquead")
                || s.contains("planton"));
    }

    /**
     * ¿Qué ruta(s) de L4 (Sur/Norte) describe un aviso de "estado" cuyo CIERRE ya está confirmado
     * a nivel de fila (ver el chequeo "cierre" en {@link #bloquearRutaL4}, calculado sobre la fila
     * completa -- estado + info + estaciones, columnas CONFIRMADAS por el extractor real: ver
     * {@code JS_ESTADO}, {@code linea/estado/estaciones/info}, 4 columnas por fila -- antes de
     * llamar aquí). Dado eso, la única pregunta real es A CUÁL ruta se refiere, no si hay cierre.
     *
     * <p>Defecto corregido: antes se exigía que la mención de ruta ("ruta sur"/"ruta norte") y la
     * palabra de cierre aparecieran en la MISMA oración de {@code infoRaw} (separadas por '.'/'·').
     * Un aviso real puede describir la razón, la ruta y la confirmación del cierre en oraciones
     * CONSECUTIVAS distintas dentro de la misma columna "info" -- caso real confirmado: "Por
     * congestionamiento vial. Servicio de la Ruta Sur por México-Tenochtitlán, Línea 3 y
     * Ayuntamiento. Sin servicio por mantenimiento a carril confinado." -- "sin servicio" y "ruta
     * sur" quedan en oraciones distintas, así que la versión anterior nunca bloqueaba la Ruta Sur
     * pese a que el cierre de fila (sev) ya lo había confirmado.
     *
     * <p>Si "info" menciona SOLO una de las dos rutas, el cierre YA CONFIRMADO se le atribuye
     * directamente a esa ruta -- no hace falta que la palabra de cierre esté en la misma oración,
     * porque no hay ninguna OTRA ruta con la que pudiera confundirse. Si menciona AMBAS rutas (caso
     * real que motivó el chequeo por oración original: "Servicio de la ruta sur de San Pablo a San
     * Lázaro. Se cancela ruta norte." -- una corriendo, la otra cancelada, en el MISMO aviso), se
     * mantiene el chequeo por oración para no cerrar la que sigue en servicio.
     *
     * <p>HIPÓTESIS no verificada contra una fila real en vivo: que "info" mencionando una sola ruta,
     * combinado con el cierre YA confirmado a nivel de fila, significa confiablemente que ESA ruta
     * es la cerrada (y no, p. ej., una ruta que sigue en servicio mientras el cierre real de la fila
     * es sobre algo no relacionado con ninguna ruta con nombre). No hay una respuesta oficial
     * reproducible disponible en este entorno para confirmarlo con un caso en vivo -- ver las
     * pruebas de {@code ManifestacionesServiceRutaL4Test}, que usan fixtures explícitos basados en
     * el texto real observado, no una respuesta oficial capturada en el momento.
     */
    static boolean[] rutasL4Cerradas(String infoRaw) {
        if (infoRaw == null) return new boolean[]{false, false};
        String normFull = Planificador.norm(infoRaw);
        boolean mencionaSur = normFull.contains("ruta sur");
        boolean mencionaNorte = normFull.contains("ruta norte");
        if (mencionaSur && !mencionaNorte) return new boolean[]{true, false};
        if (mencionaNorte && !mencionaSur) return new boolean[]{false, true};
        if (!mencionaSur && !mencionaNorte) return new boolean[]{false, false};
        // Ambas rutas mencionadas en el mismo aviso: distingue cuál está realmente cerrada,
        // oración por oración, para no mezclar el cierre de una con la mención de la otra.
        boolean sur = false, norte = false;
        for (String frase : infoRaw.split("[.·]")) {
            String nf = Planificador.norm(frase);
            boolean cierreFrase = nf.contains("cancela") || nf.contains("bloqueo") || nf.contains("bloquead")
                    || nf.contains("sin servicio") || nf.contains("suspend");
            if (!cierreFrase) continue;
            if (nf.contains("ruta sur")) sur = true;
            if (nf.contains("ruta norte")) norte = true;
        }
        return new boolean[]{sur, norte};
    }

    private void cortarAlrededor(int nlinea, String nn) {
        if (nlinea <= 0 || nn == null || nn.length() < 3) return;
        // L4/L7: cortar en la estación usando la adyacencia real de sus servicios (ambas ramas).
        if (porServicios(nlinea)) {
            for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
                for (int k = 0; k < sm.estaciones.length; k++) {
                    if (sm.lineas[k] != nlinea) continue;
                    String base = Planificador.norm(sm.estaciones[k]);
                    if (!(base.equals(nn) || base.contains(nn) || nn.contains(base))) continue;
                    if (k > 0 && sm.lineas[k - 1] == nlinea) {
                        String key = Manifestaciones.claveCorte(nlinea, base, Planificador.norm(sm.estaciones[k - 1]));
                        if (key != null) cortesAcc.add(key);
                    }
                    if (k + 1 < sm.estaciones.length && sm.lineas[k + 1] == nlinea) {
                        String key = Manifestaciones.claveCorte(nlinea, base, Planificador.norm(sm.estaciones[k + 1]));
                        if (key != null) cortesAcc.add(key);
                    }
                }
            }
            return;
        }
        Linea l = GtfsRepository.porNumero(this, nlinea);
        if (l == null || l.estaciones.isEmpty()) return;
        int idx = idxEstacion(l, nn);
        if (idx < 0) return;
        String base = Planificador.norm(l.estaciones.get(idx).nombre);
        if (idx > 0) {
            String k = Manifestaciones.claveCorte(nlinea, base, Planificador.norm(l.estaciones.get(idx - 1).nombre));
            if (k != null) cortesAcc.add(k);
        }
        if (idx + 1 < l.estaciones.size()) {
            String k = Manifestaciones.claveCorte(nlinea, base, Planificador.norm(l.estaciones.get(idx + 1).nombre));
            if (k != null) cortesAcc.add(k);
        }
    }

    /**
     * Como {@link #bloquearTramos}, pero para líneas ruteadas por SERVICIOS ({@link #porServicios}:
     * L4 y L7), que {@code bloquearTramos} excluye porque {@code l.estaciones} no sigue su topología
     * real. Usa las secuencias de {@link RutasMixtas} de esa línea como referencia de orden: resuelve
     * "Servicio de A a B" dentro de CADA secuencia que contenga ambos extremos y marca "en servicio"
     * lo que quede dentro de ese rango en AL MENOS una de ellas (si una variante sigue cubriendo una
     * estación, sigue siendo alcanzable por ahí). Lo que no queda cubierto por NINGUNA, se bloquea.
     *
     * <p>Caso real confirmado: L7 "Servicio de Campo Marte a Glorieta Cuitláhuac" no bloqueaba nada
     * (ni la troncal completa ni el tramo real): {@code bloquearTramos} la salta por ser L7, y no
     * existía ningún equivalente a {@link #bloquearRutaL4} para L7 (ese solo activa con el nombre
     * "Ruta Norte"/"Ruta Sur", que L7 no usa -- sus avisos nombran estaciones reales).
     */
    // NOTA (pendiente, fuera de alcance de este cambio): esta función recibe un 'normFull' que viene
    // de SU PROPIO Planificador.norm(info) (ver llamada más arriba), sin la sustitución "A - B" -> "A a
    // B" que sí se aplicó para bloquearTramos() (líneas troncales lineales). Un aviso real de L4/L7 con
    // guion como separador de extremos ("Campo Marte - Glorieta Cuitláhuac") tendría el mismo problema
    // que tenía L1 -- pero aplicar la sustitución aquí sin auditar los textos reales de L4/L7 (que se
    // rutean por RutasMixtas.SECUENCIAS, no por lista lineal) podía bloquear de más por un patrón de
    // texto no verificado para ese caso. Requiere una revisión específica con avisos reales de L4/L7.
    /**
     * Puente acotado hacia los 3 puntos reales del extremo L2 de H72 que {@code MapFragment}
     * dibuja por separado (ver {@code MapFragment.agregarEstacionesH72()}: "Alameda Tacubaya" y
     * "De la Salle" en ambos andenes, con coordenadas tomadas del GTFS oficial de la CDMX).
     * "Tacubaya"/"De la Salle" en {@code RutasMixtas.SECUENCIAS} solo sirven para resolver el
     * ÍNDICE del tramo (están etiquetadas línea 2, así que el bucle de arriba -- que filtra por
     * {@code nlinea}=7 -- nunca las escribe en {@code afect}); pero si el TEXTO del cierre nombra
     * ese extremo explícitamente, el marcador real correspondiente sí debe aparecer fuera de
     * servicio. "De la Salle" no distingue dirección en el texto real observado, así que ante la
     * duda se marcan AMBOS andenes -- no hay evidencia para elegir solo uno.
     */
    static void agregarClaveH72SiAplica(String xOY, Set<String> afect) {   // paquete-visible: pruebas JUnit
        String n = Planificador.norm(xOY);
        if (n.contains("tacubaya")) {
            afect.add(Planificador.claveTerminal(2) + "|" + Planificador.norm("Alameda Tacubaya"));
        }
        if (n.contains("salle")) {
            afect.add(Planificador.claveTerminal(2) + "|" + Planificador.norm("De la Salle · dirección Alameda Tacubaya"));
            afect.add(Planificador.claveTerminal(2) + "|" + Planificador.norm("De la Salle · dirección Glorieta Cuitláhuac"));
        }
    }

    private void bloquearTramosServicios(int nlinea, String normFull, Set<String> afect) {
        String seg = segmentoParcial(normFull);
        String segCierre = rutaSinServicio(normFull);
        if (seg == null && segCierre == null) return;
        List<RutasMixtas.SeqMixta> secs = new ArrayList<>();
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            boolean toca = false;
            for (int ln : sm.lineas) if (ln == nlinea) { toca = true; break; }
            if (toca) secs.add(sm);
        }
        if (secs.isEmpty()) return;

        // Cierre DIRECTO ("sin servicio la ruta X a Y", ver rutaSinServicio()) -- semántica OPUESTA
        // a la de "en servicio" de abajo: el tramo resuelto es justo el que SÍ se bloquea, no
        // "universo menos el tramo". Caso real confirmado: "Sin servicio la ruta Alameda Tacubaya a
        // Glorieta Cuitláhuac" (H72, L7) -- antes de esta corrección, ni siquiera se reconocía como
        // un patrón de cierre (no aportaba nada en absoluto).
        if (segCierre != null) {
            for (String chunk : segCierre.split("\\s+y\\s+")) {
                int ap = chunk.indexOf(" a ");
                if (ap < 3) continue;
                String x = chunk.substring(0, ap).trim();
                String y = chunk.substring(ap + 3).trim();
                if (x.length() < 3 || y.length() < 3) continue;
                if (nlinea == 7) { agregarClaveH72SiAplica(x, afect); agregarClaveH72SiAplica(y, afect); }
                for (RutasMixtas.SeqMixta sm : secs) {
                    int ix = idxEnSecuencia(sm, nlinea, x), iy = idxEnSecuencia(sm, nlinea, y);
                    if (ix < 0 || iy < 0) continue;
                    int lo = Math.min(ix, iy), hi = Math.max(ix, iy);
                    for (int k = lo; k <= hi; k++) {
                        if (sm.lineas[k] != nlinea) continue;
                        String nn = Planificador.norm(sm.estaciones[k]);
                        afect.add(Planificador.claveTerminal(nlinea) + "|" + nn);
                        cortarAlrededor(nlinea, nn);
                    }
                }
            }
        }
        if (seg == null) return;

        java.util.LinkedHashSet<String> universo = new java.util.LinkedHashSet<>();
        for (RutasMixtas.SeqMixta sm : secs)
            for (int k = 0; k < sm.estaciones.length; k++)
                if (sm.lineas[k] == nlinea) universo.add(Planificador.norm(sm.estaciones[k]));

        // Defecto confirmado (caso real de L7, reportado en dispositivo: "servicio de Indios Verdes
        // y Hospital Infantil la Villa a Hamburgo" -- dos ORÍGENES, un solo destino compartido). Antes:
        // (1) un origen SIN su propio "a Y" ("Indios Verdes") se descartaba sin más (ap<3), dejando
        // Indios Verdes marcado fuera de servicio pese a que el aviso dice explícitamente que sigue
        // operando; (2) cada tramo "X a Y" se procesaba de forma INDEPENDIENTE, bloqueando de
        // inmediato "universo menos SU PROPIO rango" -- así que un segundo tramo resuelto (p. ej. el
        // de "Hospital Infantil La Villa") podía volver a bloquear una estación que el primer tramo
        // ("Indios Verdes") ya había marcado en servicio, simplemente porque esa estación no estaba
        // DENTRO del rango del segundo tramo. Ahora: un origen sin "a Y" propio se empareja con el
        // destino del SIGUIENTE tramo que sí lo tenga (mismo destino, otro origen -- construcción real
        // "X y Y a Z"), y "en servicio" se ACUMULA (OR) across TODOS los tramos antes de decidir qué
        // bloquear UNA sola vez al final -- igual que ya hace indicesFueraDeRangoPorLinea() para las
        // líneas troncales.
        java.util.Map<String, Boolean> enServicio = new java.util.HashMap<>();
        boolean algunTramoResuelto = false;
        List<String> origenesPendientes = new ArrayList<>();
        for (String chunk : seg.split("\\s+y\\s+")) {
            int ap = chunk.indexOf(" a ");
            if (ap < 3) {
                String origen = chunk.trim();
                if (origen.length() >= 3) origenesPendientes.add(origen);
                continue;
            }
            String xDirecto = chunk.substring(0, ap).trim();
            String y = chunk.substring(ap + 3).trim();
            if (y.length() < 3) { origenesPendientes.clear(); continue; }

            List<String> origenes = new ArrayList<>(origenesPendientes);
            origenesPendientes.clear();
            if (xDirecto.length() >= 3) origenes.add(xDirecto);

            for (String x : origenes) {
                for (RutasMixtas.SeqMixta sm : secs) {
                    int ix = idxEnSecuencia(sm, nlinea, x), iy = idxEnSecuencia(sm, nlinea, y);
                    if (ix < 0 || iy < 0) continue;
                    algunTramoResuelto = true;
                    int lo = Math.min(ix, iy), hi = Math.max(ix, iy);
                    for (int k = 0; k < sm.estaciones.length; k++) {
                        if (sm.lineas[k] != nlinea) continue;
                        String nn = Planificador.norm(sm.estaciones[k]);
                        boolean dentro = k >= lo && k <= hi;
                        enServicio.merge(nn, dentro, (a, b) -> a || b);
                    }
                }
            }
        }
        // Ningún tramo real mapeado (ningún X/Y calzó con ninguna secuencia de esta línea): blindaje,
        // no arriesgar bloqueando de más por un texto no reconocido.
        if (!algunTramoResuelto) return;

        for (String nn : universo) {
            Boolean v = enServicio.get(nn);
            if (v == null || !v) {
                afect.add(Planificador.claveTerminal(nlinea) + "|" + nn);
                cortarAlrededor(nlinea, nn);
            }
        }
    }

    /** Índice de una estación DENTRO de una secuencia de {@link RutasMixtas} (solo sus tramos de la
     *  línea {@code nlinea}), por nombre normalizado con coincidencia difusa. Paquete-visible (no
     *  Context/instancia): reutilizada por las pruebas JUnit contra las secuencias REALES de
     *  RutasMixtas.SECUENCIAS, sin necesidad de una copia de datos a mano. */
    static int idxEnSecuencia(RutasMixtas.SeqMixta sm, int nlinea, String q) {
        String qn = Planificador.norm(q);
        for (int k = 0; k < sm.estaciones.length; k++) {
            if (sm.lineas[k] != nlinea) continue;
            String nn = Planificador.norm(sm.estaciones[k]);
            if (nn.equals(qn) || nn.contains(qn) || qn.contains(nn)) return k;
        }
        // Alias explícito, acotado y verificado (NO una relajación general del filtro por línea de
        // arriba): "Alameda Tacubaya" es el nombre de TERMINAL (línea 2) que el feed en vivo y los
        // avisos oficiales usan para AMBOS ramales reales de H72 -- ver RutasMixtas.LISTA:
        // Mixta("París", 7, "Alameda Tacubaya", 2) y Mixta("Glorieta Cuitláhuac", 7, "Alameda
        // Tacubaya", 2) -- y corresponde al mismo punto físico que la secuencia H72 ya trae como
        // "Tacubaya" (línea 2, el extremo donde arranca). Cuando se busca ese nombre contra la línea
        // 7 (nlinea del aviso real) dentro de una secuencia que efectivamente trae "Tacubaya" en
        // línea 2, se interpreta como "el extremo de la secuencia, antes de su primer tramo de
        // línea 7" -- se resuelve al primer índice de línea 7 de esa secuencia.
        if ("alameda tacubaya".equals(qn)) {
            boolean esExtremoDeH72 = false;
            for (int k = 0; k < sm.estaciones.length; k++) {
                if (sm.lineas[k] == 2 && "tacubaya".equals(Planificador.norm(sm.estaciones[k]))) {
                    esExtremoDeH72 = true;
                    break;
                }
            }
            if (esExtremoDeH72) {
                for (int k = 0; k < sm.estaciones.length; k++) {
                    if (sm.lineas[k] == nlinea) return k;
                }
            }
        }
        return -1;
    }

    /** Índice de la estación de la línea que coincide con el nombre normalizado {@code q}. */
    private static int idxEstacion(Linea l, String q) { return idxEstacion(l.estaciones, q); }

    // Extracción MÍNIMA (delega, no duplica): la lógica de idxEstacion() en sí no depende de
    // Android, pero Linea.java SÍ (su constructor llama a android.graphics.Color.parseColor(), que
    // revienta en una prueba JVM pura sin Robolectric -- no hay forma de construir una Linea real
    // ahí). Tomar List<Estacion> en vez de Linea permite probar esta función con una lista armada a
    // mano (Estacion no toca Android), sin cambiar en nada lo que bloquearTramos()/cortarAlrededor()
    // ya hacían a través del overload de arriba.
    static int idxEstacion(List<Estacion> estaciones, String q) {
        for (int k = 0; k < estaciones.size(); k++) {
            String nn = Planificador.norm(estaciones.get(k).nombre);
            if (nn.equals(q) || nn.contains(q) || q.contains(nn)) return k;
        }
        return -1;
    }

    private static String recortar(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    private String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) { if (b.length() > 0) b.append(", "); b.append(x); }
        return b.toString();
    }

    // ---- notificaciones ----

    /** Estático (recibe el Context) para que también lo pueda invocar {@link #emitirAvisoPrueba}
     *  desde el panel de simulación sin necesitar que el servicio esté corriendo. */
    private static void crearCanal(android.content.Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ong = new NotificationChannel(CANAL,
                    ctx.getString(R.string.canal_manifestaciones), NotificationManager.IMPORTANCE_MIN);
            ong.setShowBadge(false);
            nm.createNotificationChannel(ong);

            NotificationChannel avi = new NotificationChannel(CANAL_AVISO,
                    ctx.getString(R.string.canal_manifestaciones_avisos), NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(avi);
        }
    }

    private static PendingIntent piAbrir(android.content.Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void arrancarPrimerPlano() {
        Notification n = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.manifest_ongoing))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(piAbrir(this))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID_ONGOING, n);
            }
        } catch (Exception e) {
            // El SO puede negar el inicio en primer plano -- nunca debe tumbar la app, solo se
            // detiene limpio (el monitoreo de afectaciones queda indisponible por ahora).
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ManifestacionesService.arrancarPrimerPlano", String.valueOf(e));
            stopSelf();
        }
    }

    /**
     * Decide qué notificar. Estado del Servicio: una notificación por línea, SOLO cuando cambia
     * (nueva afectación, cambio de afectación o restablecimiento). Elevadores + mantenimiento:
     * un resumen 2 veces al día (≈05:00 y ≈13:00). Requiere que el Estado del Servicio se haya leído.
     */
    // Liveness del EC2 CACHEADA (epoch de "actualizado" del último afectaciones_mexibus.json leído en
    // SEGUNDO PLANO). notificar() corre en el hilo principal (callback del WebView), así que NO se puede
    // descargar ahí: hacerlo lanzaba NetworkOnMainThreadException → ec2Activo() devolvía SIEMPRE false y
    // el scraper local notificaba SIEMPRE, duplicando el push del EC2.
    private volatile long ec2Actualizado = 0;   // epoch del último JSON leído (0 = nunca)
    private volatile int  ec2Fallos = 0;        // descargas fallidas consecutivas (sin epoch previo)
    private final java.util.concurrent.ExecutorService ec2Exec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Actualiza en segundo plano el epoch de liveness del EC2 (sin bloquear el hilo principal). */
    private void refrescarEc2() {
        ec2Exec.execute(() -> {
            try {
                String json = Backend.descargar(Config.PATH_AFECT_MXB);
                long act = new org.json.JSONObject(json).optLong("actualizado", 0);
                if (act > 0) { ec2Actualizado = act; ec2Fallos = 0; }
            } catch (Exception e) {
                ec2Fallos++;   // EC2 no responde: se deja el último epoch (envejecerá) y se cuenta el fallo.
            }
        });
    }

    /** ¿El backend (EC2) sigue activo? Lee SOLO el cache (sin red, para no bloquear el hilo principal).
     *  Con epoch conocido decide por antigüedad (≤4 min = vivo); sin epoch aún, asume VIVO (para no
     *  duplicar el push) salvo que ya hayan fallado varias descargas seguidas (EC2 caído de arranque). */
    private boolean ec2Activo() {
        long act = ec2Actualizado;
        if (act > 0) return (System.currentTimeMillis() / 1000L - act) <= 240;
        return ec2Fallos < 3;   // nunca leído: vivo por defecto hasta ~3 fallos consecutivos
    }

    private void notificar() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        // RESPALDO: el scraper local solo NOTIFICA cuando el backend (EC2) está inactivo. Si el EC2
        // sigue vivo (escribió afectaciones_mexibus.json en los últimos 4 min), él manda las
        // notificaciones (Metrobús + Mexibús) y aquí NO se notifica para no duplicar. El panel se
        // sigue alimentando aparte, así que no se pierde información.
        if (ec2Activo()) return;
        // EC2 caído: el respaldo local cubre TAMBIÉN Mexibús (mismo parser y feeds RSS que el EC2),
        // notificando y alimentando el panel; en paralelo, abajo se procesa Metrobús.
        AfectMexibusFeed.procesar(this);
        cargarNotifEstado();   // restaura qué se avisó ya (para no duplicar tras reiniciar el servicio)

        // Separa por categoría. Estado: se guardan TODAS las afectaciones (varias por línea) indexadas
        // por su CLAVE, para avisar cada una una sola vez (una línea puede tener p. ej. "Retraso" y
        // "Manifestación" a la vez; antes se guardaba solo una por línea y, al alternarse el orden del
        // scraping, la clave cambiaba cada ciclo y re-notificaba en bucle).
        java.util.Map<String, Manifestaciones.Afectacion> estadoActual = new java.util.LinkedHashMap<>();
        List<Manifestaciones.Afectacion> otros = new ArrayList<>();
        boolean elevadores = Perfil.muestraElevadores(this);
        for (Manifestaciones.Afectacion a : listaAcc) {
            // Control maestro: si el usuario apagó los avisos de esa línea, no se notifica de ella.
            if (a.lineaNum > 0 && !Modos.notifLinea(this, a.lineaNum)) continue;
            if (a.categoria == Manifestaciones.C_ESTADO) {
                if (a.lineaNum > 0) estadoActual.put(a.clave(), a);
            } else {
                if (a.elevador && !elevadores) continue;   // elevadores solo con movilidad reducida
                otros.add(a);
            }
        }

        // --- Estado del Servicio: una notificación por AFECTACIÓN, solo cuando es nueva ---
        if (estadoFilas > 0) {   // solo si de verdad se leyó el Estado del Servicio
            // Nuevas (clave que no se había avisado)
            for (java.util.Map.Entry<String, Manifestaciones.Afectacion> e : estadoActual.entrySet()) {
                if (notifClaves.add(e.getKey())) {   // add() = true solo si es nueva
                    emitirTarjeta(this, nm, idClave(e.getKey()), e.getValue());
                }
            }
            // Restablecidas (estaban avisadas y ya no aparecen)
            for (String clave : new ArrayList<>(notifClaves)) {
                if (!estadoActual.containsKey(clave)) {
                    int ln = lineaDeClave(clave);
                    Manifestaciones.Afectacion ok = new Manifestaciones.Afectacion(
                            ln > 0 ? getString(R.string.manifest_linea_fmt, String.valueOf(ln)) : "", ln, "",
                            getString(R.string.afect_restablecido), "", "", false);
                    emitirTarjeta(this, nm, idClave(clave), ok);
                    notifClaves.remove(clave);
                }
            }
            guardarNotifEstado();   // persiste el dedup para que un reinicio no reenvíe lo mismo
        }

        // --- Elevadores + mantenimiento: 2 veces al día ---
        notificarOtros(nm, otros);
    }

    /** ID de notificación estable y único por CLAVE de afectación (así varias por línea conviven). */
    private static int idClave(String clave) {
        return ID_ESTADO_CLAVE_BASE + ((clave.hashCode() & 0x7fffffff) % 1000);
    }

    /** Número de línea guardado al inicio de la clave "linea|estado|lugar" (0 si no se puede leer). */
    private static int lineaDeClave(String clave) {
        int i = clave.indexOf('|');
        try { return Integer.parseInt(i > 0 ? clave.substring(0, i) : clave); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Restaura de disco las claves de afectación ya avisadas (una vez por vida del servicio). Sin esto,
     *  al reiniciarse el servicio se perdía la memoria y re-notificaba las mismas afectaciones. */
    private void cargarNotifEstado() {
        if (notifEstadoCargado) return;
        notifEstadoCargado = true;
        try {
            String s = getSharedPreferences("geomb", MODE_PRIVATE).getString("notif_estado", "");
            if (s.isEmpty()) return;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) notifClaves.add(arr.optString(i));
        } catch (org.json.JSONException ignore) {}
    }

    /** Persiste el conjunto de claves avisadas. */
    private void guardarNotifEstado() {
        JSONArray arr = new JSONArray();
        for (String c : notifClaves) arr.put(c);
        getSharedPreferences("geomb", MODE_PRIVATE).edit()
                .putString("notif_estado", arr.toString()).apply();
    }

    /**
     * Notificación de una afectación: TEXTO PLANO (tipografía del sistema) + logo de la línea, sin
     * layout personalizado, para que se lea igual en el teléfono, el reloj (Wear) y la isla dinámica.
     * Estático (recibe el Context) para que también la use {@link #emitirAvisoPrueba} desde el panel
     * de simulación, con la MISMA lógica que una afectación real (nada que mantener duplicado).
     */
    private static void emitirTarjeta(android.content.Context ctx, NotificationManager nm, int id, Manifestaciones.Afectacion a) {
        // Partes fijas (línea, estaciones) + descripción dinámica.
        final String prefijo = a.linea.isEmpty() ? "" : a.linea
                + (a.lugar.isEmpty() ? "" : " · " + a.lugar);
        final String tituloEs = a.estado.isEmpty() ? ctx.getString(R.string.manifest_generico) : a.estado;
        final int lineaNum = a.lineaNum;
        final String infoEs = a.info;

        // Contenido dinámico (español): se traduce al idioma efectivo con el motor de Google
        // (ML Kit). Para es/náhuatl/no soportado queda en español. Al terminar, notifica.
        // Corre en el callback ASÍNCRONO de Traductor.traducirTexto (ML Kit); una falla ahí no debe
        // tumbar este servicio en primer plano.
        Traductor.traducirTexto(ctx, tituloEs, tituloT ->
                Traductor.traducirTexto(ctx, infoEs, infoT -> {
                    try {
                        StringBuilder texto = new StringBuilder(prefijo);
                        if (infoT != null && !infoT.isEmpty())
                            texto.append(texto.length() > 0 ? "\n" : "").append(infoT);
                        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CANAL_AVISO)
                                .setSmallIcon(R.drawable.ic_bus)
                                .setContentTitle(tituloT)
                                .setContentText(texto.toString().replace('\n', ' '))
                                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto.toString()))
                                .setAutoCancel(true)
                                .setContentIntent(piAbrir(ctx))
                                .setCategory(NotificationCompat.CATEGORY_STATUS)
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                        if (lineaNum > 0) {
                            Linea l = GtfsRepository.porNumero(ctx, lineaNum);
                            int color = l != null ? l.color : 0xFFD40D0D;
                            b.setColor(color);
                            android.graphics.Bitmap logo = Tipografia.bitmapLineaLogo(ctx, lineaNum, color);
                            if (logo != null) b.setLargeIcon(logo);
                        }
                        nm.notify(id, b.build());
                    } catch (Throwable t) {
                        Telemetria.registrarError(ctx, Telemetria.ERR_EXCEPCION, "ManifestacionesService.emitirTarjeta", String.valueOf(t));
                    }
                }));
    }

    /**
     * Dispara la MISMA tarjeta de notificación que produciría una afectación real, para que el panel
     * de simulación ("Modo personalizado") pueda probar el flujo de avisos sin esperar una afectación
     * real. Crea el canal si aún no existe (el servicio en primer plano pudo no haber arrancado nunca).
     */
    public static void emitirAvisoPrueba(android.content.Context ctx, Manifestaciones.Afectacion a) {
        android.content.Context app = ctx.getApplicationContext();
        crearCanal(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        emitirTarjeta(app, nm, idClave(a.clave()), a);
    }

    /** Elevadores + mantenimiento: un lote al entrar a la ventana de las 05:00 y otra a las 13:00. */
    private void notificarOtros(NotificationManager nm, List<Manifestaciones.Afectacion> otros) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hora = c.get(java.util.Calendar.HOUR_OF_DAY);
        String hoy = c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
        String slot = (hora >= 5 && hora < 13) ? "am" : (hora >= 13 ? "pm" : null);
        if (slot == null) return;
        android.content.SharedPreferences p = getSharedPreferences("geomb", MODE_PRIVATE);
        String key = "otros_" + slot;
        if (hoy.equals(p.getString(key, ""))) return;   // ya se envió en esta ventana hoy
        p.edit().putString(key, hoy).apply();

        // Limpia el lote anterior.
        nm.cancel(ID_OTROS_SUMMARY);
        for (int j = 0; j < 12; j++) nm.cancel(ID_OTROS_BASE + j);
        if (otros.isEmpty()) return;

        int i = 0;
        List<String> resumen = new ArrayList<>();
        for (Manifestaciones.Afectacion a : otros) {
            if (i >= 12) break;
            final int idx = i;
            final String prefijoLinea = a.linea.isEmpty() ? "" : a.linea + " · ";
            final String lugar = a.lugar;
            final String infoEs = a.info;
            // Traduce estado (título) e info (cuerpo) al idioma efectivo; estaciones quedan igual.
            Traductor.traducirTexto(this, a.estado, estadoT ->
                    Traductor.traducirTexto(this, infoEs, infoT -> {
                        try {
                            String txt = (lugar.isEmpty() ? "" : lugar)
                                    + (infoT == null || infoT.isEmpty() ? "" : " · " + infoT);
                            Notification card = new NotificationCompat.Builder(this, CANAL_AVISO)
                                    .setSmallIcon(R.drawable.ic_bus)
                                    .setContentTitle(prefijoLinea + estadoT)
                                    .setContentText(txt)
                                    .setStyle(new NotificationCompat.BigTextStyle().bigText(txt))
                                    .setGroup(GRUPO_OTROS).setAutoCancel(true).setContentIntent(piAbrir(this))
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).build();
                            nm.notify(ID_OTROS_BASE + idx, card);
                        } catch (Throwable t) {
                            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ManifestacionesService.notificarOtros", String.valueOf(t));
                        }
                    }));
            resumen.add(prefijoLinea + a.lugar);
            i++;
        }
        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        for (int k = 0; k < resumen.size() && k < 8; k++) inbox.addLine("• " + resumen.get(k));
        nm.notify(ID_OTROS_SUMMARY, new NotificationCompat.Builder(this, CANAL_AVISO)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.manifest_alerta_titulo))
                .setContentText(recortar(join(resumen), 120))
                .setStyle(inbox).setGroup(GRUPO_OTROS).setGroupSummary(true)
                .setNumber(i).setAutoCancel(true).setContentIntent(piAbrir(this))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build());
    }

    /** Android 14+ (API 34): el FGS dataSync alcanzó su límite de tiempo. HAY que detenerlo aquí o el
     *  sistema lanza ForegroundServiceDidNotStopInTimeException y mata la app. Se detiene limpio; la app
     *  vuelve a arrancar el servicio al pasar a primer plano. */
    @Override
    public void onTimeout(int startId) {
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Android 15+ (API 35): mismo timeout que arriba, con la firma nueva de dos argumentos. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        onTimeout(startId);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (candadoPendiente != null) handler.removeCallbacks(candadoPendiente);
        if (seguridadPendiente != null) handler.removeCallbacks(seguridadPendiente);
        generacion++;   // defensa adicional, igual que en candadoVencido/seguridadVencida
        // "web = null" es la protección que realmente importa aquí: deja a "v != web" en
        // onPageFinished siempre verdadero para CUALQUIER callback de este WebView que aún
        // llegara (identidad de objeto -- ver el javadoc de "web" -- nunca un valor que remover
        // callbacks o generacion++ pudieran no alcanzar a tiempo).
        if (web != null) { WebView viejo = web; web = null; viejo.destroy(); }
        ec2Exec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
