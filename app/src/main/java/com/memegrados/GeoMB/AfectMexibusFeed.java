package com.memegrados.GeoMB;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RESPALDO LOCAL de afectaciones Mexibús. Es el port a Java del parser del backend
 * (mexibus_afectaciones.py): lee los MISMOS feeds RSS.app (X / Facebook de Mexibús Informa /
 * SITRAMYTEM / Mexicable), interpreta cada publicación (línea, estado, lugar, info, circuito) y:
 *   · NOTIFICA las publicaciones nuevas (una sola vez por post) con el ícono y la etiqueta de la línea;
 *   · alimenta el PANEL de estado del servicio ({@link Manifestaciones#setMexibus}) y el bloqueo de ruteo.
 *
 * Se invoca desde {@link ManifestacionesService} SOLO cuando el backend (EC2) está caído
 * (afectaciones_mexibus.json obsoleto o inalcanzable): cuando el EC2 vive, él manda los push
 * (Metrobús + Mexibús) y este respaldo no corre, para no duplicar.
 */
public final class AfectMexibusFeed {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    /** Mismos feeds RSS.app que consume el backend (RSS_URL). */
    private static final String[] FEEDS = {
            "https://rss.app/feeds/iUt5KovKRZWOtnS2.xml",   // Mexibús Informa (X)
            "https://rss.app/feeds/E8j2JvAwn87r9bfG.xml",   // Mexicable (X)
            "https://rss.app/feeds/NWYsjr0gZzQMjU38.xml",   // Mexibús L1 (avisos)
    };

    private static final long MAX_POST_AGE_MS = 90L * 60 * 1000;    // MAX_POST_AGE_MIN = 90
    private static final long ESTADO_TTL_MS   = 720L * 60 * 1000;   // MXB_ESTADO_TTL   = 720 (12 h)
    private static final String CANAL_AVISO   = "manifestaciones_avisos";   // = ManifestacionesService.CANAL_AVISO
    private static final int    ID_BASE       = 4510;              // = MensajesService.ID_BASE (misma tarjeta que el push)
    private static final String PREF          = "geomb";
    private static final String KEY_VISTOS    = "mxb_local_posts"; // dedup por publicación (id|linea)

    private AfectMexibusFeed() {}

    /** Corre el respaldo en segundo plano: fetch RSS → parse → notifica lo nuevo + actualiza el panel. */
    public static void procesar(Context ctx) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try { procesarSync(app); } catch (Exception ignore) {}
        });
    }

    private static void procesarSync(Context app) {
        List<Post> posts = fetchFeeds();
        if (posts.isEmpty()) return;
        long ahora = System.currentTimeMillis();

        // --- Notificar publicaciones NUEVAS (dedup por post + línea, como el backend) ---
        Set<String> idsActuales = new HashSet<>();
        for (Post p : posts) idsActuales.add(p.id);
        Set<String> vistos = cargarVistos(app);
        boolean notifOn = Modos.notifAfectaciones(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);

        // Orden ascendente por fecha: se avisan del más viejo al más nuevo.
        posts.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        for (Post post : posts) {
            if (ahora - post.timeMs > MAX_POST_AGE_MS) continue;
            for (Afect a : parsePost(post.message)) {
                String k = post.id + "|" + a.linea;
                if (vistos.contains(k)) continue;      // este post ya se procesó
                vistos.add(k);
                if (!notifOn || nm == null) continue;
                if (!Modos.notifLinea(app, a.linea)) continue;   // línea silenciada por el usuario
                emitir(app, nm, a);
            }
        }
        // Poda: conserva solo claves de posts aún presentes (no crece sin límite).
        Set<String> podado = new HashSet<>();
        for (String k : vistos) {
            int bar = k.indexOf('|');
            String id = bar > 0 ? k.substring(0, bar) : k;
            if (idsActuales.contains(id)) podado.add(k);
        }
        guardarVistos(app, podado);

        // --- Alimentar el PANEL + bloqueo de ruteo (estado vigente por línea) ---
        actualizarPanel(app, posts, ahora);
    }

    // ------------------------------------------------------------------ notificación
    private static void emitir(Context app, NotificationManager nm, Afect a) {
        String label = etiquetaLinea(app, a.linea);
        StringBuilder texto = new StringBuilder();
        if (!label.isEmpty()) texto.append(label);
        if (!a.lugar.isEmpty()) texto.append(texto.length() > 0 ? " · " : "").append(a.lugar);
        if (!a.info.isEmpty()) texto.append(texto.length() > 0 ? "\n" : "").append(a.info);
        String cuerpo = texto.toString();
        String titulo = a.estado.isEmpty() ? app.getString(R.string.manifest_generico) : a.estado;

        int id = ID_BASE + (((a.linea + "|" + a.lugar).hashCode() & 0x7fffffff) % 100000);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CANAL_AVISO)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(titulo)
                .setContentText(cuerpo.replace('\n', ' '))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(piApp(app))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        Linea l = GtfsRepository.porNumero(app, a.linea);
        int color = l != null ? l.color : 0xFFD40D0D;
        b.setColor(color);
        Bitmap logo = Tipografia.bitmapLineaLogo(app, a.linea, color);
        if (logo != null) b.setLargeIcon(logo);
        nm.notify(id, b.build());
    }

    /** Etiqueta como en MensajesService: "Línea N" (Metrobús), "Mexibús L2"/"L2A", "Mexicable L1". */
    private static String etiquetaLinea(Context c, int n) {
        if (n <= 0) return "";
        if (n < 100) return c.getString(R.string.manifest_linea_fmt, String.valueOf(n));
        if (n < 200) return "Mexibús L" + Planificador.etiquetaLineaCortaPub(n);
        return "Mexicable L" + (n - 200);
    }

    private static PendingIntent piApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------------ panel + bloqueo
    private static void actualizarPanel(Context app, List<Post> posts, long ahora) {
        // Situación vigente por línea: el post MÁS RECIENTE (dentro del TTL) manda.
        posts.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));   // descendente
        LinkedHashMap<Integer, Afect> reciente = new LinkedHashMap<>();
        for (Post post : posts) {
            if (ahora - post.timeMs > ESTADO_TTL_MS) continue;
            for (Afect a : parsePost(post.message))
                if (!reciente.containsKey(a.linea)) reciente.put(a.linea, a);
        }
        List<Manifestaciones.Afectacion> lista = new ArrayList<>();
        Set<String> bloq = new HashSet<>();
        for (Afect a : reciente.values()) {
            if (a.estado.toLowerCase(Locale.ROOT).contains("restablec")) continue;   // ya reanudada
            lista.add(new Manifestaciones.Afectacion("", a.linea, a.lugar, a.estado, "", a.info,
                    false, Manifestaciones.C_ESTADO));
            Linea l = GtfsRepository.porNumero(app, a.linea);
            if (l != null) AfectacionesMexibus.bloqueoLineaLocal(l, a.estado, a.circuito, a.lugar, bloq);
        }
        Manifestaciones.setMexibus(lista);
        Manifestaciones.setMexibusBloqueadas(bloq);
    }

    // ------------------------------------------------------------------ dedup persistente
    private static Set<String> cargarVistos(Context c) {
        Set<String> s = new HashSet<>();
        try {
            String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_VISTOS, "");
            if (!raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) s.add(arr.optString(i));
            }
        } catch (Exception ignore) {}
        return s;
    }

    private static void guardarVistos(Context c, Set<String> s) {
        try {
            JSONArray arr = new JSONArray();
            for (String k : s) arr.put(k);
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_VISTOS, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    // ==================================================================== PARSER (port de Python)

    /** Afectación parseada de un post. */
    static final class Afect {
        final int linea; final String estado, lugar, info; final List<String[]> circuito;
        Afect(int linea, String estado, String lugar, String info, List<String[]> circuito) {
            this.linea = linea; this.estado = estado; this.lugar = lugar;
            this.info = info; this.circuito = circuito;
        }
    }

    // _LW = "línea" y variantes + "L"
    private static final String LW = "(?:linea|linia|lnea|line|l)";

    /** _pat(n): "L n"/"mexibus l n" con límite de palabra; ramal exige "na" pegado (1a, no "1 a"). */
    private static String pat(int n, boolean ramal) {
        String suf = ramal ? "a\\b" : "\\b";
        return "(?:mexibus\\s*" + LW + "\\s*" + n + suf + "|\\b" + LW + "\\s*" + n + suf + ")";
    }

    // Códigos de la app: Mexibús 101-104, ramales 111-113, Mexicable 201-202. Orden: ramales/alias ANTES.
    private static final int[]     COD = new int[11];
    private static final Pattern[] RX  = new Pattern[11];
    static {
        int i = 0;
        RX[i] = Pattern.compile("aifa|afia|aeropuerto|felipe angeles|terminal de pasajeros"); COD[i++] = 111;
        RX[i] = Pattern.compile(pat(1, true));                                                COD[i++] = 111;
        RX[i] = Pattern.compile("servicio\\s*electric\\w*|servicioelectric\\w*");             COD[i++] = 112;
        RX[i] = Pattern.compile(pat(2, true));                                                COD[i++] = 112;
        RX[i] = Pattern.compile(pat(3, true));                                                COD[i++] = 113;
        RX[i] = Pattern.compile("mexicable\\s*" + LW + "\\s*1|linea roja");                   COD[i++] = 201;
        RX[i] = Pattern.compile("mexicable\\s*" + LW + "\\s*2");                              COD[i++] = 202;
        RX[i] = Pattern.compile(pat(1, false));                                               COD[i++] = 101;
        RX[i] = Pattern.compile(pat(2, false));                                               COD[i++] = 102;
        RX[i] = Pattern.compile(pat(3, false));                                               COD[i++] = 103;
        RX[i] = Pattern.compile(pat(4, false));                                               COD[i++] = 104;
    }
    private static final Pattern P_SERVELEC = Pattern.compile("servicio\\s*electric|servicioelectric");
    private static final Pattern P_GUARD = Pattern.compile(
            "mexibus|mexicable|aifa|afia|servicio electrico|servicioelectrico");

    private static Map<Integer, Boolean> lineasEnTexto(String tn) {
        LinkedHashMap<Integer, Boolean> enc = new LinkedHashMap<>();
        for (int i = 0; i < RX.length; i++)
            if (RX[i].matcher(tn).find() && !enc.containsKey(COD[i])) enc.put(COD[i], Boolean.TRUE);
        // "Servicio Eléctrico" = L2A: su "#MexibusLinea2" es solo la troncal → deja L2A, quita L2.
        if (P_SERVELEC.matcher(tn).find() && enc.containsKey(112)) enc.remove(102);
        return enc;
    }

    private static final Pattern E_REST = Pattern.compile("restablec|reanud|normaliz|opera con normalidad");
    private static final Pattern E_CIRC = Pattern.compile("realiza circuito|se realiza circuito|\\bcircuito\\b");
    private static final Pattern E_SUSP = Pattern.compile("suspend|sin servicio|cierre total|se cierra");
    private static final Pattern E_RETR = Pattern.compile("retras|avance lento|servicio lento|marcha lenta|demora|\\blento\\b");
    private static final Pattern E_PASO = Pattern.compile("omite acople|pasa de largo|sin parada|no se detiene");
    private static final Pattern E_CERR = Pattern.compile("estacion(es)? cerrad|cerrad");

    private static String estadoDe(String tn) {
        if (E_REST.matcher(tn).find()) return "Servicio restablecido";
        if (E_CIRC.matcher(tn).find()) return "Servicio parcial";
        if (E_SUSP.matcher(tn).find()) return "Sin servicio";
        if (E_RETR.matcher(tn).find()) return "Retraso en el servicio";
        if (E_PASO.matcher(tn).find()) return "Paso de largo";
        if (E_CERR.matcher(tn).find()) return "Estación cerrada";
        return "Afectación en el servicio";
    }

    private static final Pattern[] L_RX = {
            Pattern.compile("a la altura de ([A-Za-zÁÉÍÓÚÑáéíóúñ0-9][\\wáéíóúñ.\\- ]{2,35})"),
            Pattern.compile("zona de ([A-ZÁÉÍÓÚÑ][\\wáéíóúñ.\\- ]{2,35})"),
            Pattern.compile("estaci[oó]n(?:es)? ([A-ZÁÉÍÓÚÑ][\\wáéíóúñ.\\- ]{2,35})"),
    };

    private static String lugarDe(String texto) {
        for (Pattern rx : L_RX) {
            Matcher m = rx.matcher(texto);
            if (m.find()) return m.group(1).split("[,.;\\n]", 2)[0].trim();
        }
        return "";
    }

    private static final Pattern I_HASH = Pattern.compile("#\\w+");
    private static final Pattern I_WARN = Pattern.compile("[⚠️]");
    private static final Pattern I_RUIDO = Pattern.compile("(?i)tome sus precauciones|ver menos|ver mas");
    private static final Pattern I_SP = Pattern.compile("[ \\t]+");
    private static final Pattern I_NL = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern I_CONJ = Pattern.compile("(?i)^(y|e)\\s+");

    private static String infoDe(String texto) {
        String t = I_HASH.matcher(texto).replaceAll("");
        t = I_WARN.matcher(t).replaceAll("");
        t = I_RUIDO.matcher(t).replaceAll("");
        t = I_SP.matcher(t).replaceAll(" ");
        t = I_NL.matcher(t.trim()).replaceAll(" · ");   // multilínea → " · "
        t = stripChars(t, " ·,.-");
        t = I_CONJ.matcher(t).replaceAll("");
        return stripChars(t, " ·,.-");
    }

    private static final Pattern SEG = Pattern.compile(
            "^([A-Za-zÁÉÍÓÚÑáéíóúñ0-9.\\s]{3,32}?)\\s+-\\s+([A-Za-zÁÉÍÓÚÑáéíóúñ0-9.\\s]{3,32}?)$");

    private static List<String[]> segmentosCircuito(String texto) {
        List<String[]> segs = new ArrayList<>();
        if (!norm(texto).contains("circuito")) return segs;
        for (String ln0 : texto.split("\n")) {
            String ln = stripChars(ln0, " .");
            if (ln.isEmpty() || ln.startsWith("#") || ln.toLowerCase(Locale.ROOT).contains("circuito")) continue;
            Matcher m = SEG.matcher(ln);
            if (m.matches()) segs.add(new String[]{m.group(1).trim(), m.group(2).trim()});
        }
        return segs;
    }

    /** Interpreta un post → afectaciones (una por línea detectada). */
    static List<Afect> parsePost(String texto) {
        List<Afect> out = new ArrayList<>();
        if (texto == null) return out;
        String tn = norm(texto);
        if (!P_GUARD.matcher(tn).find()) return out;
        Map<Integer, Boolean> lineas = lineasEnTexto(tn);
        if (lineas.isEmpty()) return out;
        String estado = estadoDe(tn);
        String lugar = lugarDe(texto);
        String info = infoDe(texto);
        List<String[]> circ = segmentosCircuito(texto);
        for (Integer cod : lineas.keySet())
            out.add(new Afect(cod, estado, lugar, info, circ));
        return out;
    }

    // ------------------------------------------------------------------ utilidades de texto
    /** Normaliza: sin acentos, minúsculas, espacios colapsados (equiv. a _norm de Python). */
    private static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT);
        return n.replaceAll("\\s+", " ").trim();
    }

    /** Quita del inicio y final cualquier carácter contenido en {@code chars}. */
    private static String stripChars(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    // ==================================================================== FETCH RSS
    static final class Post {
        final String id, message; final long timeMs;
        Post(String id, String message, long timeMs) { this.id = id; this.message = message; this.timeMs = timeMs; }
    }

    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern PIE = Pattern.compile("(?is)—\\s*Mexib[uú]s Informa.*");

    private static List<Post> fetchFeeds() {
        List<Post> out = new ArrayList<>();
        for (String url : FEEDS) {
            try {
                byte[] data = httpGet(url);
                if (data == null) continue;
                XmlPullParser xp = android.util.Xml.newPullParser();
                xp.setInput(new ByteArrayInputStream(data), null);
                boolean inItem = false;
                String title = "", desc = "", guid = "", link = "", pub = "";
                int ev = xp.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String nm = xp.getName();
                        if ("item".equalsIgnoreCase(nm)) {
                            inItem = true; title = desc = guid = link = pub = "";
                        } else if (inItem && nm != null) {
                            if ("title".equalsIgnoreCase(nm)) title = safeText(xp);
                            else if ("description".equalsIgnoreCase(nm)) desc = safeText(xp);
                            else if ("guid".equalsIgnoreCase(nm)) guid = safeText(xp);
                            else if ("link".equalsIgnoreCase(nm)) link = safeText(xp);
                            else if ("pubDate".equalsIgnoreCase(nm)) pub = safeText(xp);
                        }
                    } else if (ev == XmlPullParser.END_TAG && "item".equalsIgnoreCase(xp.getName())) {
                        inItem = false;
                        String d = BR.matcher(desc).replaceAll("\n");
                        d = HTML.matcher(d).replaceAll(" ");
                        d = PIE.matcher(d).replaceAll("");
                        String texto = d.trim().isEmpty() ? title.trim() : d.trim();
                        if (!texto.isEmpty()) {
                            String id = !guid.isEmpty() ? guid : (!link.isEmpty() ? link : title);
                            out.add(new Post(id, texto, parseFecha(pub)));
                        }
                    }
                    ev = xp.next();
                }
            } catch (Exception ignore) { /* feed caído: se ignora */ }
        }
        return out;
    }

    /** Texto de un elemento simple; deja el parser en su END_TAG. */
    private static String safeText(XmlPullParser xp) {
        try {
            String t = xp.nextText();
            return t != null ? t.trim() : "";
        } catch (Exception e) { return ""; }
    }

    private static final SimpleDateFormat RFC822 =
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static long parseFecha(String pub) {
        if (pub == null || pub.trim().isEmpty()) return System.currentTimeMillis();
        try { Date d = RFC822.parse(pub.trim()); if (d != null) return d.getTime(); }
        catch (Exception ignore) {}
        return System.currentTimeMillis();
    }

    private static byte[] httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "GeoMB/afect");
            c.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
            if (c.getResponseCode() / 100 != 2) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
