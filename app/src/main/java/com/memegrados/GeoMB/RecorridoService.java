package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.List;
import java.util.Locale;

/**
 * Rastreo de "recorrido" (estilo Maps): sigue la ubicación del usuario por la secuencia
 * de estaciones y muestra una notificación (diseño propio, en pantalla de bloqueo y centro)
 * con la PRÓXIMA ESTACIÓN, origen/destino y pictograma, más avisos de voz (TTS) de
 * próxima estación, transbordo, llegada y afectaciones del servicio.
 */
public class RecorridoService extends Service {

    public static final String ACCION_DETENER = "detener_recorrido";
    private static final String CANAL = "recorrido";
    private static final int ID = 4401;
    private static final float CERCA_M = 60f;   // umbral "llegando a…" para Metrobús (GPS ~10-20 m)
    private static final float PASO_M = 20f;    // metros a ALEJARSE del punto más cercano para "próxima estación"
    // Mexibús: estaciones mucho más extensas (L1/L4 tienen 1 andén por sentido unidos por un paso),
    // así que se usa un radio de cobertura mayor (~500 m) para los avisos.
    private static final float CERCA_MXB_M = 50f;    // "llegando a estación" cuando estás a ≤50 m del punto (Haversine)
    private static final float PASO_MXB_M = 100f;     // aproximidad para "próxima" en Mexibús
    private static final long INTERVALO_MS = 1000L;   // revisa la ubicación cada 1 s durante el recorrido
    private static final float TURURU_VOL = 0.7f;     // volumen del "tururu" (70% del real)
    private static final long VOZ_TIMEOUT_MS = 4000L; // margen para descargar la voz Mia antes de caer al TTS

    /** Radio de "llegando" según el sistema de la parada (Mexibús = líneas >= 100). */
    private static float radioCerca(Planificador.Parada p) {
        return (p != null && p.linea >= 100) ? CERCA_MXB_M : CERCA_M;
    }
    /** Metros de alejamiento para disparar "próxima estación", según el sistema. */
    private static float radioPaso(Planificador.Parada p) {
        return (p != null && p.linea >= 100) ? PASO_MXB_M : PASO_M;
    }

    /** Distancia en metros por la Fórmula de Haversine. */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static volatile List<Planificador.Parada> paradas;
    public static volatile String terminal = "";
    public static volatile int actualIdx = -1;
    public static volatile boolean activo = false;
    public static volatile String servicioTexto = null;   // aviso único del servicio elegido (Ordinario/Express/Rosa)
    private static volatile boolean servicioAnunciado = false;
    private static volatile int avanceMin = 0;             // índice mínimo ya alcanzado (progreso monótono)
    public static volatile com.google.android.gms.maps.model.LatLng ultimaPos;   // última ubicación GPS (para el puntero)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient loc;
    private boolean ciclando = false;
    private boolean recibiendo = false;   // ya se pidió el stream continuo de ubicación
    private final Runnable tick = this::ciclo;

    /** Stream continuo de ubicación (como Google Maps): entrega en cuanto el GPS tiene un fix. */
    private final LocationCallback locCb = new LocationCallback() {
        @Override public void onLocationResult(LocationResult r) {
            if (r == null) return;
            android.location.Location l = r.getLastLocation();
            if (l != null) procesar(l);
        }
    };

    private TextToSpeech tts;
    private boolean ttsListo = false;
    private int ultVoz = -99;              // índice ya anunciado por voz (solo "llegaste" al final)
    private int ultLlegando = -99;         // estación cuya llegada ya se anunció ("Llegando a…")
    private int ultProxima = -99;          // próxima estación ya anunciada al salir de la anterior
    private int estSeguida = -99;          // estación cuyo acercamiento se está midiendo
    private float distMin = Float.MAX_VALUE;   // distancia mínima alcanzada a estSeguida (para medir "ya la pasé")
    private boolean afectacionAvisada = false;
    private boolean finalizado = false;    // ya se llegó al destino: se detiene el servicio
    private android.media.MediaPlayer mpActual;   // reproductor en curso (tururu o voz): evita traslapes
    private long vozSeq = 0;                       // secuencia de aviso: descarta voces viejas

    public static void iniciar(android.content.Context c, List<Planificador.Parada> seq, String term) {
        paradas = seq; terminal = term; actualIdx = -1; servicioAnunciado = false; avanceMin = 0;
        ContextCompat.startForegroundService(c, new Intent(c, RecorridoService.class));
    }

    public static void detener(android.content.Context c) {
        c.stopService(new Intent(c, RecorridoService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        loc = LocationServices.getFusedLocationProviderClient(this);
        crearCanal();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());               // mismo canal que el tururu, voz audible
                seleccionarVozFemenina();     // mejor aproximación a "Ximena" con el motor instalado
                tts.setSpeechRate(0.98f);
                tts.setPitch(1.05f);          // timbre ligeramente más agudo (femenino)
                ttsListo = true;
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        activo = true;
        ultVoz = -99; ultLlegando = -99; ultProxima = -99; afectacionAvisada = false;
        estSeguida = -99; distMin = Float.MAX_VALUE; finalizado = false;
        Notification n = construir(getString(R.string.recorrido_ubicando), "", null, "", null, "", null, "", "", 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID, n);
        }
        pedirUbicacion();
        return START_STICKY;
    }

    /** Al cerrar/deslizar la app de recientes, el recorrido sigue en segundo plano (no se detiene). */
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (activo && !finalizado) {
            pedirUbicacion();   // reasegura el stream de ubicación aunque la UI ya no exista
        }
    }

    /**
     * Pide actualizaciones de ubicación CONTINUAS (alta precisión, ~1 s / mín 500 ms), en vez de
     * un fix por ciclo. El GPS entrega en cuanto tiene una lectura, igual que Google Maps, así el
     * puntero y la detección Haversine de la estación se refrescan sin retraso.
     */
    @SuppressLint("MissingPermission")
    private void pedirUbicacion() {
        if (recibiendo || !tienePermiso()) return;
        recibiendo = true;
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVALO_MS)
                .setMinUpdateIntervalMillis(500L)   // acepta lecturas tan rápido como cada 0.5 s
                .setMaxUpdateDelayMillis(INTERVALO_MS)
                .build();
        loc.requestLocationUpdates(req, locCb, Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    private void ciclo() {
        if (!ciclando && paradas != null && tienePermiso()) {
            ciclando = true;
            loc.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this::procesar)
                    .addOnFailureListener(e -> ciclando = false);
        }
        handler.postDelayed(tick, INTERVALO_MS);
    }

    private void procesar(android.location.Location l) {
        ciclando = false;
        List<Planificador.Parada> seq = paradas;
        if (l == null || seq == null || seq.isEmpty()) return;
        ultimaPos = new com.google.android.gms.maps.model.LatLng(l.getLatitude(), l.getLongitude());

        // Búsqueda MONÓTONA hacia adelante: la estación más cercana se busca desde el avance mínimo ya
        // alcanzado, no desde el inicio. Así el recorrido no salta hacia atrás ni se "pega" a una estación
        // co-ubicada de otra línea antes del transbordo (se queda en su línea hasta hacer la correspondencia).
        int best = avanceMin;
        double bd = Double.MAX_VALUE;
        for (int i = avanceMin; i < seq.size(); i++) {
            double d = haversine(l.getLatitude(), l.getLongitude(),
                    seq.get(i).pos.latitude, seq.get(i).pos.longitude);   // Haversine
            if (d < bd) { bd = d; best = i; }
        }
        avanceMin = best;   // nunca retrocede
        actualIdx = best;

        // Aviso único del servicio elegido (Ordinario/Express, unidad Rosa) al iniciar el recorrido.
        if (servicioTexto != null && !servicioAnunciado) {
            servicioAnunciado = true;
            sonarYHablar(servicioTexto, seq.get(0).linea);
        }

        // Mide el acercamiento a la estación más cercana: cuando ya te alejaste PASO_M de tu punto
        // más cercano, se considera que "ya la pasaste" y se anuncia la próxima.
        if (best != estSeguida) { estSeguida = best; distMin = (float) bd; }
        else if (bd < distMin) distMin = (float) bd;

        int last = seq.size() - 1;
        boolean fin = best >= last;
        int proxIdx = Math.min(best + 1, last);
        int antIdx = Math.max(0, proxIdx - 1);     // estación anterior a la próxima
        int postIdx = Math.min(last, proxIdx + 1); // estación posterior a la próxima
        Planificador.Parada prox = seq.get(proxIdx), ant = seq.get(antIdx), post = seq.get(postIdx);

        String estado;
        if (fin) {
            estado = getString(R.string.recorrido_llegaste, vis(seq.get(best)));
        } else if (prox.transbordo) {
            estado = getString(R.string.recorrido_transborda, vis(prox), prox.linea);
        } else {
            estado = getString(R.string.recorrido_vas, vis(seq.get(best)), vis(prox));
        }

        // notificación (diseño propio): anterior · PRÓXIMA · posterior (sin MXB, con nº si el nombre se repite)
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID, construir(vis(prox), estado, pico(prox),
                vis(ant), pico(ant), vis(post), pico(post),
                vis(seq.get(0)), vis(seq.get(last)), seq.get(best).color));

        // VOZ (precedida del "tururu"): al ARRIBAR a la estación dice "Llegando a estación: X"; si es
        // transbordo añade "Transbordo con Línea #"; si es terminal, "nadie debe permanecer a bordo"; y
        // de forma aleatoria un consejo. Al pasar la estación (ya te alejaste PASO_M), anuncia la próxima.
        if (fin) {
            if (best != ultVoz) { ultVoz = best; sonarYHablar(getString(R.string.voz_llegaste, nom(seq.get(best))), seq.get(best).linea); }
            // Ruta terminada: deja de rastrear y detén el servicio (la notificación se elimina en
            // onDestroy) tras dar tiempo a que se escuche el aviso final.
            if (!finalizado) {
                finalizado = true;
                handler.removeCallbacks(tick);
                detenerUbicacion();   // ya llegaste: corta el GPS para ahorrar batería
                handler.postDelayed(this::stopSelf, 12000);
            }
        } else if (ultLlegando == best && ultProxima != proxIdx && bd >= distMin + radioPaso(seq.get(best))) {
            ultProxima = proxIdx;
            // Próxima estación + correspondencias de esa estación (líneas con las que conecta).
            String voz = getString(R.string.voz_proxima, nom(prox));
            String tb = transbordoTexto(prox);
            if (tb != null) voz += tb;
            sonarYHablar(voz, prox.linea);
        } else if (bd <= radioCerca(seq.get(best)) && ultLlegando != best) {
            ultLlegando = best;
            ultProxima = -99;
            // Si la próxima parada es el transbordo del usuario, avísale que se prepare (con sus
            // correspondencias); si no, el aviso normal de llegada (con transbordo/terminal/consejo).
            if (prox.transbordo) {
                String voz = avisoCorrespondencia(prox);
                if (voz == null) {   // sin correspondencia detectada: aviso genérico
                    voz = getString(R.string.voz_transbordo_prep);
                    String tb = transbordoTexto(prox);
                    if (tb != null) voz += tb;
                }
                sonarYHablar(voz, prox.linea);
            } else {
                sonarYHablar(avisoLlegada(seq, best), seq.get(best).linea);
            }
        }
        // voz de afectación (una vez, si aparece durante el recorrido), con voz Mia.
        if (Manifestaciones.hay() && !afectacionAvisada) {
            afectacionAvisada = true;
            sonarYHablar(getString(R.string.voz_afectacion), seq.get(best).linea);
        } else if (!Manifestaciones.hay()) {
            afectacionAvisada = false;
        }
    }

    private void hablar(String t) {
        if (ttsListo && tts != null && t != null) {
            android.os.Bundle p = new android.os.Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);   // voz a volumen máximo
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, p, "geomb");
        }
    }

    /**
     * PRIMERO el "tururu" (res/raw/tururu) y, cuando TERMINA, la voz — con un checador
     * (onCompletion + retardo corto) para que no se encimen y la voz sí se escuche. Si no está el
     * audio, habla directo.
     */
    private void sonarYHablar(String texto) { sonarYHablar(texto, 0); }

    /** Jingle según el sistema de la estación (Mexibús/Mexicable = tururu_mxb; Metrobús = tururu_mb) + voz. */
    private void sonarYHablar(String texto, int linea) {
        final long seq = ++vozSeq;            // este es el aviso más nuevo
        soltarActual();                       // corta cualquier audio en curso (evita el "agudo" por traslape)
        String raw = linea >= 100 ? "tururu_mxb" : "tururu_mb";
        int id = getResources().getIdentifier(raw, "raw", getPackageName());
        if (id == 0) id = getResources().getIdentifier("tururu", "raw", getPackageName());   // respaldo
        if (id != 0) {
            try {
                android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, id);
                if (mp != null) {
                    mpActual = mp;
                    mp.setVolume(TURURU_VOL, TURURU_VOL);   // ~70% para que no reviente los oídos
                    mp.setOnCompletionListener(m -> {
                        try { m.release(); } catch (Exception ignore) {}
                        if (mpActual == m) mpActual = null;
                        handler.postDelayed(() -> decirConVoz(texto, seq), 250);   // deja respirar antes de la voz
                    });
                    mp.setOnErrorListener((m, a, b) -> {
                        try { m.release(); } catch (Exception ignore) {}
                        if (mpActual == m) mpActual = null;
                        decirConVoz(texto, seq);
                        return true;
                    });
                    mp.start();
                    return;
                }
            } catch (Exception ignore) {}
        }
        decirConVoz(texto, seq);
    }

    /** Detiene y libera el reproductor en curso (tururu o voz) para que no se encimen. */
    private void soltarActual() {
        if (mpActual != null) {
            try { mpActual.stop(); } catch (Exception ignore) {}
            try { mpActual.release(); } catch (Exception ignore) {}
            mpActual = null;
        }
    }

    // ---- voz Mia (AWS Polly) con caché local; respaldo al TTS de Android ----

    /** Reproduce la frase con la voz Mia (mp3 del backend, cacheada). Si falla/offline, usa TTS. */
    private void decirConVoz(String texto, long seq) {
        if (seq != vozSeq) return;   // ya llegó un aviso más nuevo: descarta este
        java.io.File cache = archivoVoz(texto);
        if (cache != null && cache.exists() && cache.length() > 0) { reproducir(cache); return; }
        // Descarga la voz Mia PERO con un límite de ~2 s: si no llega a tiempo, se habla ya con el
        // TTS de Google para no dejar esperando; la descarga sigue y queda cacheada para la próxima.
        final boolean[] resuelto = {false};
        handler.postDelayed(() -> {
            if (seq != vozSeq || resuelto[0]) return;
            resuelto[0] = true;
            hablar(texto);   // se pasó de los 2 s: TTS inmediato
        }, VOZ_TIMEOUT_MS);
        new Thread(() -> {
            java.io.File out = descargarVoz(texto);
            handler.post(() -> {
                if (seq != vozSeq || resuelto[0]) return;   // ya habló el TTS por timeout, o cambió el aviso
                resuelto[0] = true;
                if (out != null && out.exists() && out.length() > 0) reproducir(out);
                else hablar(texto);   // respaldo: TTS de Android (offline o error)
            });
        }, "voz-mia").start();
    }

    private java.io.File archivoVoz(String texto) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "voz");
            if (!dir.exists()) dir.mkdirs();
            return new java.io.File(dir, Integer.toHexString(("Mia|" + texto).hashCode()) + ".mp3");
        } catch (Exception e) { return null; }
    }

    private java.io.File descargarVoz(String texto) {
        java.io.File out = archivoVoz(texto);
        if (out == null) return null;
        java.net.HttpURLConnection c = null;
        try {
            String u = "https://geomb.duckdns.org/api/tts?voz=Mia&texto=" + android.net.Uri.encode(texto);
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() / 100 != 2) return null;
            try (java.io.InputStream in = c.getInputStream();
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void reproducir(java.io.File f) {
        try {
            soltarActual();   // no encimar con audio previo
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mpActual = mp;
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setVolume(1f, 1f);
            mp.setOnCompletionListener(m -> {
                try { m.release(); } catch (Exception ignore) {}
                if (mpActual == m) mpActual = null;
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            /* si el mp3 falla, no truena */
        }
    }

    /** Nombre para HABLAR: sin el prefijo interno "MXB " (limpio, sin número de línea). */
    private static String nom(Planificador.Parada p) {
        return p != null ? Planificador.sinMxb(p.nombre) : null;
    }

    /** Nombre para MOSTRAR (notificación): sin MXB y con nº de línea si el nombre se repite. */
    private String vis(Planificador.Parada p) {
        return p != null ? Planificador.nombreMostrar(this, p.nombre, p.linea) : null;
    }

    /** Arma el aviso de llegada: "Llegando a estación: X" (+ correspondencia / terminal / consejo). */
    private String avisoLlegada(List<Planificador.Parada> seq, int best) {
        Planificador.Parada p = seq.get(best);
        boolean term = esTerminal(p);
        StringBuilder v = new StringBuilder(getString(
                term ? R.string.voz_llegando_terminal : R.string.voz_llegando_est, nom(p)));
        String tb = transbordoTexto(p);   // usa p.nombre canónico (con MXB) para las correspondencias
        if (tb != null) v.append(tb);   // "Transbordo con Línea(s) ..." (correspondencias, orden numérico)
        if (term) v.append(". ").append(getString(R.string.voz_terminal));
        String tip = tipAleatorio(p.linea);
        if (tip != null) v.append(". ").append(tip);
        return v.toString();
    }

    /**
     * Texto de transbordo de la estación: TODAS las líneas que la sirven (correspondencia), en orden
     * numérico y QUITANDO la línea por la que vienes. Ej. Buenavista (L1+L3+L4): si vienes por L3 dice
     * "Líneas 1 y 4"; por L1 "Líneas 3 y 4"; por L4 "Líneas 1 y 3". Devuelve null si no hay otras.
     */
    private String transbordoTexto(Planificador.Parada p) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(p);
        lineas.remove(p.linea);
        if (lineas.isEmpty()) return null;
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (int n : lineas) ls.add(etiquetaLinea(n, p.nombre));   // "Línea 3", "Línea 4 Ruta Norte", …
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return getString(R.string.voz_transbordo, sb.toString());
    }

    /** Palabra del cambio de servicio según sistemas: Metrobús=transbordo, Mexibús/Mexicable=correspondencia, mixto=conexión. */
    private int palabraTransferencia(int lineaActual, int lineaOtra) {
        boolean aMetro = lineaActual < 100, bMetro = lineaOtra < 100;
        if (aMetro && bMetro) return R.string.voz_palabra_transbordo;
        if (!aMetro && !bMetro) return R.string.voz_palabra_correspondencia;
        return R.string.voz_palabra_conexion;
    }

    /**
     * Aviso completo al acercarse a la estación de cambio: "Atención, estás llegando a la estación donde
     * realizarás tu {palabra}, favor de irte preparando. Estación {nombre}. {palabra} con {líneas}."
     * La palabra (correspondencia/conexión/transbordo) depende de los sistemas involucrados.
     */
    private String avisoCorrespondencia(Planificador.Parada prox) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(prox);
        lineas.remove(baseLinea(prox.linea));
        if (lineas.isEmpty()) return null;
        String palabra = getString(palabraTransferencia(prox.linea, lineas.first()));
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (int n : lineas) ls.add(etiquetaLinea(n, prox.nombre));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return getString(R.string.voz_prep_corresp, palabra, nom(prox), sb.toString());
    }

    /** Etiqueta de línea para la correspondencia; L4 añade su ruta (Norte/Sur) según la estación. */
    private String etiquetaLinea(int n, String estacion) {
        if (n >= 200) return getString(R.string.voz_linea_mxc, n - 200);   // Mexicable Línea X
        if (n >= 100) return getString(R.string.voz_linea_mxb, n - 100);   // Mexibús Línea X
        if (n == 4) {
            int r = RutasMixtas.rutaL4(estacion);
            if (r == 1) return getString(R.string.voz_linea_l4, getString(R.string.voz_ruta_norte));
            if (r == 2) return getString(R.string.voz_linea_l4, getString(R.string.voz_ruta_sur));
            // ambas rutas (Buenavista, San Lázaro, …): "L4" a secas
        }
        return getString(R.string.voz_linea_n, n);
    }

    private static final double CORRESP_VOZ_M = 600.0;   // radio para considerar correspondencia por voz

    /** Línea base (agrupa ordinario/ramal/exprés de una misma línea, y Mexicable por su número). */
    private static int baseLinea(int n) {
        if (n >= 200) return 200 + (n % 10);
        if (n >= 100) return 100 + (n % 10);
        return n;
    }

    /**
     * Líneas con las que ESTA estación tiene correspondencia, POR CERCANÍA física (≤ CORRESP_VOZ_M),
     * no por nombre: así se anuncian aunque el nombre difiera (p. ej. Santa Clara/Periférico/Indios
     * Verdes ↔ Mexicable). Se excluyen los servicios de la MISMA línea base (ordinario/exprés no son
     * transbordo entre sí) y las homónimas lejanas (que no están cerca) nunca se enlazan.
     */
    private java.util.TreeSet<Integer> lineasEnEstacion(Planificador.Parada p) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        if (p == null || p.pos == null) return s;
        int bp = baseLinea(p.linea);
        try {
            for (Linea l : GtfsRepository.getLineas(this)) {
                if (baseLinea(l.numero) == bp) continue;   // misma línea/servicio: no es transbordo
                for (Estacion e : l.estaciones) {
                    double d = haversine(p.pos.latitude, p.pos.longitude,
                            e.posicion.latitude, e.posicion.longitude);
                    if (d <= CORRESP_VOZ_M) { s.add(baseLinea(l.numero)); break; }
                }
            }
        } catch (Exception ignore) {}
        return s;
    }

    /** ¿La parada es terminal de su línea? */
    private boolean esTerminal(Planificador.Parada p) {
        String[] t = Planificador.terminales(p.linea);
        if (t == null) return false;
        String nn = Planificador.norm(p.nombre);
        return (t[0] != null && Planificador.norm(t[0]).equals(nn))
                || (t[1] != null && Planificador.norm(t[1]).equals(nn));
    }

    /** Consejo de seguridad aleatorio (o null, para que no salga siempre). */
    private String tipAleatorio(int linea) {
        if (Math.random() < 0.5) return null;
        java.util.List<Integer> tips = new java.util.ArrayList<>();
        tips.add(R.string.voz_tip_espacios);
        tips.add(R.string.voz_tip_objetos);
        tips.add(R.string.voz_tip_correr);
        if (linea >= 100 && linea < 200) tips.add(R.string.voz_tip_rosa);   // unidades rosas: exclusivas de Mexibús
        return getString(tips.get((int) (Math.random() * tips.size())));
    }

    /**
     * Elige la mejor voz femenina en español (México &gt; EE.UU. &gt; España) entre las que
     * ofrezca el motor TTS instalado. Es la aproximación más cercana a la voz "Ximena"
     * (Loquendo es software propietario y no puede empaquetarse en la app).
     */
    private void seleccionarVozFemenina() {
        try {
            java.util.Set<Voice> voces = tts.getVoices();
            if (voces == null || voces.isEmpty()) return;
            Voice mejor = null;
            int mejorPuntaje = -1;
            for (Voice v : voces) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage();
                if (!"es".equalsIgnoreCase(lang)) continue;
                int p = 0;
                String pais = v.getLocale().getCountry();
                if ("MX".equalsIgnoreCase(pais)) p += 40;
                else if ("US".equalsIgnoreCase(pais)) p += 25;
                else if ("ES".equalsIgnoreCase(pais)) p += 15;
                String n = v.getName() != null ? v.getName().toLowerCase(Locale.ROOT) : "";
                if (n.contains("fem") || n.contains("female") || n.contains("-f-")
                        || n.matches(".*-x-\\w*f\\w*")) p += 30;   // pistas de voz femenina
                if (!v.isNetworkConnectionRequired()) p += 5;      // prioriza voz local
                if (v.getQuality() >= Voice.QUALITY_HIGH) p += 5;
                if (p > mejorPuntaje) { mejorPuntaje = p; mejor = v; }
            }
            if (mejor != null) tts.setVoice(mejor);
        } catch (Throwable ignore) {}
    }

    private Bitmap pictograma(String icono) {
        // El pictograma se muestra a ~44dp; se decodifica reducido (helper compartido).
        int destPx = Math.round(48 * getResources().getDisplayMetrics().density);
        return Iconos.pictograma(this, icono, destPx);
    }

    /** Icono de estación para la notificación: pictograma (nuevos) o PUNTO del color (antiguos). */
    private Bitmap pico(Planificador.Parada p) {
        if (p == null) return null;
        Bitmap b = pictograma(p.icono);
        if (b != null) return b;
        int px = Math.round(48 * getResources().getDisplayMetrics().density);
        Bitmap dot = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(dot);
        android.graphics.Paint pa = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pa.setColor(0xFFFFFFFF); c.drawCircle(px / 2f, px / 2f, px * 0.30f, pa);
        pa.setColor(p.color);    c.drawCircle(px / 2f, px / 2f, px * 0.22f, pa);
        return dot;
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CANAL,
                    getString(R.string.canal_recorrido), NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ABRIR_RUTA, true);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent piDetener() {
        Intent i = new Intent(this, RecorridoService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construir(String proxima, String estado, Bitmap picProx,
                                  String anterior, Bitmap picAnt, String posterior, Bitmap picPost,
                                  String origenViaje, String destinoViaje, int colorLinea) {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_recorrido);
        // Próxima estación en Tipo Metro (bitmap); el resto en texto normal.
        Bitmap nombreBmp = Tipografia.render(this, proxima, 20f, 0xFFC8103E, true);
        if (nombreBmp != null) {
            rv.setImageViewBitmap(R.id.nr_estacion, nombreBmp);
            rv.setContentDescription(R.id.nr_estacion, proxima);
        }
        rv.setTextViewText(R.id.nr_nom_prox, proxima);
        rv.setTextViewText(R.id.nr_estado, estado);
        rv.setTextViewText(R.id.nr_nom_ant, anterior);
        rv.setTextViewText(R.id.nr_nom_post, posterior);
        rv.setTextViewText(R.id.nr_origen, getString(R.string.notif_origen_fmt, origenViaje));
        rv.setTextViewText(R.id.nr_destino, getString(R.string.notif_destino_fmt, destinoViaje));
        if (picProx != null) rv.setImageViewBitmap(R.id.nr_ic_prox, picProx);
        if (picAnt != null) rv.setImageViewBitmap(R.id.nr_ic_ant, picAnt);
        if (picPost != null) rv.setImageViewBitmap(R.id.nr_ic_post, picPost);

        // Vista COLAPSADA / pantalla de bloqueo: contenido ESTÁNDAR (título + texto + barra de progreso
        // nativa). Así se ve siempre, aunque la pantalla esté bloqueada o el sistema no renderice el
        // layout propio. El RemoteViews bonito queda SOLO para la vista expandida (setCustomBigContentView).
        String titulo = getString(R.string.notif_proxima) + ": " + proxima;
        int color = colorLinea != 0 ? colorLinea : 0xFFC8103E;   // cambia con la línea actual del tramo
        List<Planificador.Parada> seq = paradas;
        int total = (seq != null && seq.size() > 1) ? seq.size() - 1 : 1;
        int avance = Math.max(0, Math.min(total, actualIdx));
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_route)
                .setContentTitle(titulo)
                .setContentText(estado)
                .setProgress(total, avance, false)   // barra de progreso estándar (visible bloqueada)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(rv)          // solo la vista EXPANDIDA usa el layout propio
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(R.drawable.ic_route, getString(R.string.recorrido_detener), piDetener())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setColorized(true)
                .setColor(color)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private boolean tienePermiso() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Corta el stream continuo de ubicación (batería). */
    private void detenerUbicacion() {
        if (!recibiendo) return;
        recibiendo = false;
        if (loc != null) loc.removeLocationUpdates(locCb);
    }

    @Override public void onDestroy() {
        activo = false; actualIdx = -1; ultimaPos = null;
        detenerUbicacion();
        handler.removeCallbacksAndMessages(null);   // cancela tick y el stopSelf diferido
        vozSeq++;                                    // invalida cualquier voz pendiente
        soltarActual();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        // Quita la notificación al terminar/detener la ruta (finalizar ruta o llegada al destino).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
