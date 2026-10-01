package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Carta flotante de desambiguación ("¿A qué estación te refieres?"): compartida entre
 * {@link PlanificadorFragment} (al trazar una ruta) y {@link MapFragment} (buscador del mapa),
 * para que ambos usen el mismo trato visual (logo de línea + pictograma + etiqueta) en vez de
 * que uno tenga la carta ilustrada y el otro un AlertDialog de texto plano.
 */
public final class CartaDesambiguacion {

    private CartaDesambiguacion() {}

    public interface AlElegirGrupo { void run(List<Planificador.Match> grupo); }

    /** Opción de una carta flotante: logo (recurso o bitmap) + título + acción. {@code iconoLinea} es un
     *  ícono opcional a la IZQUIERDA (drawable de línea) que se muestra antes del ícono principal. */
    public static final class Opcion {
        final int iconoRes; final Bitmap iconoBmp; final Bitmap iconoLinea; final String titulo; final Runnable accion;
        int color = 0;   // acento del color de la línea (0 = sin color específico)
        public Opcion(int res, Bitmap bmp, String t, Runnable a) { this(res, bmp, null, t, a); }
        public Opcion(int res, Bitmap bmp, Bitmap lineaBmp, String t, Runnable a) {
            iconoRes = res; iconoBmp = bmp; iconoLinea = lineaBmp; titulo = t; accion = a;
        }
        public Opcion col(int c) { color = c; return this; }
    }

    /** "¿A qué estación te refieres?": una celda por estación física, con ícono/badge de línea y
     *  etiqueta "Nombre (Sistema Lx y Ly)". La usan PlanificadorFragment (al trazar) y MapFragment
     *  (buscador del mapa) para no duplicar el criterio ni el trato visual. */
    public static void elegirEstacionFisica(Context ctx, String titulo,
                                             List<List<Planificador.Match>> grupos, AlElegirGrupo cb) {
        List<List<Planificador.Match>> ordenados = new ArrayList<>(grupos);
        Collections.sort(ordenados, (g1, g2) -> {
            Planificador.Match r1 = repGrupo(g1), r2 = repGrupo(g2);
            int s = Integer.compare(sistemaDe(r1.linea), sistemaDe(r2.linea));
            return s != 0 ? s : Integer.compare(r1.linea, r2.linea);
        });
        int px = Math.round(40 * ctx.getResources().getDisplayMetrics().density);
        List<Opcion> ops = new ArrayList<>();
        for (List<Planificador.Match> g : ordenados) {
            Planificador.Match sel = repGrupo(g);
            Bitmap est = Iconos.pictograma(ctx, sel.icono, px);
            if (est == null) est = badgeLinea(ctx, colorLinea(ctx, sel.linea), Planificador.etiquetaLineaCortaPub(sel.linea));
            Bitmap linea = bmpLinea(ctx, sel.linea);
            ops.add(new Opcion(0, est, linea, etiquetaEstacion(ctx, g), () -> cb.run(g)).col(colorLinea(ctx, sel.linea)));
        }
        mostrar(ctx, titulo, ops);
    }

    /** Representante de un grupo: el match de línea más baja. */
    public static Planificador.Match repGrupo(List<Planificador.Match> g) {
        Planificador.Match rep = g.get(0);
        for (Planificador.Match m : g) if (m.linea < rep.linea) rep = m;
        return rep;
    }

    private static int sistemaDe(int n) { return n >= 200 ? 2 : (n >= 100 ? 1 : 0); }   // 0 Metrobús, 1 Mexibús, 2 Mexicable

    /** Etiqueta de una estación física: "Nombre (Sistema Lx y Ly)" con sus líneas distintas. */
    public static String etiquetaEstacion(Context ctx, List<Planificador.Match> g) {
        Planificador.Match rep = repGrupo(g);
        TreeSet<Integer> orden = new TreeSet<>();
        for (Planificador.Match m : g) orden.add(m.linea);
        LinkedHashSet<String> ets = new LinkedHashSet<>();
        for (int ln : orden) ets.add("L" + Planificador.etiquetaLineaCortaPub(ln));   // "L2", "L2A" (exprés colapsa con su troncal)
        List<String> ls = new ArrayList<>(ets);
        StringBuilder lin = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) lin.append(ls.get(i));
            else if (i == ls.size() - 1) lin.append(" y ").append(ls.get(i));
            else lin.append(", ").append(ls.get(i));
        }
        return Planificador.sinMxb(rep.nombre) + " (" + nombreSistema(ctx, rep.linea) + " " + lin + ")";
    }

    /** Nombre del sistema por número de línea. */
    public static String nombreSistema(Context ctx, int n) {
        if (n >= 200) return ctx.getString(R.string.desamb_sist_mexicable);
        if (n >= 100) return ctx.getString(R.string.desamb_sist_mexibus);
        return ctx.getString(R.string.desamb_sist_metrobus);
    }

    /** Ícono de línea para la card: Metrobús = {@code linea_1..7}; Mexibús = logo de línea
     *  ({@code mexibus_0N} nuevo / {@code mexibus_ant_0N} antiguo, troncales I–IV); si no hay, badge de color. */
    public static Bitmap bmpLinea(Context ctx, int linea) {
        int id = 0;
        if (linea < 100) {                       // Metrobús
            id = drawableId(ctx, "linea_" + linea);
        } else if (linea < 200) {                // Mexibús
            String suf = sufijoMxb(linea);
            if (suf != null) {
                if (!Modos.iconosNuevos(ctx)) id = drawableId(ctx, "mexibus_ant_" + suf);  // antiguo (SVG troncales)
                if (id == 0) id = drawableId(ctx, "mexibus_" + suf);                       // nuevo / respaldo
            }
        }
        if (id != 0) {
            Bitmap b = android.graphics.BitmapFactory.decodeResource(ctx.getResources(), id);
            if (b != null) return b;
        }
        return badgeLinea(ctx, colorLinea(ctx, linea), Planificador.etiquetaLineaCortaPub(linea));
    }

    private static int drawableId(Context ctx, String nombre) {
        return ctx.getResources().getIdentifier(nombre, "drawable", ctx.getPackageName());
    }

    /** Sufijo del drawable de logo Mexibús: troncal 101→"01", ramal 111→"01a", exprés 124→"04" (logo troncal). */
    private static String sufijoMxb(int n) {
        if (n >= 121 && n <= 124) return "0" + (n - 120);
        if (n >= 111 && n <= 113) return "0" + (n - 110) + "a";
        if (n >= 101 && n <= 104) return "0" + (n - 100);
        return null;
    }

    /** Color de una línea (para el badge); si no se encuentra, gris. */
    public static int colorLinea(Context ctx, int linea) {
        Linea l = GtfsRepository.porNumero(ctx, linea);
        return l != null ? l.color : 0xFF757575;
    }

    /** Badge circular del color de la línea con su número/etiqueta en blanco. */
    public static Bitmap badgeLinea(Context ctx, int color, String texto) {
        int px = Math.round(40 * ctx.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); c.drawCircle(px / 2f, px / 2f, px * 0.46f, p);
        p.setColor(0xFFFFFFFF); p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(px * (texto.length() > 1 ? 0.34f : 0.5f));
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(texto, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }

    /** Muestra una carta flotante HORIZONTAL: cada opción es una celda (logo arriba, título abajo). */
    public static void mostrar(Context ctx, String titulo, List<Opcion> ops) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout fila = new LinearLayout(ctx);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER);
        int pad = Math.round(12 * d);
        fila.setPadding(pad, pad, pad, pad);
        // Scroll horizontal: si hay muchas opciones (o con logo de línea + ícono de estación) no caben,
        // el usuario desliza en vez de que se corten.
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(fila);
        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle(titulo).setView(scroll).setCancelable(true).create();
        int rojo = ContextCompat.getColor(ctx, R.color.mb_red);
        for (Opcion o : ops) {
            int acento = o.color != 0 ? o.color : rojo;   // acento del color de la línea (o rojo por defecto)

            // Tarjeta contenedora con esquinas redondeadas, elevación y franja de acento arriba.
            MaterialCardView card = new MaterialCardView(ctx);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(Math.round(6 * d), Math.round(4 * d), Math.round(6 * d), Math.round(4 * d));
            card.setLayoutParams(clp);
            card.setRadius(16 * d);
            card.setCardElevation(3 * d);
            card.setStrokeWidth(0);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout envoltura = new LinearLayout(ctx);
            envoltura.setOrientation(LinearLayout.VERTICAL);

            // Franja de acento superior (color de la línea).
            View franja = new View(ctx);
            franja.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(5 * d)));
            franja.setBackgroundColor(acento);
            envoltura.addView(franja);

            LinearLayout celda = new LinearLayout(ctx);
            celda.setOrientation(LinearLayout.VERTICAL);
            celda.setGravity(Gravity.CENTER_HORIZONTAL);
            int cp = Math.round(12 * d);
            celda.setPadding(cp, cp, cp, cp);
            celda.setMinimumWidth(Math.round(96 * d));
            int sz = Math.round(48 * d);
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (o.iconoBmp != null) iv.setImageBitmap(o.iconoBmp);
            else if (o.iconoRes != 0) iv.setImageResource(o.iconoRes);
            if (o.iconoLinea != null) {
                // Fila horizontal: [drawable de línea] [ícono de estación]
                LinearLayout iconos = new LinearLayout(ctx);
                iconos.setOrientation(LinearLayout.HORIZONTAL);
                iconos.setGravity(Gravity.CENTER_VERTICAL);
                ImageView ivL = new ImageView(ctx);
                int szl = Math.round(30 * d);
                LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(szl, szl);
                lpL.rightMargin = Math.round(6 * d);
                ivL.setLayoutParams(lpL);
                ivL.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ivL.setImageBitmap(o.iconoLinea);
                iconos.addView(ivL);
                iconos.addView(iv);
                celda.addView(iconos);
            } else {
                celda.addView(iv);
            }
            TextView t = new TextView(ctx);
            t.setText(o.titulo);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Math.round(6 * d), 0, 0);
            t.setTextSize(13f);
            celda.addView(t);   // el/los ícono(s) ya se agregaron arriba
            envoltura.addView(celda);
            card.addView(envoltura);
            card.setOnClickListener(v -> { dlg.dismiss(); o.accion.run(); });
            fila.addView(card);
        }
        dlg.show();
    }
}
