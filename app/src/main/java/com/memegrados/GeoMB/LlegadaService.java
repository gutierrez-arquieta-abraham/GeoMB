package com.memegrados.GeoMB;

import android.Manifest;
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

import com.google.android.gms.maps.model.LatLng;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servicio en primer plano que vigila una parada + dirección y avisa cuando una
 * unidad se acerca (dentro de {@link Config#LLEGADA_AVISO_M}). No usa la
 * ubicación del usuario: compara la posición de las unidades contra la estación
 * elegida. Funciona con la app en segundo plano.
 */
public class LlegadaService extends Service {

    public static final String EXTRA_LINEA = "linea";
    public static final String EXTRA_ESTACION = "estacion";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";
    public static final String EXTRA_SENTIDO = "sentido";
    public static final String ACCION_DETENER = "detener";

    private static final String CH_ONGOING = "llegadas_ongoing";
    private static final String CH_ALERTA = "llegadas_alerta";
    private static final int ID_ONGOING = 5101;
    private static final int ID_ALERTA = 5102;

    /** Estación que se está vigilando (null si nada). La lee el fragmento. */
    public static volatile String paradaSeguida = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int linea;
    private String estacion;
    private String sentido;
    private LatLng pos;
    private boolean ciclando = false;
    private volatile boolean detenido = false;
    private final Set<String> avisados = new HashSet<>();

    private final Runnable tick = this::ciclo;

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanales();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            detener();
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        linea = intent.getIntExtra(EXTRA_LINEA, 1);
        estacion = intent.getStringExtra(EXTRA_ESTACION);
        sentido = intent.getStringExtra(EXTRA_SENTIDO);
        pos = new LatLng(intent.getDoubleExtra(EXTRA_LAT, 0), intent.getDoubleExtra(EXTRA_LON, 0));
        if (estacion == null) { stopSelf(); return START_NOT_STICKY; }

        paradaSeguida = estacion;
        avisados.clear();
        detenido = false;
        arrancarPrimerPlano(getString(R.string.llegada_ongoing_formato, estacion));
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void ciclo() {
        if (detenido || ciclando) return;
        ciclando = true;
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> unidades) { evaluar(unidades); reprogramar(); }
            @Override public void onError(String mensaje) { reprogramar(); }
        });
    }

    private void evaluar(List<UnidadReal> unidades) {
        List<Llegadas.Prox> prox = Llegadas.proximas(this, linea, pos, sentido, unidades);

        Set<String> cercanos = new HashSet<>();
        for (Llegadas.Prox p : prox) {
            if (p.metros <= Config.LLEGADA_AVISO_M) {
                cercanos.add(p.eco);
                if (!avisados.contains(p.eco)) {
                    avisados.add(p.eco);
                    int min = Math.max(1, Math.round(p.etaSeg / 60f));
                    lanzarAlerta(getString(R.string.llegada_notif_titulo),
                            getString(R.string.llegada_notif_texto, p.eco, estacion, min));
                }
            }
        }
        // Re-arma: olvida las unidades que ya se alejaron o pasaron.
        avisados.retainAll(cercanos);
    }

    private void reprogramar() {
        ciclando = false;
        if (detenido) return;   // ya se detuvo: no re-armar (evita que un fetch en vuelo lo reviva)
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Red.intervalo(this, Config.LLEGADA_POLL_MS));
    }

    // ---- notificaciones ----

    private void crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ong = new NotificationChannel(CH_ONGOING,
                    getString(R.string.canal_llegadas), NotificationManager.IMPORTANCE_LOW);
            NotificationChannel alt = new NotificationChannel(CH_ALERTA,
                    getString(R.string.canal_llegadas), NotificationManager.IMPORTANCE_HIGH);
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
        Intent i = new Intent(this, LlegadaService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void arrancarPrimerPlano(String texto) {
        Notification n = new NotificationCompat.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(getString(R.string.llegadas_titulo))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(R.drawable.ic_bell, getString(R.string.llegada_dejar), piDetener())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    private void lanzarAlerta(String titulo, String texto) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification n = new NotificationCompat.Builder(this, CH_ALERTA)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(piAbrir())
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ALERTA + Math.abs(titulo.hashCode() % 1000), n);
    }

    private void detener() {
        detenido = true;
        paradaSeguida = null;
        handler.removeCallbacks(tick);
        stopForeground(true);
        stopSelf();
    }

    /** Android 14+: límite de tiempo del FGS dataSync. Detener limpio para no crashear. */
    @Override
    public void onTimeout(int startId) {
        handler.removeCallbacksAndMessages(null);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignore) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        paradaSeguida = null;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
