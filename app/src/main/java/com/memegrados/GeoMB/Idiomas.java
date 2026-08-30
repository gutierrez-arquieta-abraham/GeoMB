package com.memegrados.GeoMB;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Selector de idioma de la app, con su bandera/logo por idioma. Modelo MIXTO de traducción:
 *
 *  - Idiomas CURADOS (con archivos de recursos): español (base), inglés y náhuatl. Se aplican
 *    con el sistema de "idioma por app" (res/values-*), traducción revisada.
 *  - CUALQUIER OTRO idioma: se traduce AUTOMÁTICAMENTE con el motor de Google (ML Kit) desde
 *    el español (ver Traductor). Puede estar incompleta o contener errores, por eso al elegirlo
 *    se muestra un aviso. Se puede volver a español o cambiar de idioma cuando se quiera.
 *
 * Para sumar un idioma curado: crea res/values-b+&lt;tag&gt;/ y agrégalo en curado().
 * Los idiomas automáticos son los que soporte ML Kit (~59); se listan en LISTA.
 */
public final class Idiomas {

    // { etiqueta BCP-47, nombre en su propia lengua (autoglotónimo), bandera/logo de la región }.
    private static final String[][] LISTA = {
            {"es",  "Español",     "🇲🇽"},
            {"en",  "English",     "🇺🇸"},
            {"nah", "Náhuatl",     "🇲🇽"},   // curado (semilla); ML Kit no lo soporta
            {"fr",  "Français",    "🇫🇷"},
            {"pt",  "Português",   "🇧🇷"},
            {"de",  "Deutsch",     "🇩🇪"},
            {"it",  "Italiano",    "🇮🇹"},
            {"nl",  "Nederlands",  "🇳🇱"},
            {"ru",  "Русский",     "🇷🇺"},
            {"uk",  "Українська",  "🇺🇦"},
            {"pl",  "Polski",      "🇵🇱"},
            {"ro",  "Română",      "🇷🇴"},
            {"el",  "Ελληνικά",    "🇬🇷"},
            {"tr",  "Türkçe",      "🇹🇷"},
            {"ar",  "العربية",      "🇸🇦"},
            {"he",  "עברית",        "🇮🇱"},
            {"hi",  "हिन्दी",        "🇮🇳"},
            {"bn",  "বাংলা",        "🇧🇩"},
            {"ja",  "日本語",        "🇯🇵"},
            {"ko",  "한국어",        "🇰🇷"},
            {"zh",  "中文",          "🇨🇳"},
            {"th",  "ไทย",          "🇹🇭"},
            {"vi",  "Tiếng Việt",   "🇻🇳"},
            {"id",  "Indonesia",   "🇮🇩"},
            {"tl",  "Tagalog",     "🇵🇭"},
            {"sw",  "Kiswahili",   "🇰🇪"},
            {"sv",  "Svenska",     "🇸🇪"},
            {"fi",  "Suomi",       "🇫🇮"},
            {"cs",  "Čeština",     "🇨🇿"},
            {"hu",  "Magyar",      "🇭🇺"},
    };

    private Idiomas() {}

    /** Idiomas con archivos de recursos propios (no usan traducción automática). */
    private static boolean curado(String tag) {
        return "es".equals(tag) || "en".equals(tag) || "nah".equals(tag);
    }

    /** Muestra el selector con banderas. Al elegir, aplica el idioma y recrea la pantalla. */
    public static void mostrarSelector(Context c) {
        final Activity a = actividad(c);
        if (a == null) return;

        final String[] items = new String[LISTA.length];
        for (int i = 0; i < LISTA.length; i++) items[i] = LISTA[i][2] + "   " + LISTA[i][1];

        new AlertDialog.Builder(a)
                .setTitle(R.string.idioma_dialogo)
                .setSingleChoiceItems(items, indiceActual(a), (d, w) -> {
                    d.dismiss();
                    String tag = LISTA[w][0];
                    if (curado(tag)) aplicar(a, tag);
                    else mostrarAviso(a, () -> aplicar(a, tag));   // traducción automática: avisar
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Aviso de que la traducción es automática y puede estar incompleta o tener errores. */
    private static void mostrarAviso(Activity a, Runnable alAceptar) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.traduccion_auto_titulo)
                .setMessage(R.string.traduccion_auto_aviso)
                .setPositiveButton(android.R.string.ok, (d, w) -> alAceptar.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Índice del idioma activo (objetivo automático o locale de recursos). */
    private static int indiceActual(Context c) {
        String obj = Traductor.objetivo(c);
        String buscar;
        if (obj != null) buscar = obj;
        else {
            String loc = AppCompatDelegate.getApplicationLocales().toLanguageTags().toLowerCase();
            buscar = loc.isEmpty() ? "es" : loc;
        }
        for (int i = 0; i < LISTA.length; i++) if (buscar.startsWith(LISTA[i][0])) return i;
        return 0;
    }

    private static void aplicar(Activity a, String tag) {
        boolean maquina = !curado(tag);
        String antes = AppCompatDelegate.getApplicationLocales().toLanguageTags().toLowerCase();
        boolean baseAntes = antes.isEmpty() || antes.startsWith("es");
        if (maquina) {
            // Base en español + traducción automática al idioma elegido.
            Traductor.setObjetivo(a, tag);
            if (baseAntes) a.recreate();
            else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"));
        } else {
            // Idioma curado: recursos values-*; sin traducción automática.
            Traductor.setObjetivo(a, null);
            boolean sinCambio = "es".equals(tag) ? baseAntes : antes.startsWith(tag);
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
            if (sinCambio) a.recreate();
        }
    }

    /** Encuentra la Activity a partir de un Context (para recrear la pantalla). */
    private static Activity actividad(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
