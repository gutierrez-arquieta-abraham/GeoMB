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
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.List;

/**
 * Servicio en primer plano que sigue VARIAS unidades a la vez por su número económico: compara la
 * ubicación del usuario con la posición en vivo de cada unidad y avisa cuando entra al radio de
 * {@link Config#SEGUIR_CERCA_M} metros ("está por llegar") o al de {@link Config#SEGUIR_LEJOS_M}
 * ("ya viene"). Cada unidad tiene su propia notificación (con su barra de progreso y su botón de
 * "dejar de seguir"); además hay una notificación-resumen en primer plano. Funciona en segundo plano.
 */
public class SeguimientoService extends Service {

    public static final String EXTRA_ECO = "eco";
    public static final String ACCION_DETENER = "detener";   // con EXTRA_ECO = quita esa unidad; sin él = todas

    private static final String CH_ONGOING = "seguimiento";
    private static final String CH_ALERTA = "alertas";
    private static final int ID_RESUMEN = 4100;              // notificación de primer plano (resumen)
    private static final int BASE_ONGOING = 41000;           // + hash(eco): notificación por unidad
    private static final int BASE_ALERTA = 42000;            // + hash(eco): alerta por unidad

    /** Económicos en seguimiento (los lee el buscador para reflejar el estado del botón). */
    public static final java.util.Set<String> ecosSeguidos =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** ¿Se está siguiendo esta unidad? */
    public static boolean sigue(String eco) { return eco != null && ecosSeguidos.contains(eco); }

    /** Estado de aviso por unidad. */
    private static final class Est {
        boolean avisadoLejos = false;   // "ya viene" (5 km)
        boolean avisadoCerca = false;   // "está por llegar" (800 m)
    }

    private final java.util.Map<String, Est> estados = new java.util.concurrent.ConcurrentHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient locationClient;
    private boolean ciclando = false;
    private boolean primerPlano = false;

    private final Runnable tick = this::ciclo;

    @Override
    public void onCreate() {
        super.onCreate();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        crearCanales();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;
        String eco = intent != null ? intent.getStringExtra(EXTRA_ECO) : null;

        if (ACCION_DETENER.equals(accion)) {
            if (eco != null && !eco.isEmpty()) quitar(eco);   // quita solo esa unidad
            else detenerTodo();                                // sin económico: detiene todo
            return ecosSeguidos.isEmpty() ? START_NOT_STICKY : START_STICKY;
        }

        if (eco == null || eco.isEmpty()) {
            if (ecosSeguidos.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        ecosSeguidos.add(eco);
        estados.put(eco, new Est());
        arrancarPrimerPlano();                 // asegura la notificación de primer plano (resumen)
        actualizarOngoing(eco, getString(R.string.siguiendo_buscando, eco), -1);
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    // ---- ciclo de sondeo (una sola consulta al feed y una sola ubicación para TODAS las unidades) ----

    private void ciclo() {
        if (ciclando) return;
        if (ecosSeguidos.isEmpty()) { detenerTodo(); return; }
        ciclando = true;
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> unidades) { procesarTodas(); }
            @Override public void onError(String mensaje) {
                for (String e : ecosSeguidos) actualizarOngoing(e, getString(R.string.siguiendo_sin_conexion, e), -1);
                reprogramar();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void procesarTodas() {
        if (!tienePermisoUbicacion()) {
            for (String e : ecosSeguidos) actualizarOngoing(e, getString(R.string.siguiendo_sin_permiso), -1);
            reprogramar();
            return;
        }
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        for (String e : ecosSeguidos) {
                            UnidadReal u = RealtimeRepository.get().buscar(e);
                            if (u == null) actualizarOngoing(e, getString(R.string.siguiendo_fuera, e), -1);
                            else manejarDistancia(e, u, loc);
                        }
                        actualizarResumen();
                    }
                    reprogramar();
                })
                .addOnFailureListener(e -> reprogramar());
    }

    private void manejarDistancia(String eco, UnidadReal u, Location loc) {
        Est st = estados.get(eco);
        if (st == null) return;   // se dejó de seguir mientras se consultaba
        float[] res = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                u.posicion.latitude, u.posicion.longitude, res);
        int metros = Math.round(res[0]);

        float tope = Config.SEGUIR_LEJOS_M;
        int progreso = (int) Math.round(100.0 * (tope - metros) / tope);
        if (progreso < 0) progreso = 0;
        if (progreso > 100) progreso = 100;
        actualizarOngoing(eco, getString(R.string.siguiendo_distancia, eco, distTxt(metros), progreso), progreso);

        if (metros <= Config.SEGUIR_CERCA_M) {
            if (!st.avisadoCerca) {
                st.avisadoCerca = true; st.avisadoLejos = true;
                lanzarAlerta(eco, getString(R.string.cerca_titulo), getString(R.string.cerca_texto, eco, distTxt(metros)));
            }
        } else if (metros <= Config.SEGUIR_LEJOS_M) {
            if (!st.avisadoLejos) {
                st.avisadoLejos = true;
                lanzarAlerta(eco, getString(R.string.viene_titulo), getString(R.string.viene_texto, eco, distTxt(metros)));
            }
        }
        if (metros > Config.SEGUIR_REARME_CERCA_M) st.avisadoCerca = false;
        if (metros > Config.SEGUIR_REARME_LEJOS_M) st.avisadoLejos = false;
    }

    private void reprogramar() {
        ciclando = false;
        if (ecosSeguidos.isEmpty()) { detenerTodo(); return; }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Red.intervalo(this, Config.SEGUIR_POLL_MS));
    }

    // ---- notificaciones ----

    private static int idOngoing(String eco) { return BASE_ONGOING + Math.abs(eco.hashCode() % 10000); }
    private static int idAlerta(String eco) { return BASE_ALERTA + Math.abs(eco.hashCode() % 10000); }

    private void crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ong = new NotificationChannel(CH_ONGOING,
                    getString(R.string.canal_seguimiento), NotificationManager.IMPORTANCE_LOW);
            NotificationChannel alt = new NotificationChannel(CH_ALERTA,
                    getString(R.string.canal_alertas), NotificationManager.IMPORTANCE_HIGH);
            alt.enableVibration(true);
            nm.createNotificationChannel(ong);
            nm.createNotificationChannel(alt);
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** "Dejar de seguir" de UNA unidad (código de solicitud único por económico). */
    private PendingIntent piDetener(String eco) {
        Intent i = new Intent(this, SeguimientoService.class).setAction(ACCION_DETENER).putExtra(EXTRA_ECO, eco);
        return PendingIntent.getService(this, idOngoing(eco), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent piDetenerTodo() {
        Intent i = new Intent(this, SeguimientoService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, ID_RESUMEN, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construirOngoing(String eco, String texto, int progreso) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.siguiendo_titulo, eco))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(0, getString(R.string.dejar_de_seguir), piDetener(eco))
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (progreso >= 0) b.setProgress(100, progreso, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private Notification construirResumen() {
        return new NotificationCompat.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.siguiendo_varias_titulo))
                .setContentText(getString(R.string.siguiendo_resumen, ecosSeguidos.size()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(0, getString(R.string.dejar_todo), piDetenerTodo())
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void arrancarPrimerPlano() {
        if (primerPlano) { actualizarResumen(); return; }
        primerPlano = true;
        Notification n = construirResumen();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_RESUMEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_RESUMEN, n);
        }
    }

    private void actualizarResumen() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && primerPlano) nm.notify(ID_RESUMEN, construirResumen());
    }

    private void actualizarOngoing(String eco, String texto, int progreso) {
        if (!ecosSeguidos.contains(eco)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(idOngoing(eco), construirOngoing(eco, texto, progreso));
    }

    private void lanzarAlerta(String eco, String titulo, String texto) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification n = new NotificationCompat.Builder(this, CH_ALERTA)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(piAbrir())
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(idAlerta(eco), n);
    }

    /** Distancia legible: "820 m" o "3.4 km". */
    private String distTxt(int metros) {
        if (metros >= 1000) return String.format(java.util.Locale.getDefault(), "%.1f km", metros / 1000f);
        return metros + " m";
    }

    private boolean tienePermisoUbicacion() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Quita UNA unidad del seguimiento; si no queda ninguna, detiene el servicio. */
    private void quitar(String eco) {
        ecosSeguidos.remove(eco);
        estados.remove(eco);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) { nm.cancel(idOngoing(eco)); nm.cancel(idAlerta(eco)); }
        if (ecosSeguidos.isEmpty()) detenerTodo();
        else actualizarResumen();
    }

    private void detenerTodo() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) for (String e : ecosSeguidos) { nm.cancel(idOngoing(e)); nm.cancel(idAlerta(e)); }
        ecosSeguidos.clear();
        estados.clear();
        handler.removeCallbacks(tick);
        stopForeground(true);
        primerPlano = false;
        stopSelf();
    }

    /** Android 14+: límite de tiempo del FGS location. Detener limpio para no crashear. */
    @Override
    public void onTimeout(int startId) {
        handler.removeCallbacksAndMessages(null);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignore) {}
        detenerTodo();
    }

    @Override
    public void onDestroy() {
        ecosSeguidos.clear();
        estados.clear();
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
