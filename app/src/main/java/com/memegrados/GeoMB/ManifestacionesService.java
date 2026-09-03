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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Revisa cada minuto, en segundo plano, la página oficial de estado del servicio
 * (manifestaciones, cierres, mantenimiento) con un WebView oculto, detecta las
 * estaciones afectadas y avisa. El planificador las usa para rutas alternas.
 */
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
    private WebView web;
    private boolean cargando = false;
    private List<Manifestaciones.Afectacion> ultimaLista = new ArrayList<>();

    private int fase = 0;                                         // 0 = tablas (ServicioMB), 1 = estado (iframe)
    private Set<String> afectAcc = new HashSet<>();
    // Bloqueo POR SENTIDO: estación(norm) -> {terminal(norm) | AMBOS}. General y de movilidad reducida.
    private java.util.Map<String, java.util.Set<String>> porSentidoAcc = new java.util.HashMap<>();
    private java.util.Map<String, java.util.Set<String>> porSentidoMRAcc = new java.util.HashMap<>();
    private List<Manifestaciones.Afectacion> listaAcc = new ArrayList<>();
    private List<String> resumenAcc = new ArrayList<>();
    private Set<String> cortesAcc = new HashSet<>();             // cortes reales (partición de línea) del ciclo
    private int estadoFilas = 0;                                  // filas leídas del Estado del Servicio
    // Estado ya notificado por línea (clave de la situación) para no repetir el aviso.
    private final java.util.Set<String> notifClaves = new java.util.HashSet<>();   // claves de afectación ya avisadas
    private boolean notifEstadoCargado = false;   // ¿ya se restauró el dedup persistido de esta sesión?
    // Si el iframe de estado no responde, igual notifica lo de las tablas (estado se omite por estadoFilas==0).
    private final Runnable seguridad = () -> { notificar(); cargando = false; };

    private final Runnable tick = this::revisar;

    public static void iniciar(android.content.Context c) {
        androidx.core.content.ContextCompat.startForegroundService(
                c, new Intent(c, ManifestacionesService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
        web = new WebView(getApplicationContext());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public void onPageFinished(WebView v, String url) {
                final String u = url != null ? url : "";
                // Fase 0: tablas de ServicioMB (elevadores + mantenimiento). Fase 1: iframe Estado del Servicio.
                if (fase == 0 && u.contains("metrobus.cdmx.gob.mx/ServicioMB")) {
                    handler.postDelayed(() -> v.evaluateJavascript(JS_TABLAS,
                            ManifestacionesService.this::onTablas), 2500);
                } else if (fase == 1 && u.contains("bandejaEstadoServicio")) {
                    handler.postDelayed(() -> v.evaluateJavascript(JS_ESTADO,
                            ManifestacionesService.this::onEstado), 2500);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        arrancarPrimerPlano();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void revisar() {
        if (!cargando && web != null) {
            cargando = true;
            fase = 0;
            estadoFilas = 0;
            afectAcc = new HashSet<>();
            porSentidoAcc = new java.util.HashMap<>();
            porSentidoMRAcc = new java.util.HashMap<>();
            listaAcc = new ArrayList<>();
            resumenAcc = new ArrayList<>();
            cortesAcc = new HashSet<>();
            web.loadUrl(URL_SERVICIOMB);   // primero las tablas (funciona seguro); luego el iframe de estado
        }
        handler.postDelayed(tick, INTERVALO_MS);
    }

    /** Fase 0 listo: elevadores + mantenimiento. Actualiza el estado (para la app) y va por el Estado del Servicio. */
    private void onTablas(String jsonValue) {
        procesarFilas(jsonValue);
        guardarStore();                              // panel en la app se actualiza siempre
        fase = 1;
        if (web != null) web.loadUrl(URL_ESTADO);
        handler.removeCallbacks(seguridad);
        handler.postDelayed(seguridad, 15000);       // si el iframe no responde, no bloquear el ciclo
    }

    /** Fase 1 listo: Estado del Servicio. Actualiza el estado y decide qué notificar. */
    private void onEstado(String jsonValue) {
        handler.removeCallbacks(seguridad);
        procesarFilas(jsonValue);
        guardarStore();
        notificar();
        cargando = false;
    }

    /** Actualiza el estado compartido (panel en la app + ruteo). NO notifica. */
    private void guardarStore() {
        Manifestaciones.actualizar(afectAcc, porSentidoAcc, porSentidoMRAcc, listaAcc, join(resumenAcc));
        Manifestaciones.reemplazarCortesReales(cortesAcc);   // parte la(s) línea(s) donde hay cierre total
        ultimaLista = listaAcc;
    }

    /**
     * Registra un bloqueo por sentido: estación(norm) -> {terminal(norm) | AMBOS}. La dirección
     * "ambos sentidos"/vacía marca la estación completa; cualquier otra se toma como el nombre
     * de la terminal del carril afectado. Se quitan paréntesis del nombre (p. ej. "(Escaleras Sur)").
     */
    private void agregarSentido(java.util.Map<String, java.util.Set<String>> mapa,
                                String estacion, String direccion) {
        if (estacion == null) return;
        String est = Planificador.norm(estacion.replaceAll("\\(.*?\\)", ""));
        if (est.length() < 3) return;
        String d = Planificador.norm(direccion);
        String clave = (d.isEmpty() || d.contains("ambos") || d.contains("ambas")
                || d.contains("todos") || d.contains("todas") || d.contains("dos sentidos"))
                ? Manifestaciones.AMBOS : d;
        mapa.computeIfAbsent(est, z -> new java.util.HashSet<>()).add(clave);
    }

    /** Bloquea una estación: por sentido (hacia esa terminal) si se detectó una, o ambos si no. */
    private void bloquearNn(String nn, String terminalSentido) {
        if (nn == null || nn.length() < 3) return;
        if (terminalSentido != null)
            porSentidoAcc.computeIfAbsent(nn, z -> new java.util.HashSet<>()).add(terminalSentido);
        else
            afectAcc.add(nn);
    }

    /**
     * Terminal (norm) del SENTIDO afectado, o null = ambos sentidos. Solo se considera "un sentido"
     * si el texto lo dice explícitamente (dirección / sentido / hacia). Esto evita confundir una
     * descripción por RANGOS ("Servicio de Indios Verdes a Plaza de la República y de El Caminero a
     * Insurgentes") —donde las terminales son extremos de tramo, no un sentido— con un cierre de un
     * solo carril: en ese caso se corta AMBOS sentidos (se parte la línea).
     */
    private String terminalEnTexto(int linea, String textoNorm) {
        Linea l = GtfsRepository.porNumero(this, linea);
        if (l == null || l.estaciones.isEmpty()) return null;
        boolean direccional = textoNorm.contains("direccion") || textoNorm.contains("sentido")
                || textoNorm.contains("hacia");
        if (!direccional) return null;   // sin marca de sentido = ambos (cierre total, se parte la línea)
        String t1 = Planificador.norm(l.estaciones.get(0).nombre);
        String t2 = Planificador.norm(l.estaciones.get(l.estaciones.size() - 1).nombre);
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
        try { rows = new JSONObject(payload).optJSONArray("rows"); } catch (Exception ignore) {}

        List<Estacion> catalogo = new ArrayList<>();
        try { for (Linea l : GtfsRepository.getLineas(this)) catalogo.addAll(l.estaciones); }
        catch (Exception ignore) {}

        Set<String> afect = afectAcc;
        List<Manifestaciones.Afectacion> lista = listaAcc;
        List<String> resumenLista = resumenAcc;

        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String tipo = r.optString("tipo", "");
                int nlinea = -1;
                try { nlinea = Integer.parseInt(r.optString("linea", "").trim()); } catch (Exception ignore) {}
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
                        agregarSentido(porSentidoMRAcc, estacion, direccion);

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
                    agregarSentido(porSentidoAcc, estacion, direccion);

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
                    boolean retraso = ne.contains("retraso") || ne.contains("demora")
                            || ne.contains("lento") || ne.contains("regular");
                    String sev = Planificador.norm(estado + " " + info + " " + estaciones);
                    boolean sinServicio = !retraso && (
                               sev.contains("sin servicio") || sev.contains("cerrad")
                            || sev.contains("suspend") || sev.contains("no hay servicio")
                            || sev.contains("intervencion en la estacion")
                            || ne.contains("manifestacion")        // Manifestación como ESTADO = corta
                            || sev.contains("bloqueo") || sev.contains("bloquead")
                            || sev.contains("planton"));
                    // Obstrucción de carril: afecta UN solo sentido (un carril), NO toda la estación ni
                    // parte la línea. Sin dirección clara en el texto, no se bloquea nada.
                    boolean obstruccionCarril = !retraso && sev.contains("obstru") && sev.contains("carril");
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
                        } else if (nEst.contains("linea completa") || nEst.contains("toda la linea")) {
                            if (nlinea > 0) {
                                Linea l = GtfsRepository.porNumero(this, nlinea);
                                if (l != null) {
                                    for (Estacion e : l.estaciones)
                                        bloquearNn(Planificador.norm(e.nombre), terminalSentido);
                                    // Línea completa fuera: corta todos los tramos (queda intransitable).
                                    // L4/L7 se rutean por servicios (couplet): los cortes se generan de la
                                    // secuencia real, no de la lista plana, para atrapar AMBAS ramas.
                                    if (ambos) {
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
                            for (Estacion e : catalogo) {
                                String nn = Planificador.norm(e.nombre);
                                if (nn.length() >= 4 && nEst.contains(nn)) {
                                    bloquearNn(nn, terminalSentido);
                                    if (ambos) cortarAlrededor(nlinea, nn);   // parte la línea en la estación cerrada
                                }
                            }
                        }
                        // Obstrucción de carril no usa rangos "solo hay servicio de A a B" (eso bloquea
                        // ambos sentidos); ya se bloqueó solo el carril afectado arriba.
                        if (!obstruccionCarril)
                            bloquearTramos(Planificador.norm(info), afect, ambos ? nlinea : 0);
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
        while (mm.find()) { try { dias.add(Integer.parseInt(mm.group())); } catch (Exception ignore) {} }
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

    /**
     * Detecta "solo hay servicio de A a B (y de C a D)" y bloquea el complemento
     * (las estaciones del tramo sin servicio) en la línea correspondiente.
     */
    private void bloquearTramos(String normFull, Set<String> afect, int cortarLineaHint) {
        boolean parcial = normFull.contains("solo hay servicio") || normFull.contains("servicio provisional")
                || normFull.contains("servicio parcial") || normFull.contains("opera de")
                || normFull.contains("provisional");
        if (!parcial) return;

        int idx = normFull.indexOf("servicio de");
        if (idx < 0) idx = normFull.indexOf("opera de");
        if (idx < 0) return;
        String seg = normFull.substring(idx);
        if (seg.length() > 200) seg = seg.substring(0, 200);
        seg = seg.replaceFirst("^(servicio de|opera de)\\s*", "");

        List<Linea> lineas;
        try { lineas = GtfsRepository.getLineas(this); } catch (Exception e) { return; }

        // Rangos "en servicio" por número de línea.
        java.util.Map<Integer, List<int[]>> corridos = new java.util.HashMap<>();
        for (String chunk : seg.split("\\s+y\\s+")) {
            int ap = chunk.indexOf(" a ");
            if (ap < 3) continue;
            String x = chunk.substring(0, ap).trim();
            String y = chunk.substring(ap + 3).trim();
            if (x.length() < 3 || y.length() < 3) continue;
            for (Linea l : lineas) {
                int ix = idxEstacion(l, x), iy = idxEstacion(l, y);
                if (ix >= 0 && iy >= 0) {
                    int a = Math.min(ix, iy), b = Math.max(ix, iy);
                    corridos.computeIfAbsent(l.numero, z -> new ArrayList<>()).add(new int[]{a, b});
                    break;   // primer línea que contiene ambos extremos
                }
            }
        }

        // Bloquea las estaciones fuera de los rangos en servicio y CORTA los tramos que quedan
        // sin servicio (servicio parcial = la línea está físicamente partida entre los rangos).
        for (Linea l : lineas) {
            List<int[]> rangos = corridos.get(l.numero);
            if (rangos == null) continue;
            for (int k = 0; k < l.estaciones.size(); k++) {
                boolean corre = false;
                for (int[] r : rangos) if (k >= r[0] && k <= r[1]) { corre = true; break; }
                if (!corre) {
                    String nn = Planificador.norm(l.estaciones.get(k).nombre);
                    afect.add(nn);
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

    /** Índice de la estación de la línea que coincide con el nombre normalizado {@code q}. */
    private static int idxEstacion(Linea l, String q) {
        for (int k = 0; k < l.estaciones.size(); k++) {
            String nn = Planificador.norm(l.estaciones.get(k).nombre);
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

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ong = new NotificationChannel(CANAL,
                    getString(R.string.canal_manifestaciones), NotificationManager.IMPORTANCE_MIN);
            ong.setShowBadge(false);
            nm.createNotificationChannel(ong);

            NotificationChannel avi = new NotificationChannel(CANAL_AVISO,
                    getString(R.string.canal_manifestaciones_avisos), NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(avi);
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void arrancarPrimerPlano() {
        Notification n = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.manifest_ongoing))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(piAbrir())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    /**
     * Decide qué notificar. Estado del Servicio: una notificación por línea, SOLO cuando cambia
     * (nueva afectación, cambio de afectación o restablecimiento). Elevadores + mantenimiento:
     * un resumen 2 veces al día (≈05:00 y ≈13:00). Requiere que el Estado del Servicio se haya leído.
     */
    private void notificar() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
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
                    emitirTarjeta(nm, idClave(e.getKey()), e.getValue());
                }
            }
            // Restablecidas (estaban avisadas y ya no aparecen)
            for (String clave : new ArrayList<>(notifClaves)) {
                if (!estadoActual.containsKey(clave)) {
                    int ln = lineaDeClave(clave);
                    Manifestaciones.Afectacion ok = new Manifestaciones.Afectacion(
                            ln > 0 ? getString(R.string.manifest_linea_fmt, String.valueOf(ln)) : "", ln, "",
                            getString(R.string.afect_restablecido), "", "", false);
                    emitirTarjeta(nm, idClave(clave), ok);
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
        } catch (Exception ignore) {}
    }

    /** Persiste el conjunto de claves avisadas. */
    private void guardarNotifEstado() {
        try {
            JSONArray arr = new JSONArray();
            for (String c : notifClaves) arr.put(c);
            getSharedPreferences("geomb", MODE_PRIVATE).edit()
                    .putString("notif_estado", arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    /**
     * Notificación de una afectación: TEXTO PLANO (tipografía del sistema) + logo de la línea, sin
     * layout personalizado, para que se lea igual en el teléfono, el reloj (Wear) y la isla dinámica.
     */
    private void emitirTarjeta(NotificationManager nm, int id, Manifestaciones.Afectacion a) {
        // Partes fijas (línea, estaciones) + descripción dinámica.
        final String prefijo = a.linea.isEmpty() ? "" : a.linea
                + (a.lugar.isEmpty() ? "" : " · " + a.lugar);
        final String tituloEs = a.estado.isEmpty() ? getString(R.string.manifest_generico) : a.estado;
        final int lineaNum = a.lineaNum;
        final String infoEs = a.info;

        // Contenido dinámico (español): se traduce al idioma efectivo con el motor de Google
        // (ML Kit). Para es/náhuatl/no soportado queda en español. Al terminar, notifica.
        Traductor.traducirTexto(this, tituloEs, tituloT ->
                Traductor.traducirTexto(this, infoEs, infoT -> {
                    StringBuilder texto = new StringBuilder(prefijo);
                    if (infoT != null && !infoT.isEmpty())
                        texto.append(texto.length() > 0 ? "\n" : "").append(infoT);
                    NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL_AVISO)
                            .setSmallIcon(R.drawable.ic_bus)
                            .setContentTitle(tituloT)
                            .setContentText(texto.toString().replace('\n', ' '))
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto.toString()))
                            .setAutoCancel(true)
                            .setContentIntent(piAbrir())
                            .setCategory(NotificationCompat.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                    if (lineaNum > 0) {
                        Linea l = GtfsRepository.porNumero(this, lineaNum);
                        int color = l != null ? l.color : 0xFFD40D0D;
                        b.setColor(color);
                        android.graphics.Bitmap logo = Tipografia.logoLinea(this, color, String.valueOf(lineaNum));
                        if (logo != null) b.setLargeIcon(logo);
                    }
                    nm.notify(id, b.build());
                }));
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
                        String txt = (lugar.isEmpty() ? "" : lugar)
                                + (infoT == null || infoT.isEmpty() ? "" : " · " + infoT);
                        Notification card = new NotificationCompat.Builder(this, CANAL_AVISO)
                                .setSmallIcon(R.drawable.ic_bus)
                                .setContentTitle(prefijoLinea + estadoT)
                                .setContentText(txt)
                                .setStyle(new NotificationCompat.BigTextStyle().bigText(txt))
                                .setGroup(GRUPO_OTROS).setAutoCancel(true).setContentIntent(piAbrir())
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build();
                        nm.notify(ID_OTROS_BASE + idx, card);
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
                .setNumber(i).setAutoCancel(true).setContentIntent(piAbrir())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build());
    }

    /** Android 14+ (API 34): el FGS dataSync alcanzó su límite de tiempo. HAY que detenerlo aquí o el
     *  sistema lanza ForegroundServiceDidNotStopInTimeException y mata la app. Se detiene limpio; la app
     *  vuelve a arrancar el servicio al pasar a primer plano. */
    @Override
    public void onTimeout(int startId) {
        handler.removeCallbacksAndMessages(null);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignore) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
