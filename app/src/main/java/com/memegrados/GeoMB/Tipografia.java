package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Tipografía institucional "Tipo Metro" (res/font/tipo_metro.otf).
 * Se usa en textos breves: nombres de estación y números de unidad.
 */
// ============================================================
// CLASE    : Tipografia
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Da acceso a la tipografía institucional "Tipo Metro"
// (res/font/tipo_metro.otf), usada en textos breves como nombres de
// estación y números de unidad.
//
// Se cachea (patrón "doble verificación con synchronized") para cargarla
// UNA sola vez; si falla, usa la tipografía por defecto. Clase de UTILIDAD.
// ============================================================
public final class Tipografia {

    private static volatile Typeface metro;
    private static volatile Typeface gothamBlack;
    private static volatile Typeface roundedElegance;

    private Tipografia() {}

    /** Devuelve la tipografía Tipo Metro (cacheada). Puede ser null si falla la carga. */
    public static Typeface metro(Context c) {
        if (metro == null) {
            synchronized (Tipografia.class) {
                if (metro == null) {
                    try { metro = ResourcesCompat.getFont(c.getApplicationContext(), R.font.tipo_metro); }
                    catch (Throwable t) { metro = Typeface.DEFAULT; }
                }
            }
        }
        return metro;
    }

    /** Gotham Black: señalética de la iconografía nueva (Movimex) del Mexibús. */
    public static Typeface gothamBlack(Context c) {
        if (gothamBlack == null) {
            synchronized (Tipografia.class) {
                if (gothamBlack == null) {
                    try { gothamBlack = ResourcesCompat.getFont(c.getApplicationContext(), R.font.gotham_black); }
                    catch (Throwable t) { gothamBlack = Typeface.DEFAULT_BOLD; }
                }
            }
        }
        return gothamBlack;
    }

    /** Rounded Elegance: señalética original de Mexibús L1/L2 (iconografía antigua). */
    public static Typeface roundedElegance(Context c) {
        if (roundedElegance == null) {
            synchronized (Tipografia.class) {
                if (roundedElegance == null) {
                    try { roundedElegance = ResourcesCompat.getFont(c.getApplicationContext(), R.font.rounded_elegance); }
                    catch (Throwable t) { roundedElegance = Typeface.DEFAULT; }
                }
            }
        }
        return roundedElegance;
    }

    /**
     * Tipografía de nombre de estación según línea y modo de iconografía. Solo distingue
     * dentro de Mexibús/Mexicable (imitan su señalética real, distinta por época/línea);
     * Metrobús sigue con Tipo Metro, sin cambios.
     *  - Iconografía nueva (Movimex, toda la red Mexibús/Mexicable): Gotham Black.
     *  - Iconografía antigua, L1 (101) / L2 (102): Rounded Elegance (su tipografía original).
     *  - Iconografía antigua, resto (ramales L1A/L2A/L3A = 111/112/113, L3/L4 = 103/104,
     *    exprés L1–L4 = 121–124, y Mexicable 201+): Arial — Android no trae esa fuente, se usa
     *    el sans-serif del sistema como equivalente más cercano.
     */
    public static Typeface fuenteEstacion(Context c, int linea) {
        boolean esMexibus = (linea >= 101 && linea <= 104) || (linea >= 111 && linea <= 113)
                || (linea >= 121 && linea <= 124);
        boolean esMexicable = linea >= 200;
        if (!esMexibus && !esMexicable) return metro(c);
        if (Modos.iconosNuevos(c)) return gothamBlack(c);
        if (linea == 101 || linea == 102) return roundedElegance(c);
        return Typeface.SANS_SERIF;
    }

    /** Aplica {@link #fuenteEstacion} a un TextView de nombre de estación, respetando su estilo actual. */
    public static void aplicarEstacion(TextView v, int linea) {
        if (v == null) return;
        int estilo = v.getTypeface() != null ? v.getTypeface().getStyle() : Typeface.NORMAL;
        v.setTypeface(fuenteEstacion(v.getContext(), linea), estilo);
    }

    /** Aplica Tipo Metro a uno o más TextView, respetando el estilo (normal/negrita) actual. */
    public static void aplicar(TextView... vistas) {
        if (vistas == null || vistas.length == 0) return;
        Typeface base = metro(vistas[0].getContext());
        for (TextView v : vistas) {
            if (v == null) continue;
            int estilo = v.getTypeface() != null ? v.getTypeface().getStyle() : Typeface.NORMAL;
            v.setTypeface(base, estilo);
        }
    }

    /** Aplica Tipo Metro con un estilo explícito (Typeface.NORMAL/BOLD). */
    public static void aplicar(TextView v, int estilo) {
        if (v == null) return;
        v.setTypeface(metro(v.getContext()), estilo);
    }

    /**
     * Renderiza un texto corto (nombre de estación) en Tipo Metro a un Bitmap.
     * Útil para notificaciones (RemoteViews) donde no se puede fijar la tipografía;
     * el color es fijo porque el fondo de esas tarjetas es blanco.
     */
    public static Bitmap render(Context c, String texto, float spSize, int color, boolean negrita) {
        return render(c, texto, spSize, color, negrita, metro(c));
    }

    /** Igual que {@link #render}, pero elige la tipografía según línea (ver {@link #fuenteEstacion}):
     *  usado en la notificación de recorrido, para que la próxima estación de Mexibús imite su
     *  señalética real en vez de Tipo Metro siempre. */
    public static Bitmap render(Context c, String texto, float spSize, int color, boolean negrita, int linea) {
        return render(c, texto, spSize, color, negrita, fuenteEstacion(c, linea));
    }

    private static Bitmap render(Context c, String texto, float spSize, int color, boolean negrita, Typeface tf) {
        if (texto == null || texto.isEmpty()) return null;
        if (texto.length() > 34) texto = texto.substring(0, 33) + "…";
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(negrita ? Typeface.create(tf, Typeface.BOLD) : tf);
        p.setColor(color);
        float px = spSize * c.getResources().getDisplayMetrics().scaledDensity;
        p.setTextSize(px);
        Paint.FontMetrics fm = p.getFontMetrics();
        int w = (int) Math.ceil(p.measureText(texto));
        int h = (int) Math.ceil(fm.bottom - fm.top);
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawText(texto, 0, -fm.top, p);
        return bmp;
    }

    /**
     * Renderiza varias líneas de texto en Tipo Metro a un solo Bitmap (para notificaciones).
     * Cada línea tiene su tamaño (sp), negrita y color.
     */
    public static Bitmap renderBloque(Context c, String[] textos, float[] sp, boolean[] bold, int[] color) {
        if (textos == null || textos.length == 0) return null;
        float d = c.getResources().getDisplayMetrics().scaledDensity;
        Typeface tf = metro(c);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ancho = 1, alto = 0;
        int[] hLinea = new int[textos.length];
        int[] baseY = new int[textos.length];
        for (int i = 0; i < textos.length; i++) {
            p.setTypeface(bold[i] ? Typeface.create(tf, Typeface.BOLD) : tf);
            p.setTextSize(sp[i] * d);
            Paint.FontMetrics fm = p.getFontMetrics();
            int w = (int) Math.ceil(p.measureText(textos[i] != null ? textos[i] : ""));
            int h = (int) Math.ceil(fm.bottom - fm.top);
            hLinea[i] = h;
            baseY[i] = alto + (int) Math.ceil(-fm.top);
            ancho = Math.max(ancho, w);
            alto += h + (int) (2 * d);
        }
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, ancho), Math.max(1, alto), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        for (int i = 0; i < textos.length; i++) {
            p.setTypeface(bold[i] ? Typeface.create(tf, Typeface.BOLD) : tf);
            p.setTextSize(sp[i] * d);
            p.setColor(color[i]);
            cv.drawText(textos[i] != null ? textos[i] : "", 0, baseY[i], p);
        }
        return bmp;
    }

    /** Logo de línea: cuadro redondeado con el color oficial y el número en Tipo Metro blanco. */
    public static Bitmap logoLinea(Context c, int colorLinea, String numero) {
        float d = c.getResources().getDisplayMetrics().density;
        int lado = (int) (48 * d);
        Bitmap bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint fondo = new Paint(Paint.ANTI_ALIAS_FLAG);
        fondo.setColor(colorLinea);
        float r = 10 * d;
        float sw = 2 * d;   // grosor del contorno
        // Relleno con el color de la línea (inset por medio trazo para que el borde quepa completo).
        cv.drawRoundRect(sw / 2f, sw / 2f, lado - sw / 2f, lado - sw / 2f, r, r, fondo);
        // Contorno negro alrededor del icono.
        Paint borde = new Paint(Paint.ANTI_ALIAS_FLAG);
        borde.setStyle(Paint.Style.STROKE);
        borde.setColor(0xFF000000);
        borde.setStrokeWidth(sw);
        cv.drawRoundRect(sw / 2f, sw / 2f, lado - sw / 2f, lado - sw / 2f, r, r, borde);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create(metro(c), Typeface.BOLD));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(lado * 0.62f);
        float y = lado / 2f - (tp.descent() + tp.ascent()) / 2f;
        cv.drawText(numero != null ? numero : "", lado / 2f, y, tp);
        return bmp;
    }

    /**
     * Ícono PROPIO de la línea para notificaciones: Metrobús {@code linea_N}; Mexibús
     * {@code mexibus_0N}/{@code mexibus_ant_0N} (troncal/ramal); Mexicable {@code mexicable_0N}
     * (logo propio por línea: L1/L2 en operación, L3 en construcción con logo provisional).
     * Respaldo: el badge de texto {@link #logoLinea}. Usado por MensajesService y
     * ManifestacionesService.
     */
    public static Bitmap bitmapLineaLogo(Context c, int linea, int color) {
        int id = 0;
        if (linea < 100) {                       // Metrobús
            id = idDrawable(c, "linea_" + linea);
        } else if (linea < 200) {                // Mexibús
            String suf = (linea >= 111 && linea <= 113) ? "0" + (linea - 110) + "a"
                    : "0" + (linea % 100);
            if (!Modos.iconosNuevos(c)) id = idDrawable(c, "mexibus_ant_" + suf);   // antiguo
            if (id == 0) id = idDrawable(c, "mexibus_" + suf);                        // nuevo / respaldo
        } else {                                 // Mexicable
            id = idDrawable(c, "mexicable_0" + (linea - 200));   // 201->mexicable_01, 202->_02, 203->_03
            if (id == 0) id = Modos.iconosNuevos(c) ? R.drawable.logo_mexicable_nuevo : R.drawable.mexicable_01_0;
        }
        if (id != 0) {
            Bitmap b = android.graphics.BitmapFactory.decodeResource(c.getResources(), id);
            if (b != null) return b;
        }
        return logoLinea(c, color, linea < 100 ? String.valueOf(linea) : "");
    }

    private static int idDrawable(Context c, String nombre) {
        return c.getResources().getIdentifier(nombre, "drawable", c.getPackageName());
    }

    /** Etiqueta para excluir un TextView/subárbol (textos largos, descripciones). */
    public static final String TAG_LARGO = "largo";

    /**
     * Aplica Tipo Metro a todos los TextView del árbol, EXCEPTO los marcados con
     * android:tag="largo" (y sus subárboles): así se evitan textos largos y descripciones.
     */
    public static void aplicarArbol(View root) {
        if (root == null) return;
        Object tag = root.getTag();
        if (tag instanceof String && TAG_LARGO.equals(tag)) return;   // salta este subárbol
        if (root instanceof TextView) {
            aplicar((TextView) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) aplicarArbol(g.getChildAt(i));
        }
    }
}
