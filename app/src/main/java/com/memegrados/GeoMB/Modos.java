package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Modos ocultos de la app, activables desde "Acerca de" (5 toques al logo).
 * Se guardan en SharedPreferences. Los sub-modos (cachondo / PBS) solo cuentan
 * si el modo personalizado está activo, y se desactivan si éste se apaga.
 */
public final class Modos {

    private static final String PREFS = "geomb_modos";
    private static final String K_PERSON = "personalizado";
    private static final String K_CACHONDO = "cachondo";
    private static final String K_PBS = "pbs";
    private static final String K_SINCRO = "sincro_fondo";

    /** Frases secretas EXACTAS (se comparan tal cual, con mayúsculas y acentos). */
    public static final String FRASE_CACHONDO = "CUMsultando a la patrona";
    public static final String FRASE_PBS = "Metrobús & Mexibús PBS";

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

    public static void setPersonalizado(Context c, boolean v) {
        p(c).edit().putBoolean(K_PERSON, v).apply();
        if (!v) { setCachondo(c, false); setPbs(c, false); }   // apagar todo con él
    }

    public static void setCachondo(Context c, boolean v) {
        p(c).edit().putBoolean(K_CACHONDO, v).apply();
    }

    public static void setPbs(Context c, boolean v) {
        p(c).edit().putBoolean(K_PBS, v).apply();
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

    // --- Mostrar Mexibús: activa la capa del Mexibús en el mapa y su ruteo en el planificador. ---

    public static boolean mostrarMexibus(Context c) {
        return p(c).getBoolean("mostrar_mexibus", false);
    }

    public static void setMostrarMexibus(Context c, boolean v) {
        p(c).edit().putBoolean("mostrar_mexibus", v).apply();
    }

    // --- Estilo de iconos de estación: true = pictogramas nuevos; false = puntos simples (antiguos). ---

    public static boolean iconosNuevos(Context c) {
        return p(c).getBoolean("iconos_nuevos", true);
    }

    public static void setIconosNuevos(Context c, boolean v) {
        p(c).edit().putBoolean("iconos_nuevos", v).apply();
    }
}
