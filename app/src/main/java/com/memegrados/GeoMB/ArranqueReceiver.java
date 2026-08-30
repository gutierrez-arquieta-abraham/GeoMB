package com.memegrados.GeoMB;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reinicia el monitoreo de afectaciones al servicio cuando el teléfono arranca,
 * para que los avisos sigan llegando aunque el sistema haya liberado la app o se
 * haya reiniciado el dispositivo. El servicio corre en primer plano y es START_STICKY,
 * así que el sistema también lo recrea si lo mata mientras el proceso vive.
 */
public class ArranqueReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a)) {
            // Vigila afectaciones del servicio (siempre).
            try { ManifestacionesService.iniciar(context); } catch (Exception ignore) {}
            // Sincronización en segundo plano solo si el usuario la dejó activa.
            try {
                if (Modos.sincronizacionFondo(context)) SincronizacionService.iniciar(context);
            } catch (Exception ignore) {}
        }
    }
}
