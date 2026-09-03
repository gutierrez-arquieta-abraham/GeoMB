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
    // MISMA fórmula para Metrobús y Mexibús (Haversine): a ≤50 m del punto avisa la LLEGADA; y al
    // alejarse ~100 m del punto más cercano (cruzando hacia la siguiente) avisa la PRÓXIMA estación.
    private static final float CERCA_M = 50f;   // "llegando a estación" cuando estás a ≤50 m del punto
    private static final float PASO_M = 100f;   // metros a ALEJARSE del punto más cercano para "próxima estación"
    private static final float CERCA_MXB_M = 50f;
    private static final float PASO_MXB_M = 100f;
    // Anillo de cobertura extra: dentro de (llegada 50 m + 50 m) NO se anuncia la próxima estación,
    // solo la llegada. La "próxima" se dispara al SALIR de esos ~100 m del punto más cercano.
    private static final float COBERTURA_EXTRA_M = 50f;
    // En una correspondencia, el aviso cambia de línea SOLO cuando estás en el andén de la otra línea.
    // ~15 m (el usuario pidió <10 m; se deja un poco más por el error típico del GPS para no quedar clavado).
    private static final float CAMBIO_LINEA_M = 15f;
    // Fin de recorrido: el "llegaste" debe sonar SOBRE el punto (1–5 m), no al radio de llegada de 50 m.
    // Excepción: Mexibús L4 (Indios Verdes) son 2 andenes y la unidad avanza hasta el fondo rebasando el
    // punto, así que ahí se conserva el radio normal.
    private static final float FIN_M = 5f;
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
        persistir(c);   // guarda la ruta: si el SO mata el proceso, el servicio (START_STICKY) la restaura
        ContextCompat.startForegroundService(c, new Intent(c, RecorridoService.class));
    }

    public static void detener(android.content.Context c) {
        limpiarPersistencia(c);
        c.stopService(new Intent(c, RecorridoService.class));
    }

    private static final String PREFS = "recorrido_estado";

    /** Serializa la ruta activa (paradas + terminal + servicio) para poder resumir tras muerte de proceso. */
    private static void persistir(android.content.Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("term", terminal == null ? "" : terminal);
            o.put("svc", servicioTexto == null ? org.json.JSONObject.NULL : servicioTexto);
            org.json.JSONArray arr = new org.json.JSONArray();
            if (paradas != null) for (Planificador.Parada p : paradas) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("n", p.nombre); j.put("l", p.linea); j.put("c", p.color);
                j.put("t", p.transbordo); j.put("la", p.pos.latitude); j.put("lo", p.pos.longitude);
                j.put("i", p.icono == null ? org.json.JSONObject.NULL : p.icono);
                arr.put(j);
            }
            o.put("paradas", arr);
            c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString("ruta", o.toString()).apply();
        } catch (Exception ignore) {}
    }

    /** Reconstruye {@link #paradas} desde disco cuando el proceso revivió sin la ruta en memoria. */
    private boolean restaurar() {
        try {
            String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString("ruta", null);
            if (s == null) return false;
            org.json.JSONObject o = new org.json.JSONObject(s);
            terminal = o.optString("term", "");
            servicioTexto = o.isNull("svc") ? null : o.optString("svc", null);
            org.json.JSONArray arr = o.getJSONArray("paradas");
            java.util.List<Planificador.Parada> seq = new java.util.ArrayList<>();
            for (int k = 0; k < arr.length(); k++) {
                org.json.JSONObject j = arr.getJSONObject(k);
                seq.add(new Planificador.Parada(j.getString("n"), j.getInt("l"), j.getInt("c"),
                        j.getBoolean("t"),
                        new com.google.android.gms.maps.model.LatLng(j.getDouble("la"), j.getDouble("lo")),
                        j.isNull("i") ? null : j.getString("i")));
            }
            if (seq.isEmpty()) return false;
            paradas = seq; actualIdx = -1; servicioAnunciado = false; avanceMin = 0;
            return true;
        } catch (Exception e) { return false; }
    }

    private void limpiarPersistencia() { limpiarPersistencia(this); }
    private static void limpiarPersistencia(android.content.Context c) {
        try { c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().remove("ruta").apply(); } catch (Exception ignore) {}
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
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) { limpiarPersistencia(); stopSelf(); return START_NOT_STICKY; }
        // Proceso revivido por el SO (intent null) sin la ruta en memoria: recárgala del disco.
        if (paradas == null && !restaurar()) { limpiarPersistencia(); stopSelf(); return START_NOT_STICKY; }
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

    private java.util.List<com.google.android.gms.maps.model.LatLng> ivPlataformas;

    /** Las 4 plataformas de Indios Verdes (Metrobús L1/L7, Mexibús L4, Mexicable L2), cacheadas. */
    private java.util.List<com.google.android.gms.maps.model.LatLng> plataformasIV() {
        if (ivPlataformas != null) return ivPlataformas;
        java.util.List<com.google.android.gms.maps.model.LatLng> ls = new java.util.ArrayList<>();
        try {
            java.util.List<Linea> todas = new java.util.ArrayList<>(GtfsRepository.getLineas(this));
            todas.addAll(GtfsRepository.getMexibus(this));
            for (Linea li : todas)
                for (Estacion e : li.estaciones)
                    if (Planificador.norm(e.nombre).contains("indios verdes")) ls.add(e.posicion);
        } catch (Exception ignore) {}
        ivPlataformas = ls;
        return ls;
    }

    /** Distancia al PUNTO de la parada. El punto de Indios Verdes ya es la plataforma direccional
     *  (lo ajusta el planificador), así que basta medir contra p.pos: a ≤50 m avisa la llegada. */
    private double distParada(android.location.Location l, Planificador.Parada p) {
        return haversine(l.getLatitude(), l.getLongitude(), p.pos.latitude, p.pos.longitude);
    }

    /** Nº mínimo de estaciones dentro de una línea (tras la correspondencia) para dar por hecho que ya
     *  vas en ella y saltar el aviso a esa línea. */
    private static final int SALTO_MIN_ESTACIONES = 3;

    /**
     * Si la ubicación está sobre una parada de OTRA línea de la ruta, situada al menos
     * {@link #SALTO_MIN_ESTACIONES} estaciones dentro de esa línea (después de la correspondencia),
     * devuelve su índice para reanclar ahí. Si no, devuelve {@code best}.
     */
    private int reanclarOtraLinea(android.location.Location l, List<Planificador.Parada> seq, int best) {
        int mejor = best;
        int baseBest = baseLinea(seq.get(best).linea);
        for (int j = best + 1; j < seq.size(); j++) {
            Planificador.Parada pj = seq.get(j);
            if (baseLinea(pj.linea) == baseBest) continue;             // misma línea que la actual: no aplica
            if (distParada(l, pj) > radioCerca(pj)) continue;          // no estás sobre esa parada
            // ¿Cuántas estaciones consecutivas de ESA línea terminan en j? (profundidad tras el cambio)
            int dentro = 0;
            for (int k = j; k >= 0 && baseLinea(seq.get(k).linea) == baseLinea(pj.linea); k--) dentro++;
            if (dentro >= SALTO_MIN_ESTACIONES) mejor = j;             // toma la más adelantada válida
        }
        return mejor;
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
        double bd = distParada(l, seq.get(avanceMin));
        for (int i = avanceMin + 1; i < seq.size(); i++) {
            Planificador.Parada pi = seq.get(i);
            double d = distParada(l, pi);   // Haversine (Indios Verdes: la más cercana de sus 4 plataformas)
            if (pi.linea != seq.get(best).linea) {
                // Correspondencia (cambio de línea): el aviso NO cambia a la nueva línea hasta que estás
                // físicamente en su andén. Te aferras a la línea actual mientras caminas la correspondencia.
                // Si es la MISMA estación (mismo nombre, p. ej. Puente de Fierro L2↔L4, u ordinario↔exprés
                // de la misma base), el cambio se permite dentro del radio de LLEGADA (~50 m), porque estar
                // en ese andén ya cuenta como haber hecho la correspondencia. Para andenes co-ubicados de
                // DISTINTO nombre se mantiene el umbral estricto (CAMBIO_LINEA_M) para no saltar antes.
                float umbral = mismaEstacion(pi, seq.get(best)) ? radioCerca(pi) : CAMBIO_LINEA_M;
                if (d <= umbral) { bd = d; best = i; }
                else break;
            } else if (d < bd) {
                bd = d; best = i;
            }
        }
        // Reanclaje a OTRA línea: si la ubicación ya está claramente sobre una parada MUY adentro de otra
        // línea de la ruta (≥3 estaciones después de la correspondencia), el aviso salta a esa línea. Así,
        // si te subiste directo o la línea previa cerró, el recorrido no queda clavado en la anterior.
        int salto = reanclarOtraLinea(l, seq, best);
        if (salto > best) best = salto;

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
        // Fin sobre el punto (≤5 m); Mexibús L4 conserva el radio normal (2 andenes, la unidad rebasa el punto).
        boolean finL4 = seq.get(last).linea == 104 || seq.get(last).linea == 124;
        boolean fin = best >= last && bd <= (finL4 ? radioCerca(seq.get(last)) : FIN_M);
        int proxIdx = Math.min(best + 1, last);
        int antIdx = Math.max(0, proxIdx - 1);     // estación anterior a la próxima
        int postIdx = Math.min(last, proxIdx + 1); // estación posterior a la próxima
        Planificador.Parada prox = seq.get(proxIdx), ant = seq.get(antIdx), post = seq.get(postIdx);

        String estado;
        if (fin) {
            estado = getString(R.string.recorrido_llegaste, vis(seq.get(best)));
        } else if (prox.transbordo) {
            estado = getString(R.string.recorrido_transborda, vis(prox), Planificador.etiquetaLineaCortaPub(prox.linea));
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
                limpiarPersistencia();   // llegaste: no debe resumir tras muerte de proceso
                handler.removeCallbacks(tick);
                detenerUbicacion();   // ya llegaste: corta el GPS para ahorrar batería
                handler.postDelayed(this::stopSelf, 12000);
            }
        } else if (ultLlegando == best && ultProxima != proxIdx && bd > radioCerca(seq.get(best)) + COBERTURA_EXTRA_M) {
            ultProxima = proxIdx;
            // Te acercas a la estación de BAJADA si la parada siguiente a "prox" es un transbordo.
            boolean prepararse = proxIdx + 1 <= last && seq.get(proxIdx + 1).transbordo;
            sonarYHablar(vozProxima(prox, prepararse), prox.linea);   // "Próxima estación X" (+ correspondencia / prep)
        } else if (bd <= radioCerca(seq.get(best)) && ultLlegando != best) {
            ultLlegando = best;
            ultProxima = -99;
            sonarYHablar(vozLlegada(seq, best), seq.get(best).linea);   // "Llegando a X" (+ correspondencia / terminal / consejo)
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

    /** Nombre para HABLAR: sin el prefijo interno "MXB " ni el paréntesis de conexión
     *  ("Pantitlán (conexión Metrobús L4)" → "Pantitlán"). */
    private static String nom(Planificador.Parada p) {
        if (p == null) return null;
        String s = Planificador.sinMxb(p.nombre);
        int par = s.indexOf('(');
        return par >= 0 ? s.substring(0, par).trim() : s;
    }

    /** Nombre para MOSTRAR (notificación): sin MXB y con nº de línea si el nombre se repite. */
    private String vis(Planificador.Parada p) {
        return p != null ? Planificador.nombreMostrar(this, p.nombre, p.linea) : null;
    }

    /** Aviso de PRÓXIMA estación. Si tiene correspondencia (líneas cercanas) la nombra; y si el usuario
     *  transbordará ahí, añade la preparación ("atención… favor de irte preparando"). */
    private String vozProxima(Planificador.Parada prox, boolean prepararse) {
        if (esTerminal(prox)) return vozTerminal(prox, false);   // terminal de línea
        String tf = transferenciaTexto(prox.linea, basesCorresp(prox));
        StringBuilder v = tf.isEmpty()
                ? new StringBuilder(getString(R.string.voz_proxima, nom(prox)))
                : new StringBuilder(getString(R.string.voz_prox_base, nom(prox))).append(tf);
        // "Favor de irte preparando" cuando te ACERCAS a la estación donde bajarás a hacer la
        // correspondencia (no cuando ya llegaste al andén de la otra línea).
        if (prepararse) v.append(". ").append(getString(R.string.voz_transbordo_prep));
        return v.toString();
    }

    /** Aviso de LLEGADA a la estación best. Terminal → mensaje de terminal; con correspondencia → nombra
     *  las líneas con su palabra (transbordo/correspondencia/conexión); si no, aviso simple + consejo. */
    private String vozLlegada(List<Planificador.Parada> seq, int best) {
        Planificador.Parada p = seq.get(best);
        if (esTerminal(p)) return vozTerminal(p, true);
        String tf = transferenciaTexto(p.linea, basesCorresp(p));
        if (tf.isEmpty()) {
            StringBuilder v = new StringBuilder(getString(R.string.voz_llegando_est, nom(p)));
            String tip = tipAleatorio(p.linea);
            if (tip != null) v.append(". ").append(tip);
            return v.toString();
        }
        return getString(R.string.voz_lleg_base, nom(p)) + tf;
    }

    /**
     * Aviso de estación TERMINAL de línea (llegada o próxima). Ej.:
     *  · Metrobús L1 Indios Verdes: "…estación terminal, Indios Verdes, transbordo con Línea 7.
     *    Conexión con Meksibús Línea 4 y Meksicable Línea 2".
     *  · Mexibús L4 La Raza: "…estación terminal, La Raza, conexión con Metrobús Líneas 1 y 3".
     */
    private String vozTerminal(Planificador.Parada p, boolean llegada) {
        StringBuilder v = new StringBuilder(getString(
                llegada ? R.string.voz_term_lleg : R.string.voz_term_prox, nom(p)));
        return v.append(transferenciaTexto(p.linea, basesCorresp(p))).toString();
    }

    /** Bases de correspondencia de la parada, respetando la visibilidad de la capa Mexibús/Mexicable. */
    private java.util.TreeSet<Integer> basesCorresp(Planificador.Parada p) {
        java.util.TreeSet<Integer> bases = lineasEnEstacion(p);
        correspManuales(p, bases);   // enlaces que no tienen estación física del otro sistema en los datos
        if (!Modos.mostrarMexibus(this)) {   // capa Mexibús/Mexicable apagada: no anunciar esos sistemas
            java.util.Iterator<Integer> it = bases.iterator();
            while (it.hasNext()) if (it.next() >= 100) it.remove();
        }
        return bases;
    }

    /** Correspondencias declaradas en el propio nombre de la estación (p. ej. Pantitlán / Calle 6 de
     *  Mexibús L3 → "(conexión Metrobús L4)"): se leen del texto y se añaden a las bases. */
    private void correspManuales(Planificador.Parada p, java.util.TreeSet<Integer> bases) {
        String nn = Planificador.norm(p.nombre);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(metrobus|mexibus|mexicable)\\s+l\\s*([0-9]+)").matcher(nn);
        while (m.find()) {
            int off = m.group(1).equals("metrobus") ? 0 : (m.group(1).equals("mexibus") ? 100 : 200);
            try { bases.add(off + Integer.parseInt(m.group(2))); } catch (NumberFormatException ignore) {}
        }
        bases.remove(baseLinea(p.linea));
    }

    /**
     * Texto de transferencia de una estación: agrupa las líneas por PALABRA (transbordo Metrobús↔Metrobús,
     * correspondencia Mexibús/Mexicable entre sí, conexión entre Metrobús y Edomex) y, dentro de cada palabra,
     * por sistema, anteponiendo el nombre del sistema cuando difiere del que se viaja. Ej. desde Mexibús:
     * ", conexión con Metrobús Líneas 1 y 3".
     */
    private String transferenciaTexto(int lineaActual, java.util.TreeSet<Integer> bases) {
        bases.remove(baseLinea(lineaActual));
        if (bases.isEmpty()) return "";
        int[] orden = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion};
        java.util.Map<Integer, java.util.TreeSet<Integer>> porPalabra = new java.util.HashMap<>();
        for (int r : orden) porPalabra.put(r, new java.util.TreeSet<>());
        for (int n : bases) porPalabra.get(palabraTransferencia(lineaActual, n)).add(n);
        StringBuilder v = new StringBuilder();
        boolean primero = true;
        for (int r : orden) {
            java.util.TreeSet<Integer> g = porPalabra.get(r);
            if (g.isEmpty()) continue;
            String lista = listaLineas(lineaActual, g);
            String palabra = getString(r);
            if (primero) { v.append(getString(R.string.voz_tf_primera, palabra, lista)); primero = false; }
            else { v.append(getString(R.string.voz_tf_sig, cap(palabra), lista)); }
        }
        return v.toString();
    }

    /** Lista de líneas agrupadas por sistema: "Líneas 1 y 3" (mismo sistema) o "Metrobús Líneas 1 y 3"
     *  / "Meksibús Línea 4 y Meksicable Línea 2" (otro sistema, con su nombre). */
    private String listaLineas(int lineaActual, java.util.TreeSet<Integer> bases) {
        int sisAct = sistemaDe(lineaActual);
        java.util.LinkedHashMap<Integer, java.util.List<String>> porSis = new java.util.LinkedHashMap<>();
        for (int n : bases) porSis.computeIfAbsent(sistemaDe(n), k -> new java.util.ArrayList<>()).add(numLinea(n));
        java.util.List<String> partes = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.List<String>> e : porSis.entrySet()) {
            java.util.List<String> nums = e.getValue();
            boolean varias = nums.size() > 1;
            if (e.getKey() == sisAct) {
                partes.add(getString(varias ? R.string.voz_lineas_num : R.string.voz_linea_num, unir(nums)));
            } else {
                String sis = getString(e.getKey() == 0 ? R.string.voz_sis_mb
                        : e.getKey() == 1 ? R.string.voz_sis_mxb : R.string.voz_sis_mxc);
                partes.add(getString(varias ? R.string.voz_sis_lineas : R.string.voz_sis_linea, sis, unir(nums)));
            }
        }
        return unir(partes);
    }

    /** Número visible de una base de línea: Metrobús 1..7, Mexibús 10X→X, Mexicable 20X→X. */
    private static String numLinea(int base) {
        return String.valueOf(base >= 200 ? base - 200 : (base >= 100 ? base - 100 : base));
    }

    /** Capitaliza la primera letra (para iniciar frase tras un punto). */
    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Une una lista en "a", "a y b" o "a, b y c". */
    private static String unir(java.util.List<String> ls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return sb.toString();
    }

    /** Lista de líneas de correspondencia (por cercanía) de la estación, en orden; null si ninguna. */
    private String lineasCorrespLista(Planificador.Parada p) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(p);
        lineas.remove(baseLinea(p.linea));
        if (lineas.isEmpty()) return null;
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (int n : lineas) ls.add(etiquetaLinea(n, p.nombre));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return sb.toString();
    }

    /** Primera línea (base) cercana, para decidir la palabra (correspondencia/transbordo/conexión). */
    private int primeraLineaCercana(Planificador.Parada p) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(p);
        lineas.remove(baseLinea(p.linea));
        return lineas.isEmpty() ? p.linea : lineas.first();
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

    private static int sistemaDe(int n) { return n < 100 ? 0 : (n < 200 ? 1 : 2); }   // 0 Metrobús, 1 Mexibús, 2 Mexicable

    // Palabras a ignorar al extraer el NÚCLEO de un nombre de estación (prefijos de sistema, "conexión", etc.).
    private static final java.util.Set<String> STOP_NUCLEO = new java.util.HashSet<>(java.util.Arrays.asList(
            "mxb", "mxc", "mb", "conexion", "mexibus", "meksibus", "mexicable", "meksicable",
            "metrobus", "linea", "y", "e"));

    /** Núcleo del nombre: sin prefijos de sistema, "conexión", ni tokens de línea (l1, l4, l1a). */
    private static String nucleo(String nombre) {
        StringBuilder sb = new StringBuilder();
        for (String w : Planificador.norm(nombre).split(" ")) {
            if (w.isEmpty() || STOP_NUCLEO.contains(w) || w.matches("l[0-9]+a?")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** ¿Coinciden los núcleos de dos nombres (igual o uno contiene al otro)? */
    private static boolean nucleoCoincide(String a, String b) {
        String na = nucleo(a), nb = nucleo(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** Clave de servicio: une ordinario↔exprés de la misma línea (104↔124), pero NO los ramales. */
    private static int claveServicio(int n) { return (n >= 121 && n <= 124) ? (100 + n % 10) : n; }

    /** ¿Dos paradas son la MISMA estación física? Ordinario↔exprés de la misma línea, o mismo nombre
     *  normalizado (correspondencia con el mismo nombre, p. ej. Puente de Fierro L2↔L4). */
    private static boolean mismaEstacion(Planificador.Parada a, Planificador.Parada b) {
        if (a == null || b == null) return false;
        if (claveServicio(a.linea) == claveServicio(b.linea)) return true;
        return Planificador.norm(Planificador.sinMxb(a.nombre))
                .equals(Planificador.norm(Planificador.sinMxb(b.nombre)));
    }

    /**
     * Líneas con las que ESTA estación tiene correspondencia. Recorre TODAS las líneas (Metrobús +
     * Mexibús + Mexicable). Las correspondencias se aferran a estaciones reales: DENTRO del mismo
     * sistema (Metrobús↔Metrobús o Mexibús↔Mexibús) exige MISMO NOMBRE (así Puente de Fierro L2↔L4 sí,
     * pero Casa de Morelos —única de L2— no inventa correspondencia con L4 a 500 m). ENTRE sistemas se
     * enlaza por cercanía (≤ CORRESP_VOZ_M) aunque el nombre difiera (Santa Clara/Periférico ↔ Mexicable).
     */
    private java.util.TreeSet<Integer> lineasEnEstacion(Planificador.Parada p) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        if (p == null || p.pos == null) return s;
        int bp = baseLinea(p.linea);
        int sisP = sistemaDe(p.linea);
        try {
            String pn = Planificador.norm(Planificador.sinMxb(p.nombre));
            java.util.List<Linea> todas = new java.util.ArrayList<>(GtfsRepository.getLineas(this));
            todas.addAll(GtfsRepository.getMexibus(this));
            for (Linea l : todas) {
                if (baseLinea(l.numero) == bp) continue;   // misma línea/servicio: no es transbordo
                boolean mismoSistema = sistemaDe(l.numero) == sisP;
                for (Estacion e : l.estaciones) {
                    if (e.soloMapa) continue;
                    // Mismo sistema: exige MISMO nombre. Entre sistemas (Mexibús↔Mexicable/Metrobús): exige que
                    // el NÚCLEO del nombre coincida (Santa Clara↔Santa Clara, Periférico↔Periférico), NO solo
                    // cercanía — así Cerro Gordo ya no inventa correspondencia con un Mexicable a 500 m.
                    boolean nombreOk = mismoSistema
                            ? Planificador.norm(Planificador.sinMxb(e.nombre)).equals(pn)
                            : nucleoCoincide(p.nombre, e.nombre);
                    if (!nombreOk) continue;
                    double d = haversine(p.pos.latitude, p.pos.longitude,
                            e.posicion.latitude, e.posicion.longitude);
                    if (d <= CORRESP_VOZ_M) { s.add(baseLinea(l.numero)); break; }
                }
            }
        } catch (Exception ignore) {}
        return s;
    }

    /** ¿La parada es terminal de su línea? (incluye terminales por servicio de L4/L7). */
    private boolean esTerminal(Planificador.Parada p) {
        return Planificador.esTerminalDe(p.linea, p.nombre);
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
