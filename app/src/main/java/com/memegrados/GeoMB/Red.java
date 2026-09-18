package com.memegrados.GeoMB;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;

/**
 * Utilidades de red. Detecta si el teléfono está usando datos móviles (en roaming o no) para
 * espaciar las peticiones periódicas (feed en tiempo real, llegadas, seguimiento) y así no generar
 * tantos cargos ni consumir de más el paquete de datos del día, sin bloquear el uso de la app.
 * El ahorro en datos móviles (no roaming) respeta el interruptor {@link Modos#ahorroDatos}, que el
 * usuario puede apagar desde "Acerca de"; en roaming siempre se espacia, sin importar ese ajuste,
 * por ser el caso de cargos más caros.
 *
 * NOTA: el ciclo de anuncios de estación (RecorridoService) NO usa esto para su propio GPS (sin
 * costo, debe correr a su ritmo normal para que no se encimen los avisos), pero SÍ consulta
 * {@link #datosMoviles} y {@link Modos#ahorroDatos} para decidir si descarga la voz Mia o usa
 * directo la voz local del teléfono.
 */
// ============================================================
// CLASE    : Red
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Utilidades de RED (conexión). Detecta si el teléfono usa datos móviles
// (y si está en roaming) para ESPACIAR las peticiones periódicas (feed en
// vivo, llegadas, seguimiento) y no gastar de más el paquete de datos, sin
// bloquear el uso de la app.
//
// REGLAS:
//   - Datos móviles normales + ahorro activado (Modos.ahorroDatos): espacia
//     con FACTOR_DATOS.
//   - Roaming: espacia SIEMPRE (FACTOR_ROAMING), sin importar el ajuste
//     (son los cargos más caros).
//   - El GPS del recorrido NO se espacia (debe correr a su ritmo), pero sí
//     consulta esto para decidir si baja la voz Mia o usa la voz local.
//
// Clase de UTILIDAD (final + constructor privado + métodos static).
// ============================================================
public final class Red {

    /** En roaming, las peticiones periódicas se espacian este factor (siempre, sin importar el ajuste). */
    private static final long FACTOR_ROAMING = 3;
    /** En datos móviles normales (sin roaming) con el ahorro activado, este factor más leve. */
    private static final long FACTOR_DATOS = 2;

    private Red() {}

    /** ¿La red activa es celular (datos móviles), esté o no en roaming? (Wi-Fi u otra = false). */
    public static boolean datosMoviles(Context c) {
        if (c == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable t) {
            return false;   // ante cualquier duda, comportamiento normal (no ahorra)
        }
    }

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

    /** Intervalo efectivo para una petición periódica: más espaciado en roaming (siempre) o en
     *  datos móviles normales si el ahorro de datos está activado. */
    public static long intervalo(Context c, long baseMs) {
        if (enRoaming(c)) return baseMs * FACTOR_ROAMING;
        if (Modos.ahorroDatos(c) && datosMoviles(c)) return baseMs * FACTOR_DATOS;
        return baseMs;
    }

    /** ¿Debe evitarse gastar datos ahora mismo? (datos móviles + ahorro activado). Se usa, p. ej.,
     *  para decidir si se descarga la voz Mia o se usa directo la voz local del teléfono. */
    public static boolean ahorrarAhora(Context c) {
        return Modos.ahorroDatos(c) && datosMoviles(c);
    }
}
