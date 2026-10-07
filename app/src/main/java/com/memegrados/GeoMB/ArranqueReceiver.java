package com.memegrados.GeoMB;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Reinicia el monitoreo que haga falta cuando el teléfono arranca (o se reemplaza la app).
 *
 * ManifestacionesService y SincronizacionService YA NO se arrancan aquí: Android 15+ no permite
 * lanzar un foreground service de tipo RESTRINGIDO (dataSync incluido) desde un receptor de
 * BOOT_COMPLETED/MY_PACKAGE_REPLACED -- lo bloquea con ForegroundServiceStartNotAllowedException,
 * justo el warning de Google Play que originó este cambio. No hace falta reemplazarlo por nada:
 * MainActivity.onCreate() ya arranca ambos en cada apertura de la app (sin esa restricción, porque
 * ahí el exemption es "transición desde un estado visible", no BOOT_COMPLETED), que es el único
 * momento en que su trabajo (respaldo local de afectaciones / precalentar el caché del mapa) tiene
 * algún valor real -- sin la app abierta no hay nada que mostrar de todos modos.
 *
 * LlegadaService SÍ necesita poder reanudarse automáticamente tras un reinicio (si el usuario tenía
 * una alerta de llegada activa, debe seguir sonando sin que reabra la app) y no tiene ningún otro
 * punto de reanudación -- por eso se conserva, pero diferido vía una alarma exacta en vez de un
 * arranque directo: ver el javadoc de {@link #reanudarLlegadaDiferido}.
 */
public class ArranqueReceiver extends BroadcastReceiver {

    private static final String TAG = "ArranqueReceiver";

    // Margen antes de que dispare la alarma: no es para "esperar" nada (el disparo es casi
    // inmediato), es para que el arranque real de LlegadaService ocurra DESPUÉS de que este
    // receptor ya devolvió el control, bajo el exemption de "alarma exacta" en vez del de
    // BOOT_COMPLETED (ver javadoc de reanudarLlegadaDiferido).
    private static final long RETRASO_ALARMA_MS = 2000L;
    // Respaldo: si por lo que sea la alarma nunca llega a disparar, suelta igual el PendingResult
    // de goAsync() en vez de dejarlo colgado indefinidamente (mismo criterio que los respaldos por
    // timeout ya usados en RecorridoService/DescargaVozService).
    private static final long TIMEOUT_PENDING_RESULT_MS = 10000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onReceive: acción recibida = " + a);
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) {
            reanudarLlegadaDiferido(context.getApplicationContext());
        }
    }

    /**
     * Reanuda la alerta de llegada persistida (si había una) SIN llamar a
     * {@code startForegroundService} directamente desde este receptor.
     *
     * POR QUÉ: en apps que targetean Android 15+, Android prohíbe lanzar un foreground service de
     * tipo RESTRINGIDO (dataSync incluido) cuando el único "exemption" de arranque en segundo plano
     * disponible es haber recibido BOOT_COMPLETED/MY_PACKAGE_REPLACED (uno de los 14 exemptions
     * documentados en "Exemptions from background start restrictions"; Android 15 le añadió esa
     * excepción puntual). La alarma EXACTA es un exemption DISTINTO e independiente de ese mismo
     * listado, y la documentación de Android dice explícitamente que las alarmas exactas "aren't
     * affected by foreground service launch restrictions". Por eso se programa una alarma exacta de
     * disparo casi inmediato: no para "esperar", sino para que el arranque real de LlegadaService
     * quede bajo ESE exemption en vez del de BOOT_COMPLETED.
     *
     * Se usa la variante de {@link AlarmManager#setExact} con {@link AlarmManager.OnAlarmListener}
     * (no con PendingIntent) a propósito: esa es la única que NO exige el permiso
     * SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM. GeoMB no califica para USE_EXACT_ALARM (no es una app
     * de alarmas/calendario) y pedirle al usuario el permiso especial de SCHEDULE_EXACT_ALARM solo
     * para esto sería desproporcionado -- máxime cuando la precisión de segundos no importa aquí.
     *
     * Contrapartida de OnAlarmListener: el callback se entrega a un Handler DENTRO de este proceso,
     * así que si el proceso muriera entre programar la alarma y que dispare, no llegaría a
     * entregarse. {@code goAsync()} le pide al sistema que no mate el proceso mientras este
     * receptor sigue "activo", lo que cubre sobradamente el margen de {@link #RETRASO_ALARMA_MS}
     * (el dispositivo recién arrancó, sin presión de memoria todavía).
     */
    private void reanudarLlegadaDiferido(Context appCtx) {
        if (!LlegadaService.hayAlertaPersistida(appCtx)) {
            Log.d(TAG, "reanudarLlegadaDiferido: no había alerta de llegada persistida; nada que reanudar.");
            return;
        }
        Log.d(TAG, "reanudarLlegadaDiferido: alerta persistida encontrada; programando reanudación diferida.");

        PendingResult pr = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] resuelto = {false};
        Runnable terminar = () -> {
            if (resuelto[0]) return;
            resuelto[0] = true;
            try { pr.finish(); } catch (Exception ignore) {}
        };
        h.postDelayed(terminar, TIMEOUT_PENDING_RESULT_MS);

        try {
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) { terminar.run(); return; }
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RETRASO_ALARMA_MS,
                    "GeoMB:ReanudarLlegada",
                    () -> {
                        Log.d(TAG, "reanudarLlegadaDiferido: alarma disparada, reanudando LlegadaService.");
                        LlegadaService.reanudarSiHay(appCtx);
                        terminar.run();
                    }, h);
            Log.d(TAG, "reanudarLlegadaDiferido: alarma exacta programada (+" + RETRASO_ALARMA_MS + " ms).");
        } catch (Exception e) {
            Log.w(TAG, "reanudarLlegadaDiferido: no se pudo programar la alarma", e);
            terminar.run();
        }
    }
}
