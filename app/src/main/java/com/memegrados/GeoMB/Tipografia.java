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
public final class Tipografia {

    private static volatile Typeface metro;

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
        if (texto == null || texto.isEmpty()) return null;
        if (texto.length() > 34) texto = texto.substring(0, 33) + "…";
        Typeface tf = metro(c);
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
