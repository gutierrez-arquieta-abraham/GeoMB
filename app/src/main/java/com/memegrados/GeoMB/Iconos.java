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

    // Cachés para evitar rehacer trabajo caro en el hilo principal (getIdentifier + decodeResource):
    // el mismo pictograma se pide para el marcador del mapa Y para la lista de la descripción de ruta,
    // y en cada redibujo. Sin caché, una ruta larga (~40 estaciones) bloqueaba el hilo → ANR.
    private static final java.util.Map<String, Integer> IDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Bitmap> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static int resId(Context ctx, String nombre) {
        Integer c = IDS.get(nombre);
        if (c != null) return c;
        int id = ctx.getResources().getIdentifier(nombre, "drawable", ctx.getPackageName());
        IDS.put(nombre, id);
        return id;
    }

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

    /** Pictograma de estación por nombre de drawable, escalado a px×px, o null si no existe. Cacheado. */
    public static Bitmap pictograma(Context ctx, String nombre, int px) {
        if (nombre == null || nombre.isEmpty()) return null;
        boolean nuevos = Modos.iconosNuevos(ctx);
        String key = nombre + "|" + px + "|" + (nuevos ? 1 : 0);
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        String dw = nombre;
        // Modo "iconos antiguos": Mexibús usa su iconografía antigua (mexibus_ant_*); Mexicable = punto.
        if (!nuevos) {
            if (dw.startsWith("mexicable_")) return null;   // Mexicable antiguo: punto
            if (dw.startsWith("mexibus_") && !dw.startsWith("mexibus_ant_")) {
                String ant = "mexibus_ant_" + dw.substring("mexibus_".length());
                if (resId(ctx, ant) == 0) return null;   // sin icono antiguo -> punto
                dw = ant;
            }
            // Metrobús (ic_est_*) conserva su pictograma en ambos modos.
        }
        int id = resId(ctx, dw);
        Bitmap bmp = id != 0 ? escalado(ctx.getResources(), id, px) : null;
        if (bmp != null) CACHE.put(key, bmp);
        return bmp;
    }
}
