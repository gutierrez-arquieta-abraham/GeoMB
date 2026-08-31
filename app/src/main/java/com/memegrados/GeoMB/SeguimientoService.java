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
 * Servicio en primer plano que sigue una unidad por su número económico:
 * compara la ubicación del usuario con la posición en vivo de la unidad y,
 * cuando entra al radio de {@link Config#SEGUIR_CERCA_M} metros, lanza una
 * notificación "ya está cerca de ti". Funciona con la app en segundo plano.
 */
public class SeguimientoService extends Service {

    public static final String EXTRA_ECO = "eco";
    public static final String ACCION_DETENER = "detener";

    private static final String CH_ONGOING = "seguimiento";
    private static final String CH_ALERTA = "alertas";
    private static final int ID_ONGOING = 4101;
    private static final int ID_ALERTA = 4102;

    /** Económico que se está siguiendo (null si nada). Lo lee el buscador. */
    public static volatile String ecoSeguido = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient locationClient;
    private String eco;
    private boolean avisadoLejos = false;   // "ya viene" (5 km)
    private boolean avisadoCerca = false;   // "está por llegar" (800 m)
    private boolean ciclando = false;
    private int distanciaInicial = -1;   // baseline para la barra de progreso (m)

    private final Runnable tick = this::ciclo;

    @Override
    public void onCreate() {
        super.onCreate();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        crearCanales();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            detener();
            return START_NOT_STICKY;
        }
        eco = intent != null ? intent.getStringExtra(EXTRA_ECO) : null;
        if (eco == null || eco.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ecoSeguido = eco;
        avisadoLejos = false;
        avisadoCerca = false;
        distanciaInicial = -1;
        arrancarPrimerPlano(getString(R.string.siguiendo_buscando, eco));
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void ciclo() {
        if (ciclando) return;
        ciclando = true;
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override
            public void onData(List<UnidadReal> unidades) {
                UnidadReal u = RealtimeRepository.get().buscar(eco);
                if (u == null) {
                    actualizarOngoing(getString(R.string.siguiendo_fuera, eco), -1);
                    reprogramar();
                    return;
                }
                compararConUbicacion(u);
            }

            @Override
            public void onError(String mensaje) {
                actualizarOngoing(getString(R.string.siguiendo_sin_conexion, eco), -1);
                reprogramar();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void compararConUbicacion(UnidadReal u) {
        if (!tienePermisoUbicacion()) {
            actualizarOngoing(getString(R.string.siguiendo_sin_permiso), -1);
            reprogramar();
            return;
        }
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc != null) manejarDistancia(u, loc);
                    reprogramar();
                })
                .addOnFailureListener(e -> reprogramar());
    }

    private void manejarDistancia(UnidadReal u, Location loc) {
        float[] res = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                u.posicion.latitude, u.posicion.longitude, res);
        int metros = Math.round(res[0]);

        // Barra de progreso de llegada sobre un tope fijo (5 km): 0% a 5 km o más, 100% al llegar.
        float tope = Config.SEGUIR_LEJOS_M;
        int progreso = (int) Math.round(100.0 * (tope - metros) / tope);
        if (progreso < 0) progreso = 0;
        if (progreso > 100) progreso = 100;

        actualizarOngoing(getString(R.string.siguiendo_distancia, eco, distTxt(metros), progreso), progreso);

        // Dos niveles de aviso: "ya viene" (5 km) y "está por llegar" (800 m).
        if (metros <= Config.SEGUIR_CERCA_M) {
            if (!avisadoCerca) {
                avisadoCerca = true;
                avisadoLejos = true;   // ya rebasó el de lejos
                lanzarAlerta(getString(R.string.cerca_titulo),
                        getString(R.string.cerca_texto, eco, distTxt(metros)));
            }
        } else if (metros <= Config.SEGUIR_LEJOS_M) {
            if (!avisadoLejos) {
                avisadoLejos = true;
                lanzarAlerta(getString(R.string.viene_titulo),
                        getString(R.string.viene_texto, eco, distTxt(metros)));
            }
        }
        // Re-arma cada aviso cuando la unidad se aleja lo suficiente.
        if (metros > Config.SEGUIR_REARME_CERCA_M) avisadoCerca = false;
        if (metros > Config.SEGUIR_REARME_LEJOS_M) avisadoLejos = false;
    }

    private void reprogramar() {
        ciclando = false;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Red.intervalo(this, Config.SEGUIR_POLL_MS));
    }

    // ---- notificaciones ----

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

    private PendingIntent piDetener() {
        Intent i = new Intent(this, SeguimientoService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construirOngoing(String texto, int progreso) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.siguiendo_titulo, eco))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(0, getString(R.string.dejar_de_seguir), piDetener())
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (progreso >= 0) {
            b.setProgress(100, progreso, false);          // barra de llegada
        } else {
            b.setProgress(0, 0, true);                    // indeterminada (localizando)
        }
        return b.build();
    }

    private void arrancarPrimerPlano(String texto) {
        Notification n = construirOngoing(texto, -1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    private void actualizarOngoing(String texto, int progreso) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ONGOING, construirOngoing(texto, progreso));
    }

    private void lanzarAlerta(String titulo, String texto) {
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
        if (nm != null) nm.notify(ID_ALERTA, n);
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

    private void detener() {
        ecoSeguido = null;
        handler.removeCallbacks(tick);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        ecoSeguido = null;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
