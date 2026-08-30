package com.memegrados.GeoMB;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Servicio en primer plano que mantiene la sincronización con el servidor de
 * unidades en segundo plano: pide el feed periódicamente para conservar los
 * datos frescos aunque la app no esté visible. El usuario lo activa/desactiva
 * desde "Acerca de" (switch siempre visible).
 */
public class SincronizacionService extends Service {

    public static final String ACCION_DETENER = "detener_sincro";
    private static final String CANAL = "sincronizacion";
    private static final int ID_ONGOING = 4201;

    /** Estado en memoria para que la UI sepa si está corriendo. */
    public static volatile boolean activo = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ciclando = false;
    private final Runnable tick = this::ciclo;

    /** Arranca el servicio (si el usuario lo habilitó). */
    public static void iniciar(Context c) {
        Intent i = new Intent(c, SincronizacionService.class);
        ContextCompat.startForegroundService(c, i);
    }

    /** Detiene el servicio. */
    public static void detener(Context c) {
        c.stopService(new Intent(c, SincronizacionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            Modos.setSincronizacionFondo(this, false);   // el usuario la apagó desde la notificación
            stopSelf();
            return START_NOT_STICKY;
        }
        activo = true;
        arrancarPrimerPlano();
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
                // El servidor (EC2/SONDA) respondió bien. Si el usuario NO activó la sincronización
                // manual, este servicio solo era un RESPALDO temporal: se apaga para no gastar batería.
                if (!Modos.sincronizacionFondo(SincronizacionService.this)) { stopSelf(); return; }
                String txt = getString(R.string.sincro_texto, unidades.size());
                if (Manifestaciones.hay()) txt += " · ⚠ " + getString(R.string.sincro_afectacion);
                actualizar(txt);
                reprogramar();
            }

            @Override
            public void onError(String mensaje) {
                // El servidor falló: se mantiene el sondeo de respaldo hasta que vuelva (o el usuario lo apague).
                actualizar(getString(R.string.sincro_sin_conexion));
                reprogramar();
            }
        });
    }

    /**
     * En SEGUNDO PLANO el sondeo es mucho más espaciado que en la app visible: mantener 10 s en
     * background consumía demasiada batería. Se refresca cada {@link #BACKGROUND_POLL_MS}; el mapa
     * abierto sigue usando el intervalo corto de {@link Config#POLL_MS}.
     */
    private static final long BACKGROUND_POLL_MS = 60000L;

    private void reprogramar() {
        ciclando = false;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Math.max(BACKGROUND_POLL_MS, Red.intervalo(this, Config.POLL_MS)));
    }

    // ---- notificación en primer plano ----

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CANAL,
                    getString(R.string.canal_sincronizacion), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent piDetener() {
        Intent i = new Intent(this, SincronizacionService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construir(String texto) {
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(getString(R.string.sincro_titulo))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(0, getString(R.string.sincro_detener), piDetener())
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void arrancarPrimerPlano() {
        Notification n = construir(getString(R.string.sincro_texto_inicial));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    private void actualizar(String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ONGOING, construir(texto));
    }

    @Override
    public void onDestroy() {
        activo = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
