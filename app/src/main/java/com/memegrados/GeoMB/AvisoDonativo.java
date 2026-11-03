package com.memegrados.GeoMB;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

/**
 * Ventana de donativo (PayPal.me, ver {@link Config#DONACION_URL}), al frente de MainActivity --
 * no bloquea el uso de la app, se puede posponer o desactivar. Con cooldown de
 * {@link #COOLDOWN_MS} entre apariciones para no ser invasiva, y un "no volver a preguntar"
 * permanente si el usuario lo pide.
 */
final class AvisoDonativo {

    private static final String PREFS = "geomb_donativo";
    private static final String K_ULTIMO_MOSTRADO = "ultimo_mostrado_ms";
    private static final String K_NO_PREGUNTAR = "no_preguntar";
    private static final long COOLDOWN_MS = 7L * 24 * 3600 * 1000;   // 7 días

    private AvisoDonativo() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Muestra el diálogo si toca (no se desactivó permanentemente y ya pasó el cooldown desde
     *  la última vez). Nunca truena: una falla aquí no debe impedir que la app arranque. */
    static void mostrarSiToca(Activity act) {
        try {
            if (act.isFinishing() || act.isDestroyed()) return;
            SharedPreferences prefs = p(act);
            if (prefs.getBoolean(K_NO_PREGUNTAR, false)) return;
            long ahora = System.currentTimeMillis();
            long ultimo = prefs.getLong(K_ULTIMO_MOSTRADO, 0L);
            if (ahora - ultimo < COOLDOWN_MS) return;
            prefs.edit().putLong(K_ULTIMO_MOSTRADO, ahora).apply();

            new AlertDialog.Builder(act)
                    .setTitle(R.string.donativo_titulo)
                    .setMessage(R.string.donativo_mensaje)
                    .setPositiveButton(R.string.donativo_boton_donar, (d, w) -> abrirPaypal(act))
                    .setNegativeButton(R.string.donativo_boton_ahora_no, null)
                    .setNeutralButton(R.string.donativo_boton_no_preguntar, (d, w) ->
                            prefs.edit().putBoolean(K_NO_PREGUNTAR, true).apply())
                    .show();
        } catch (Exception ignore) {}
    }

    private static void abrirPaypal(Activity act) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Config.DONACION_URL)));
        } catch (Exception ignore) {}
    }
}
