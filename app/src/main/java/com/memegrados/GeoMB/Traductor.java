package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Traducción automática de la interfaz con ML Kit (motor de Google; funciona sin conexión
 * tras descargar el paquete del idioma). Traduce desde el ESPAÑOL (idioma base) al idioma
 * objetivo elegido en el selector.
 *
 * Es traducción AUTOMÁTICA: puede estar incompleta o contener errores (por eso se avisa al
 * usuario). Cubre los textos estáticos de las pantallas; algunos textos generados en tiempo
 * de ejecución o dentro de notificaciones pueden quedar en español.
 */
public final class Traductor {

    private static final String PREFS = "geomb_idioma";
    private static final String K_OBJ = "objetivo";   // etiqueta BCP-47; "" = español (sin traducir)

    private static Translator cliente;
    private static String clienteLang;
    private static boolean modeloListo;

    /** Cache de traducciones: idioma -> (texto original -> traducido). Evita repetir llamadas. */
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();

    private Traductor() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Idioma objetivo actual; null = español base (sin traducir). */
    public static String objetivo(Context c) {
        String o = p(c).getString(K_OBJ, "");
        return (o == null || o.isEmpty()) ? null : o;
    }

    /** Fija el idioma objetivo (null/"" = español). Reinicia el traductor si cambió. */
    public static void setObjetivo(Context c, String tag) {
        String nuevo = (tag == null || tag.isEmpty()) ? null : tag;
        if (eq(objetivo(c), nuevo)) return;
        p(c).edit().putString(K_OBJ, nuevo == null ? "" : nuevo).apply();
        cerrar();
    }

    /** ¿ML Kit soporta este idioma como destino? */
    public static boolean soportado(String tag) {
        return tag != null && TranslateLanguage.fromLanguageTag(tag) != null;
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** Resultado de una traducción asíncrona de texto suelto. */
    public interface Cb { void listo(String texto); }

    /**
     * Idioma efectivo de VISUALIZACIÓN: el objetivo automático (ML Kit) si hay uno, o si no el
     * idioma del locale de recursos ("es", "en", "nah", ...). Sirve para el contenido dinámico
     * (notificaciones/avisos del backend) que no vive en strings.xml.
     */
    private static String idiomaEfectivo(Context c) {
        String obj = objetivo(c);
        if (obj != null) return obj;
        String loc = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (loc == null || loc.isEmpty()) return "es";
        int g = loc.indexOf('-');
        return (g > 0 ? loc.substring(0, g) : loc).toLowerCase();
    }

    /**
     * Traduce un texto suelto (en ESPAÑOL) al idioma efectivo del dispositivo con el motor de
     * Google, para contenido dinámico (afectaciones/push). Para español, náhuatl u otro idioma
     * NO soportado por ML Kit devuelve el original. Asíncrono: entrega por callback (hilo principal).
     */
    public static void traducirTexto(Context c, String textoEs, Cb cb) {
        if (textoEs == null || textoEs.trim().isEmpty()) { cb.listo(textoEs); return; }
        final String lang = idiomaEfectivo(c);
        if ("es".equals(lang) || !soportado(lang)) { cb.listo(textoEs); return; }

        final Map<String, String> cache = cache(lang);
        String hit = cache.get(textoEs);
        if (hit != null) { cb.listo(hit); return; }

        final Translator t = clientePara(lang);
        final Runnable trad = () -> t.translate(textoEs)
                .addOnSuccessListener(res -> {
                    String out = (res == null || res.isEmpty()) ? textoEs : res;
                    if (res != null && !res.isEmpty()) cache.put(textoEs, res);
                    cb.listo(out);
                })
                .addOnFailureListener(e -> cb.listo(textoEs));

        if (modeloListo && lang.equals(clienteLang)) { trad.run(); return; }
        DownloadConditions cond = new DownloadConditions.Builder().build();
        t.downloadModelIfNeeded(cond)
                .addOnSuccessListener(v -> { modeloListo = true; trad.run(); })
                .addOnFailureListener(e -> cb.listo(textoEs));
    }

    private static void cerrar() {
        if (cliente != null) { try { cliente.close(); } catch (Throwable ignore) {} }
        cliente = null; clienteLang = null; modeloListo = false;
    }

    /**
     * Traduce todos los TextView del árbol al idioma objetivo. Si el objetivo es español
     * (o el idioma no está soportado) no hace nada. Es asíncrono: aplica de inmediato lo que
     * ya esté en cache, descarga el modelo si falta y va reemplazando el resto conforme llega.
     */
    public static void traducirArbol(View root) {
        if (root == null) return;
        final String lang = objetivo(root.getContext());
        if (lang == null || !soportado(lang)) return;

        recorrerCache(root, lang);   // instantáneo con lo ya traducido

        final Translator t = clientePara(lang);
        if (modeloListo && lang.equals(clienteLang)) { recorrer(root, t, lang); return; }
        DownloadConditions cond = new DownloadConditions.Builder().build();
        t.downloadModelIfNeeded(cond)
                .addOnSuccessListener(v -> { modeloListo = true; recorrer(root, t, lang); })
                .addOnFailureListener(e -> { /* sin modelo/conexión: queda en español */ });
    }

    private static Translator clientePara(String lang) {
        if (cliente != null && lang.equals(clienteLang)) return cliente;
        cerrar();
        TranslatorOptions opts = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(lang))
                .build();
        cliente = Translation.getClient(opts);
        clienteLang = lang;
        modeloListo = false;
        return cliente;
    }

    private static Map<String, String> cache(String lang) {
        Map<String, String> m = CACHE.get(lang);
        if (m == null) { m = new HashMap<>(); CACHE.put(lang, m); }
        return m;
    }

    /** Recorre el árbol y traduce cada TextView (llamando al modelo si hace falta). */
    private static void recorrer(View root, Translator t, String lang) {
        if (root == null) return;
        if (root instanceof TextView) { traducirVista((TextView) root, t, lang); return; }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) recorrer(g.getChildAt(i), t, lang);
        }
    }

    /** Recorre el árbol aplicando SOLO traducciones ya cacheadas (sin red). */
    private static void recorrerCache(View root, String lang) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            CharSequence cs = tv.getText();
            if (cs != null) {
                String hit = cache(lang).get(cs.toString());
                if (hit != null) tv.setText(hit);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) recorrerCache(g.getChildAt(i), lang);
        }
    }

    private static void traducirVista(TextView tv, Translator t, String lang) {
        CharSequence cs = tv.getText();
        if (cs == null) return;
        final String src = cs.toString();
        if (!traducible(src)) return;

        Map<String, String> cache = cache(lang);
        String hit = cache.get(src);
        if (hit != null) { tv.setText(hit); return; }

        t.translate(src).addOnSuccessListener(res -> {
            if (res == null || res.isEmpty()) return;
            cache.put(src, res);
            // Solo si la vista sigue mostrando el texto original (no cambió mientras tanto).
            if (src.contentEquals(tv.getText())) tv.setText(res);
        });
    }

    /** Evita traducir números, arrobas, enlaces, el nombre de la app o textos sin letras. */
    private static boolean traducible(String s) {
        if (s == null) return false;
        String x = s.trim();
        if (x.length() < 2) return false;
        if (x.startsWith("@") || x.startsWith("http") || x.startsWith("www.")) return false;
        if ("GeoMB".equals(x)) return false;
        int letras = 0;
        for (int i = 0; i < x.length(); i++) if (Character.isLetter(x.charAt(i))) letras++;
        return letras >= 2;
    }
}
