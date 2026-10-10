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
        public final TextView txtNombre, txtLinea, txtAfectacion;

        public Vistas(View raiz) {
            card = raiz.findViewById(R.id.card_estacion);
            imgEstacion = raiz.findViewById(R.id.img_estacion);
            txtNombre = raiz.findViewById(R.id.txt_estacion_nombre);
            txtLinea = raiz.findViewById(R.id.txt_estacion_linea);
            txtAfectacion = raiz.findViewById(R.id.txt_estacion_afectacion);
        }
    }

    /** Rellena la tarjeta para la estación {@code e}, de la línea {@code linea} (color {@code color}). */
    public static void bind(Context ctx, Vistas v, Estacion e, int linea, int color) {
        bind(ctx, v, e, linea, color, false);
    }

    /** Igual que {@link #bind(Context, Vistas, Estacion, int, int)}, pero con {@code transbordo}:
     *  true = punto real de una ruta mixta (MapFragment.EstMapa.transbordo) -- el pictograma se pinta
     *  en diagonal entre L2/L7, mismo criterio que MapFragment.iconoEstacionOTransbordo(), para que la
     *  tarjeta no muestre un ícono sólido distinto al que ya se ve en el marcador del mapa (antes esta
     *  tarjeta tenía su propia ruta de dibujo, nunca enterada de la diagonal). */
    public static void bind(Context ctx, Vistas v, Estacion e, int linea, int color, boolean transbordo) {
        v.txtNombre.setText(Planificador.sinMxb(e.nombre));
        Linea l = GtfsRepository.porNumero(ctx, linea);
        String nombreLinea = l != null ? l.nombre : "";
        String texto;
        if (linea < 100) {
            // Metrobús: "Línea N · nombre-de-la-ruta". Número PÚBLICO (no el interno crudo).
            texto = ctx.getString(R.string.linea_formato_txt, Planificador.etiquetaLineaCortaPub(linea))
                    + (nombreLinea.isEmpty() ? "" : " · " + nombreLinea);
        } else {
            // Mexibús/Mexicable: su nombre ya es autodescriptivo ("Mexibús L4"), no se antepone
            // "Línea 104" (mismo criterio que LinesAdapter); además muestra el par de terminales
            // oficial ("UMB Tecámac – La Raza") en vez de solo repetir "Mexibús L4".
            String par = Planificador.terminalesMexibusPar(linea);
            texto = nombreLinea + (par != null ? " · " + par : "");
        }
        v.txtLinea.setText(texto);
        v.txtLinea.setSelected(true);   // arranca el marquee (panel LED) cuando el texto no cabe

        Bitmap pic = (e.icono != null && !e.icono.isEmpty())
                ? Iconos.pictograma(ctx, e.icono, Math.round(44 * ctx.getResources().getDisplayMetrics().density))
                : null;
        if (pic != null && transbordo) {
            // Troncal L2 (Tacubaya/De la Salle): L7 arriba-izq / L2 abajo-der; los 3 puntos propios de
            // H72 (su nombre NO está en ese set, p. ej. "Alameda Tacubaya"): orden invertido -- igual
            // que en el mapa general (MapFragment.iconoEstacionOTransbordo()).
            boolean esTroncalL2 = Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm(e.nombre));
            int arriba = colorLinea(ctx, esTroncalL2 ? 7 : 2);
            int abajo = colorLinea(ctx, esTroncalL2 ? 2 : 7);
            pic = Iconos.recoloreaFondoDiagonal(pic, arriba, abajo);
        }
        if (pic != null) {
            // Con pictograma: sin círculo de fondo, el icono se ve grande y a tamaño completo.
            v.imgEstacion.setBackground(null);
        } else {
            // Sin pictograma: círculo del color de la línea como respaldo (no queda en blanco).
            GradientDrawable fondo = new GradientDrawable();
            fondo.setShape(GradientDrawable.OVAL);
            fondo.setColor(color);
            v.imgEstacion.setBackground(fondo);
        }
        v.imgEstacion.setImageBitmap(pic);

        // Aviso de afectación (manifestación, circuito de emergencia, mantenimiento…), si esta
        // estación tiene una activa en ESTA línea (ver Manifestaciones.afectacionEstacion()).
        Manifestaciones.Afectacion af = Manifestaciones.afectacionEstacion(linea, Planificador.norm(e.nombre));
        if (af != null && !af.estado.isEmpty()) {
            String textoAfect = "⚠ " + af.estado;
            // Detalle adicional (p. ej. "Sin servicio en ambos sentidos"): antes se perdía -- la
            // tarjeta solo mostraba el encabezado (af.estado), aunque el push de esa misma
            // afectación (MensajesService) SÍ incluye este detalle. txt_estacion_afectacion ya
            // admite 2 líneas (maxLines="2").
            if (af.info != null && !af.info.isEmpty()
                    && !Planificador.norm(af.estado).contains(Planificador.norm(af.info))) {
                textoAfect += "\n" + af.info;
            }
            v.txtAfectacion.setText(textoAfect);
            v.txtAfectacion.setVisibility(View.VISIBLE);
        } else {
            v.txtAfectacion.setVisibility(View.GONE);
        }

        v.card.setVisibility(View.VISIBLE);
    }

    /** Color de una línea por su número (gris si no se encuentra). */
    private static int colorLinea(Context ctx, int linea) {
        Linea l = GtfsRepository.porNumero(ctx, linea);
        return l != null ? l.color : 0xFF757575;
    }
}
