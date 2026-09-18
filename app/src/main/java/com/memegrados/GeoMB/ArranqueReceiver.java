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
// ============================================================
// CLASE    : ArranqueReceiver   (extends BroadcastReceiver)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Un RECEPTOR de eventos del sistema. Cuando el teléfono ARRANCA (o se
// reemplaza la app), reinicia el monitoreo de afectaciones para que los
// avisos sigan llegando aunque el sistema haya liberado la app.
//
// ¿QUÉ ES UN BroadcastReceiver? Un componente que "escucha" avisos del
// sistema (aquí BOOT_COMPLETED, MY_PACKAGE_REPLACED, QUICKBOOT). Al recibirlos
// arranca ManifestacionesService (siempre) y la sincronización en 2º plano
// solo si el usuario la dejó activa.
// ============================================================
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
