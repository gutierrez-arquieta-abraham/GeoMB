package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Perfil del usuario capturado en el mini-formulario de login. Personaliza la app:
 * - Tipo (Normal / Aficionado): ninguno ve el buscador de unidades históricas.
 * - Movilidad reducida: solo entonces se muestran avisos de elevadores afectados.
 */
public final class Perfil {

    public static final int NORMAL = 0;
    public static final int AFICIONADO = 1;

    public static final int HOMBRE = 0;
    public static final int MUJER = 1;

    private static final String PREFS = "geomb_perfil";
    private static final String K_CONFIG = "configurado";
    private static final String K_TIPO = "tipo";
    private static final String K_MOVILIDAD = "movilidad_reducida";
    private static final String K_GENERO = "genero";

    private Perfil() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** ¿Ya completó el mini-formulario? */
    public static boolean configurado(Context c) {
        return p(c).getBoolean(K_CONFIG, false);
    }

    public static int tipo(Context c) {
        return p(c).getInt(K_TIPO, NORMAL);
    }

    public static boolean movilidadReducida(Context c) {
        return p(c).getBoolean(K_MOVILIDAD, false);
    }

    public static int genero(Context c) {
        return p(c).getInt(K_GENERO, HOMBRE);
    }

    /** Los servicios rosas (unidades exclusivas para mujeres) solo se ofrecen si el perfil es Mujer. */
    public static boolean serviciosRosa(Context c) {
        return genero(c) == MUJER;
    }

    /** Guarda el perfil y lo marca como configurado. */
    public static void guardar(Context c, int tipo, boolean movilidadReducida, int genero) {
        p(c).edit()
                .putInt(K_TIPO, tipo)
                .putBoolean(K_MOVILIDAD, movilidadReducida)
                .putInt(K_GENERO, genero)
                .putBoolean(K_CONFIG, true)
                .apply();
    }

    /** El buscador de unidades históricas: solo el Aficionado tiene todos los módulos. */
    public static boolean muestraBuscador(Context c) {
        return tipo(c) == AFICIONADO;
    }

    /** Los avisos de elevadores solo aplican con movilidad reducida. */
    public static boolean muestraElevadores(Context c) {
        return movilidadReducida(c);
    }
}
