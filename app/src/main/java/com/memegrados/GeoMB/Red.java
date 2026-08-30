package com.memegrados.GeoMB;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;

/**
 * Utilidades de red. En particular, detecta si el teléfono está usando datos móviles EN ROAMING
 * para espaciar las peticiones periódicas (feed en tiempo real, llegadas, seguimiento) y así no
 * generar tantos cargos, sin bloquear el uso de la app.
 *
 * NOTA: el ciclo de anuncios de estación (RecorridoService) NO usa esto: se basa en el GPS (sin
 * costo) y debe correr a su ritmo normal para que no se encimen los avisos.
 */
public final class Red {

    /** En roaming, las peticiones periódicas se espacian este factor. */
    private static final long FACTOR_ROAMING = 3;

    private Red() {}

    /** ¿La red activa son datos móviles EN ROAMING? (Wi-Fi u otra no celular = false). */
    public static boolean enRoaming(Context c) {
        if (c == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
                if (caps != null) {
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false; // Wi-Fi/otras
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
                }
            }
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.isNetworkRoaming();
        } catch (Throwable t) {
            return false;   // ante cualquier duda, comportamiento normal
        }
    }

    /** Intervalo efectivo para una petición periódica: mayor si hay roaming (menos cargos). */
    public static long intervalo(Context c, long baseMs) {
        return enRoaming(c) ? baseMs * FACTOR_ROAMING : baseMs;
    }
}
