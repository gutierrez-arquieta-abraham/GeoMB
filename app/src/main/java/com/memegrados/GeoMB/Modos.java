package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Modos ocultos de la app, activables desde "Acerca de" (5 toques al logo).
 * Se guardan en SharedPreferences. Los sub-modos (cachondo / PBS) solo cuentan
 * si el modo personalizado está activo, y se desactivan si éste se apaga.
 */
// ============================================================
// CLASE    : Modos
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Guarda las PREFERENCIAS y "modos" de la app en SharedPreferences
// (un almacén clave-valor que persiste aunque se cierre la app).
//
// QUÉ GUARDA:
//   - Modos ocultos (personalizado, cachondo, PBS) activables desde
//     "Acerca de" con 5 toques al logo. Los sub-modos solo cuentan si
//     'personalizado' está activo, y se apagan con él.
//   - Sincronización en segundo plano.
//   - Tiempo de refresco del mapa (15–120 s, def. 30).
//   - Suscripciones de notificaciones (afectaciones, actualizaciones y
//     por línea 1..7).
//   - Mostrar Mexibús (capa + ruteo), estilo de iconos, ahorro de datos.
//
// PATRÓN: para cada preferencia hay un GET (leer) y un SET (guardar).
// Clase de UTILIDAD (final + constructor privado + métodos static);
// los valores viven en SharedPreferences "geomb_modos".
// ============================================================
public final class Modos {

    private static final String PREFS = "geomb_modos";
    private static final String K_PERSON = "personalizado";
    private static final String K_CACHONDO = "cachondo";
    private static final String K_PBS = "pbs";
    private static final String K_VOZ_XIMENA_DISP = "voz_ximena";   // gate maestro (frase secreta)
    private static final String K_VOZ_XIMENA_METROBUS = "voz_ximena_metrobus";
    private static final String K_VOZ_XIMENA_MEXIBUS = "voz_ximena_mexibus";
    private static final String K_VOZ_XIMENA_MEXICABLE = "voz_ximena_mexicable";
    private static final String K_SINCRO = "sincro_fondo";

    /** Frases secretas EXACTAS (se comparan tal cual, con mayúsculas y acentos). */
    public static final String FRASE_CACHONDO = "CUMsultando a la patrona";
    public static final String FRASE_PBS = "Metrobús & Mexibús PBS";
    // Voz Ximena (Fish Audio, clon del anuncio real del Metrobús/CITI Indra) es de PAGO por uso
    // en el backend -- a
    // diferencia de cachondo/pbs (gratis, solo cosméticos), esta SÍ le cuesta dinero al EC2
    // cada vez que se habla con ella. Por eso vive tras su propia frase secreta (no basta con
    // desbloquear "personalizado"), para que nadie la active sin querer ni la descubra por
    // accidente -- solo quien ya sabe esta frase específica.
    public static final String FRASE_VOZ_XIMENA = "Voz CITI Indra";

    private Modos() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean personalizado(Context c) {
        return p(c).getBoolean(K_PERSON, false);
    }

    public static boolean cachondo(Context c) {
        return personalizado(c) && p(c).getBoolean(K_CACHONDO, false);
    }

    public static boolean pbs(Context c) {
        return personalizado(c) && p(c).getBoolean(K_PBS, false);
    }

    /** Gate maestro de la voz Ximena (desbloqueado con su frase secreta): si está apagado, NINGÚN
     *  sistema puede usar Ximena sin importar lo que tengan guardado sus 3 switches individuales. */
    public static boolean vozXimenaDisponible(Context c) {
        return personalizado(c) && p(c).getBoolean(K_VOZ_XIMENA_DISP, false);
    }

    /** Switch de Ximena POR SISTEMA (Metrobús/Mexibús/Mexicable, independientes entre sí) --
     *  solo cuentan si {@link #vozXimenaDisponible} ya está activo. */
    public static boolean vozXimenaMetrobus(Context c) {
        return vozXimenaDisponible(c) && p(c).getBoolean(K_VOZ_XIMENA_METROBUS, false);
    }

    public static boolean vozXimenaMexibus(Context c) {
        return vozXimenaDisponible(c) && p(c).getBoolean(K_VOZ_XIMENA_MEXIBUS, false);
    }

    public static boolean vozXimenaMexicable(Context c) {
        return vozXimenaDisponible(c) && p(c).getBoolean(K_VOZ_XIMENA_MEXICABLE, false);
    }

    /** ¿El sistema de esta línea tiene su propio switch de Ximena activo? Único punto de verdad
     *  compartido por {@link #vozActual} (avisos en vivo) y la pre-descarga masiva (DescargaVoz vía
     *  ConfiguracionFragment), para que ambos resuelvan "puede esta línea usar Ximena" igual. */
    public static boolean vozXimenaPermitida(Context c, int linea) {
        switch (Sistemas.sistemaDe(linea)) {
            case Sistemas.MEXIBUS:   return vozXimenaMexibus(c);
            case Sistemas.MEXICABLE: return vozXimenaMexicable(c);
            default:                 return vozXimenaMetrobus(c);
        }
    }

    /** Nombre de voz a pedirle al backend (/api/tts?voz=...) para una parada de esta línea: "Ximena"
     *  solo si el sistema de esa línea tiene su propio switch activo; "Mia" (AWS Polly, de siempre)
     *  en cualquier otro caso -- cada sistema elige su voz por separado. */
    public static String vozActual(Context c, int linea) {
        return vozXimenaPermitida(c, linea) ? "Ximena" : "Mia";
    }

    public static void setPersonalizado(Context c, boolean v) {
        p(c).edit().putBoolean(K_PERSON, v).apply();
        // apagar todo con él
        if (!v) { setCachondo(c, false); setPbs(c, false); setVozXimenaDisponible(c, false); }
    }

    public static void setCachondo(Context c, boolean v) {
        p(c).edit().putBoolean(K_CACHONDO, v).apply();
    }

    public static void setPbs(Context c, boolean v) {
        p(c).edit().putBoolean(K_PBS, v).apply();
    }

    public static void setVozXimenaDisponible(Context c, boolean v) {
        p(c).edit().putBoolean(K_VOZ_XIMENA_DISP, v).apply();
        if (!v) {   // apaga los 3 switches individuales con el gate maestro
            setVozXimenaMetrobus(c, false);
            setVozXimenaMexibus(c, false);
            setVozXimenaMexicable(c, false);
        }
    }

    public static void setVozXimenaMetrobus(Context c, boolean v) {
        p(c).edit().putBoolean(K_VOZ_XIMENA_METROBUS, v).apply();
    }

    public static void setVozXimenaMexibus(Context c, boolean v) {
        p(c).edit().putBoolean(K_VOZ_XIMENA_MEXIBUS, v).apply();
    }

    public static void setVozXimenaMexicable(Context c, boolean v) {
        p(c).edit().putBoolean(K_VOZ_XIMENA_MEXICABLE, v).apply();
    }

    // --- Sincronización en segundo plano (independiente del modo personalizado) ---

    public static boolean sincronizacionFondo(Context c) {
        return p(c).getBoolean(K_SINCRO, false);
    }

    public static void setSincronizacionFondo(Context c, boolean v) {
        p(c).edit().putBoolean(K_SINCRO, v).apply();
    }

    // --- Tiempo de actualización del mapa (segundos), configurable por el usuario en "Acerca de".
    //     El endpoint SONDA refresca cada ~30–60 s, así que por defecto son 30 s. ---

    private static final String K_MAPA_SEG = "mapa_refresco_seg";
    public static final int MAPA_SEG_MIN = 15, MAPA_SEG_MAX = 120, MAPA_SEG_DEF = 30;

    public static int mapaRefrescoSeg(Context c) {
        int s = p(c).getInt(K_MAPA_SEG, MAPA_SEG_DEF);
        return Math.max(MAPA_SEG_MIN, Math.min(MAPA_SEG_MAX, s));
    }

    public static void setMapaRefrescoSeg(Context c, int seg) {
        p(c).edit().putInt(K_MAPA_SEG, Math.max(MAPA_SEG_MIN, Math.min(MAPA_SEG_MAX, seg))).apply();
    }

    /** Intervalo del sondeo del mapa en milisegundos (según el ajuste del usuario). */
    public static long mapaRefrescoMs(Context c) {
        return mapaRefrescoSeg(c) * 1000L;
    }

    // --- Notificaciones de suscripción (push por temas FCM). Por defecto activas. ---

    public static boolean notifAfectaciones(Context c) {
        return p(c).getBoolean("notif_afect", true);
    }

    public static void setNotifAfectaciones(Context c, boolean v) {
        p(c).edit().putBoolean("notif_afect", v).apply();
    }

    public static boolean notifActualizaciones(Context c) {
        return p(c).getBoolean("notif_act", true);
    }

    public static void setNotifActualizaciones(Context c, boolean v) {
        p(c).edit().putBoolean("notif_act", v).apply();
    }

    // --- Control maestro: avisos de afectación por LÍNEA (1..7). Por defecto todas activas.
    //     Filtra las notificaciones locales (ServicioMB): si una línea está apagada, no avisa de ella. ---

    public static boolean notifLinea(Context c, int linea) {
        return p(c).getBoolean("notif_linea_" + linea, true);
    }

    public static void setNotifLinea(Context c, int linea, boolean v) {
        p(c).edit().putBoolean("notif_linea_" + linea, v).apply();
    }

    // --- Mostrar Mexibús / Mexicable: activan su capa en el mapa y su ruteo en el planificador,
    // cada sistema por separado (antes de este split, una sola bandera "mostrarMexibus" controlaba
    // ambos a la vez, porque mexibus.json los trae juntos). ---

    public static boolean mostrarMexibus(Context c) {
        return p(c).getBoolean("mostrar_mexibus", false);
    }

    public static void setMostrarMexibus(Context c, boolean v) {
        p(c).edit().putBoolean("mostrar_mexibus", v).apply();
    }

    private static final String K_MXC_MIGRADO = "mxc_migrado_v1";

    /** Mexicable es NUEVO como bandera independiente: a quien ya tenía "mostrarMexibus" (cuando
     *  representaba a los dos sistemas) se le hereda ese mismo valor UNA SOLA VEZ, para que la
     *  actualización de GeoMB no le apague Mexicable de golpe sin que lo haya decidido. */
    private static void migrarMexicableSiHaceFalta(Context c) {
        SharedPreferences prefs = p(c);
        if (prefs.contains(K_MXC_MIGRADO)) return;
        prefs.edit()
                .putBoolean("mostrar_mexicable", mostrarMexibus(c))
                .putBoolean(K_MXC_MIGRADO, true)
                .apply();
    }

    public static boolean mostrarMexicable(Context c) {
        migrarMexicableSiHaceFalta(c);
        return p(c).getBoolean("mostrar_mexicable", false);
    }

    public static void setMostrarMexicable(Context c, boolean v) {
        p(c).edit().putBoolean("mostrar_mexicable", v).putBoolean(K_MXC_MIGRADO, true).apply();
    }

    // --- Estilo de iconos de estación: true = pictogramas nuevos; false = puntos simples (antiguos). ---

    public static boolean iconosNuevos(Context c) {
        return p(c).getBoolean("iconos_nuevos", true);
    }

    public static void setIconosNuevos(Context c, boolean v) {
        p(c).edit().putBoolean("iconos_nuevos", v).apply();
    }

    // --- Ahorro de datos: en datos móviles, espacia el refresco de unidades en vivo y usa solo la
    // voz local (TTS) en vez de descargar la voz Mia. Activo por defecto (hay usuarios con muy poco
    // saldo/datos para el día); se puede desactivar desde "Acerca de". ---

    public static boolean ahorroDatos(Context c) {
        return p(c).getBoolean("ahorro_datos", true);
    }

    public static void setAhorroDatos(Context c, boolean v) {
        p(c).edit().putBoolean("ahorro_datos", v).apply();
    }

    // --- Certificado cliente (.p12) del panel admin de afectaciones (mTLS), modo personalizado.
    // Solo se guarda el URI del archivo (un simple puntero al almacenamiento del propio teléfono,
    // no un secreto): la CONTRASEÑA del .p12 nunca se persiste, se pide cada vez que se abre el
    // panel (PanelAdminActivity), igual que exige CLAUDE.md ("nunca tokens/llaves en claro"). ---

    public static String certAdminUri(Context c) {
        return p(c).getString("cert_admin_uri", null);
    }

    public static void setCertAdminUri(Context c, String uri) {
        p(c).edit().putString("cert_admin_uri", uri).apply();
    }
}
