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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Servicio en primer plano EXCLUSIVO de 🔔 Alertas de proximidad: mientras exista al menos una
 * unidad guardada con {@code alertaActiva}, manda periódicamente la ubicación actual del
 * dispositivo a {@code POST /device/ubicacion} (ver {@link AlertasBackend#actualizarUbicacion}),
 * para que el detector de proximidad del backend (etapa posterior, en {@code push_metrobus.py})
 * tenga algo contra qué comparar cada unidad con alerta activa.
 *
 * Completamente independiente de {@link SeguimientoService} -- NUNCA lo inicia, detiene, ni lee
 * su estado, y viceversa: este servicio NO sigue ninguna unidad, NO mueve el mapa, NO calcula
 * distancias a vehículos, NO consulta el feed de unidades, NO genera notificaciones de
 * proximidad ni manda FCM. Su única responsabilidad es "¿dónde está el usuario ahora?" -- el
 * resto del pipeline de alertas vive en el backend.
 *
 * Arranca/se detiene exclusivamente por acción explícita mientras la app está visible (ver
 * {@link Telemetria#actualizarAlertaUnidad}/{@link Telemetria#quitarFavorito}, que deciden si
 * queda alguna alerta activa tras cada cambio) -- NUNCA se resume solo porque ya había alertas
 * guardadas al reiniciar el teléfono o abrir la app (no hay ningún gancho en ArranqueReceiver ni
 * en GeoMBApplication para esto, a propósito, igual criterio que ya se decidió para
 * SeguimientoService). Si el proceso muere o el teléfono se reinicia mientras había alertas
 * activas, el servicio se queda detenido hasta que el usuario vuelva a tocar algo en la UI de
 * alertas -- limitación conocida y aceptada, no un descuido.
 *
 * Ubicación: {@link FusedLocationProviderClient#getCurrentLocation} (una sola lectura por ciclo,
 * NO {@code requestLocationUpdates} continuo) cada {@link Config#ALERTA_UBICACION_POLL_MS} --
 * mismo mecanismo y la misma técnica de sondeo periódico con {@code Handler} que ya usa
 * {@link SeguimientoService}, sin requerir {@code ACCESS_BACKGROUND_LOCATION}: el permiso "en
 * uso" (ACCESS_FINE_LOCATION) ya concedido basta porque este es un Foreground Service con tipo
 * {@code location} declarado, el mecanismo que Android espera para este caso exacto.
 */
public class AlertasUnidadesService extends Service {

    private static final String CANAL = "alertas_ubicacion";
    private static final int ID_NOTIF = 4600;

    /** Arranca el servicio si no corría ya. No hace nada (ni crashea) si falta
     *  ACCESS_FINE_LOCATION -- la preferencia de alerta ya quedó guardada en Room/backend de
     *  todos modos (ver Telemetria); esto solo intenta que además tenga ubicación que mandar. */
    public static void iniciar(android.content.Context c) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent i = new Intent(c, AlertasUnidadesService.class);
        try { ContextCompat.startForegroundService(c, i); } catch (Exception ignore) {}
    }

    /** Detiene el servicio -- no-op seguro si ya no estaba corriendo. */
    public static void detener(android.content.Context c) {
        try { c.stopService(new Intent(c, AlertasUnidadesService.class)); } catch (Exception ignore) {}
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient locationClient;
    private boolean primerPlano = false;

    private final Runnable tick = this::ciclo;

    @Override
    public void onCreate() {
        super.onCreate();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        crearCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        arrancarPrimerPlano();
        handler.removeCallbacks(tick);
        handler.post(tick);
        // A propósito NO START_STICKY: si el sistema mata el proceso, este servicio NUNCA debe
        // resucitar solo -- ver el javadoc de la clase ("no iniciar silenciosamente").
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void ciclo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permiso revocado mientras corría: no tiene sentido seguir -- no hay nada que mandar
            // y reintentar en bucle sin permiso no cumpliría ningún propósito.
            detenerInterno();
            return;
        }
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null) AlertasBackend.actualizarUbicacion(this, loc.getLatitude(), loc.getLongitude());
                        reprogramar();
                    })
                    .addOnFailureListener(e -> reprogramar());
        } catch (SecurityException e) {
            // Carrera rarísima entre el checkSelfPermission de arriba y esta llamada (el usuario
            // revoca el permiso justo entre ambas) -- se trata igual que "sin permiso": se
            // detiene limpio en vez de dejar que la excepción tumbe el servicio.
            detenerInterno();
        }
    }

    private void reprogramar() {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Red.intervalo(this, Config.ALERTA_UBICACION_POLL_MS));
    }

    // ---- notificación (siempre deja claro que es de ALERTAS, nunca de seguimiento) ----

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CANAL,
                    getString(R.string.canal_alertas_ubicacion), NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construirNotificacion() {
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.alertas_ubicacion_notif_titulo))
                .setContentText(getString(R.string.alertas_ubicacion_notif_texto))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void arrancarPrimerPlano() {
        if (primerPlano) return;
        primerPlano = true;
        Notification n = construirNotificacion();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(ID_NOTIF, n);
        }
    }

    private void detenerInterno() {
        handler.removeCallbacks(tick);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignore) {}
        primerPlano = false;
        stopSelf();
    }

    /** Android 14+: límite de tiempo del FGS de tipo location -- mismo criterio que
     *  SeguimientoService.onTimeout(): detener limpio en vez de dejar que el sistema lo mate. */
    @Override
    public void onTimeout(int startId) {
        detenerInterno();
    }

    /** Android 15+ (API 35): misma firma nueva de dos argumentos. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        onTimeout(startId);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
