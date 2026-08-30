package com.memegrados.GeoMB;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Utilidades para cargar pictogramas de estación de forma eficiente (reducción de muestreo),
 * centralizando el bloque que antes se repetía en el mapa, el planificador y el recorrido.
 */
public final class Iconos {

    private Iconos() {}

    /** Decodifica el drawable REDUCIDO (evita OOM) y lo escala a un bitmap px×px, o null. */
    public static Bitmap escalado(Resources res, int id, int px) {
        try {
            BitmapFactory.Options medir = new BitmapFactory.Options();
            medir.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, id, medir);
            int s = 1;
            while (medir.outWidth / s > px * 2 || medir.outHeight / s > px * 2) s *= 2;
            BitmapFactory.Options opc = new BitmapFactory.Options();
            opc.inSampleSize = s;
            Bitmap src = BitmapFactory.decodeResource(res, id, opc);
            if (src == null) return null;
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(0, 0, px, px),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            src.recycle();
            return bmp;
        } catch (Throwable t) {
            return null;   // icono corrupto o sin memoria
        }
    }

    /** Pictograma de estación por nombre de drawable, escalado a px×px, o null si no existe. */
    public static Bitmap pictograma(Context ctx, String nombre, int px) {
        if (nombre == null || nombre.isEmpty()) return null;
        // Modo "iconos antiguos": Mexibús usa su iconografía antigua (mexibus_ant_*); Mexicable = punto.
        if (!Modos.iconosNuevos(ctx)) {
            if (nombre.startsWith("mexicable_")) return null;   // Mexicable antiguo: punto
            if (nombre.startsWith("mexibus_") && !nombre.startsWith("mexibus_ant_")) {
                String ant = "mexibus_ant_" + nombre.substring("mexibus_".length());
                int aid = ctx.getResources().getIdentifier(ant, "drawable", ctx.getPackageName());
                if (aid == 0) return null;   // sin icono antiguo -> punto
                nombre = ant;
            }
            // Metrobús (ic_est_*) conserva su pictograma en ambos modos.
        }
        int id = ctx.getResources().getIdentifier(nombre, "drawable", ctx.getPackageName());
        return id != 0 ? escalado(ctx.getResources(), id, px) : null;
    }
}
