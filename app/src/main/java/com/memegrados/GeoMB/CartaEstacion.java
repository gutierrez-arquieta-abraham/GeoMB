package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

/**
 * Tarjeta de resultado de una ESTACIÓN (view_carta_estacion.xml): pictograma + nombre + línea.
 * La usa MapFragment al tocar un marcador de estación en el mapa, dándole a las estaciones el
 * mismo trato de "carta" que ya tenían las unidades ({@link CartaUnidad}) -- antes solo se veía
 * el tooltip genérico de 2 líneas (título + snippet) de Google Maps.
 */
// ============================================================
// CLASE    : CartaEstacion   (subclase Vistas)
// PROYECTO : GeoMB
// ============================================================
public final class CartaEstacion {

    private CartaEstacion() {}

    /** Referencias a las vistas de view_carta_estacion.xml, resueltas una sola vez al inflar. */
    public static final class Vistas {
        public final MaterialCardView card;
        public final ImageView imgEstacion;
        public final TextView txtNombre, txtLinea;

        public Vistas(View raiz) {
            card = raiz.findViewById(R.id.card_estacion);
            imgEstacion = raiz.findViewById(R.id.img_estacion);
            txtNombre = raiz.findViewById(R.id.txt_estacion_nombre);
            txtLinea = raiz.findViewById(R.id.txt_estacion_linea);
        }
    }

    /** Rellena la tarjeta para la estación {@code e}, de la línea {@code linea} (color {@code color}). */
    public static void bind(Context ctx, Vistas v, Estacion e, int linea, int color) {
        v.txtNombre.setText(Planificador.sinMxb(e.nombre));
        Linea l = GtfsRepository.porNumero(ctx, linea);
        String nombreLinea = l != null ? l.nombre : "";
        // Número PÚBLICO de la línea (Metrobús 1..7 tal cual; Mexibús/Mexicable sin el prefijo
        // interno: 104→"4", 111→"1A", 202→"2"), no el numero interno crudo (p. ej. "Línea 104").
        // linea_formato_txt recibe texto (%s), no dígito (%d): los ramales dan letra ("1A").
        v.txtLinea.setText(ctx.getString(R.string.linea_formato_txt, Planificador.etiquetaLineaCortaPub(linea))
                + (nombreLinea.isEmpty() ? "" : " · " + nombreLinea));

        GradientDrawable fondo = new GradientDrawable();
        fondo.setShape(GradientDrawable.OVAL);
        fondo.setColor(color);
        v.imgEstacion.setBackground(fondo);

        Bitmap pic = (e.icono != null && !e.icono.isEmpty())
                ? Iconos.pictograma(ctx, e.icono, Math.round(28 * ctx.getResources().getDisplayMetrics().density))
                : null;
        v.imgEstacion.setImageBitmap(pic);   // null: se queda solo el círculo de color (sin pictograma)

        v.card.setVisibility(View.VISIBLE);
    }
}
