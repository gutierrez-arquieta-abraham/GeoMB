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
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

// ============================================================
// CLASE    : DescargaVozService
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Ejecuta la descarga de audios (offline) como SERVICIO EN PRIMER PLANO
// con notificación de progreso, en vez de una tarea suelta en 2º plano.
//
// ¿POR QUÉ? "Descargar todas las líneas" son cientos de peticiones
// secuenciales y tarda minutos. Sin un servicio en primer plano, si el
// usuario manda la app a 2º plano, el sistema puede MATAR el proceso (se
// sentía como cierre forzado a mitad). Con este servicio la descarga sigue
// viva y su avance se ve en la notificación aunque la app esté cerrada.
// ============================================================
/**
 * Descarga los audios (offline) en un servicio en PRIMER PLANO con notificación de progreso, en
 * vez de una simple tarea en segundo plano atada al fragmento de Acerca de. "Descargar todas las
 * líneas" implica cientos de peticiones secuenciales (una por una, ya que {@link DescargaVoz} ya
 * las hace de a una para no saturar el ancho de banda) y puede tardar varios minutos: si en ese
 * tiempo el usuario manda la app a segundo plano, sin un servicio en primer plano el sistema puede
 * matar el proceso por límites de ejecución en segundo plano, lo que se sentía como un "cierre
 * forzado" a mitad de la descarga. Con este servicio la descarga sigue viva y su avance se ve en
 * la notificación aunque la app esté cerrada; la "carta" (diálogo) del fragmento solo la refleja
 * mientras está visible y se puede cerrar (o desaparece al ir a segundo plano) sin cancelarla.
 */
public class DescargaVozService extends Service {

    /** Para que la UI (el diálogo de Acerca de) reciba el avance en vivo mientras está visible. */
    public interface Escucha {
        void avance(int hechos, int total);
        void fin(int descargados, int total);
    }

    private static final String EXTRA_LINEAS = "lineas";
    private static final String EXTRA_NOMBRE = "nombre";
    private static final String ACCION_CANCELAR = "cancelar_descarga_audios";
    private static final String CANAL = "descarga_audios";
    private static final int ID_ONGOING = 4301;
    private static final long NOTIF_MIN_MS = 300L;   // no saturar al NotificationManager

    public static volatile boolean corriendo = false;
    public static volatile int hechos = 0, total = 0;
    private static volatile Escucha escucha;

    private String nombre;
    private long ultimaNotif = 0;

    public static void setEscucha(Escucha e) { escucha = e; }

    /** Arranca la descarga (si no hay ya una en curso: ignora el duplicado). */
    public static void iniciar(Context c, List<Integer> lineas, String nombre) {
        int[] arr = new int[lineas.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = lineas.get(i);
        Intent i = new Intent(c, DescargaVozService.class)
                .putExtra(EXTRA_LINEAS, arr)
                .putExtra(EXTRA_NOMBRE, nombre);
        ContextCompat.startForegroundService(c, i);
    }

    /** Cancela la descarga en curso (botón "Cancelar" del diálogo o de la notificación). */
    public static void cancelar(Context c) {
        DescargaVoz.cancelar();
        c.startService(new Intent(c, DescargaVozService.class).setAction(ACCION_CANCELAR));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_CANCELAR.equals(intent.getAction())) {
            DescargaVoz.cancelar();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (corriendo) return START_NOT_STICKY;   // ya hay una descarga en curso, ignora el duplicado
        int[] lineas = intent != null ? intent.getIntArrayExtra(EXTRA_LINEAS) : null;
        if (lineas == null || lineas.length == 0) { stopSelf(); return START_NOT_STICKY; }
        nombre = intent.getStringExtra(EXTRA_NOMBRE);
        if (nombre == null) nombre = getString(R.string.audios_titulo);
        corriendo = true;
        hechos = 0; total = 0;
        arrancarPrimerPlano();

        List<Integer> ls = new ArrayList<>();
        for (int ln : lineas) ls.add(ln);
        DescargaVoz.descargarVarias(this, ls, new DescargaVoz.Progreso() {
            @Override public void avance(int h, int t) {
                hechos = h; total = t;
                actualizar(h, t);
                Escucha e = escucha;
                if (e != null) e.avance(h, t);
            }
            @Override public void fin(int ok, int t) {
                corriendo = false;
                terminar(ok, t);
                Escucha e = escucha;
                if (e != null) e.fin(ok, t);
            }
            @Override public void error(String msg) {}
        });
        return START_NOT_STICKY;
    }

    // ---- notificación en primer plano ----

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CANAL,
                    getString(R.string.canal_descarga_audios), NotificationManager.IMPORTANCE_LOW);
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

    private PendingIntent piCancelar() {
        Intent i = new Intent(this, DescargaVozService.class).setAction(ACCION_CANCELAR);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construir(int h, int t) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(nombre)
                .setContentText(getString(R.string.audios_descargando, h, t))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(0, getString(R.string.audios_cancelar), piCancelar())
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (t > 0) b.setProgress(t, h, false); else b.setProgress(0, 0, true);   // indeterminada hasta saber el total
        return b.build();
    }

    private void arrancarPrimerPlano() {
        Notification n = construir(0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_ONGOING, n);
        }
    }

    private void actualizar(int h, int t) {
        long ahora = System.currentTimeMillis();
        if (h < t && ahora - ultimaNotif < NOTIF_MIN_MS) return;
        ultimaNotif = ahora;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ONGOING, construir(h, t));
    }

    private void terminar(int ok, int t) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Notification fin = new NotificationCompat.Builder(this, CANAL)
                    .setSmallIcon(R.drawable.ic_bus)
                    .setContentTitle(nombre)
                    .setContentText(getString(R.string.audios_listo, ok))
                    .setAutoCancel(true)
                    .setContentIntent(piAbrir())
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            nm.notify(ID_ONGOING, fin);
        }
        stopForeground(STOP_FOREGROUND_DETACH);   // deja visible el aviso final, ya sin ser "en curso"
        stopSelf();
    }

    /** Android 14+: límite de tiempo del FGS dataSync. Detener limpio para no crashear. */
    @Override
    public void onTimeout(int startId) {
        corriendo = false;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignore) {}
        stopSelf();
    }

    /** Android 15+ (API 35): mismo timeout que arriba, con la firma nueva de dos argumentos. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        onTimeout(startId);
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
