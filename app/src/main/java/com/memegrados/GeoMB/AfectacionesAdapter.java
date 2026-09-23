package com.memegrados.GeoMB;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tarjetas de afectación dentro de la app (todo en Tipo Metro): un banner con el color oficial
 * de la línea arriba, y abajo filas con icono + valor + etiqueta (Estación/tramo, Dirección,
 * Incidencia) más el texto libre de información adicional.
 */
// ============================================================
// CLASE    : AfectacionesAdapter   (extends RecyclerView.Adapter)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// ADAPTADOR de las TARJETAS de afectación dentro de la app (todo en tipografía
// Tipo Metro): banner de línea + filas con icono (estación/dirección/incidencia)
// + información adicional. Muestra el panel de elevadores/otras afectaciones
// (no la tabla de estado).
// ============================================================
public class AfectacionesAdapter extends RecyclerView.Adapter<AfectacionesAdapter.VH> {

    private final List<Manifestaciones.Afectacion> datos = new ArrayList<>();

    public void set(List<Manifestaciones.Afectacion> lista) {
        datos.clear();
        if (lista != null) datos.addAll(lista);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_afectacion, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Manifestaciones.Afectacion a = datos.get(pos);
        android.content.Context ctx = h.itemView.getContext();
        float dens = ctx.getResources().getDisplayMetrics().density;

        h.estado.setText(a.estado.isEmpty() ? ctx.getString(R.string.manifest_generico) : a.estado);
        par(h.filaDir, h.direccion, a.direccion);

        // Todo el texto en Tipo Metro
        Tipografia.aplicar(h.estado, h.lugar, h.direccion);
        if (a.info != null && !a.info.isEmpty()) {
            h.info.setText(a.info);
            h.info.setVisibility(View.VISIBLE);
            Tipografia.aplicar(h.info);
        } else {
            h.info.setVisibility(View.GONE);
        }

        // Chips con pictograma + nombre de cada estación puntual mencionada en 'lugar' (más
        // precisas que leerlas dentro del párrafo): 'lugar' puede traer un rango "A - B", varias
        // por "y"/coma, o varias afectaciones combinadas por "/". Sin adivinar estaciones
        // intermedias de un rango, que en la lista base de la línea no van en orden físico.
        Linea linea = a.lineaNum > 0 ? GtfsRepository.porNumero(ctx, a.lineaNum) : null;
        int color = linea != null ? linea.color : 0xFFD40D0D;
        List<Estacion> estaciones = estacionesDe(linea, a.lugar);
        h.estacionesRow.removeAllViews();
        h.lugar.setText(estaciones.isEmpty() ? a.lugar : nombresDe(estaciones));
        for (Estacion e : estaciones) {
            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_pill_soft);
            int padH = Math.round(9 * dens), padV = Math.round(3 * dens);
            chip.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(6 * dens));
            chip.setLayoutParams(lp);

            Bitmap pic = e.icono != null && !e.icono.isEmpty() ? Iconos.pictograma(ctx, e.icono, Math.round(16 * dens)) : null;
            if (pic != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageBitmap(pic);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                        Math.round(16 * dens), Math.round(16 * dens));
                ivLp.setMarginEnd(Math.round(5 * dens));
                iv.setLayoutParams(ivLp);
                chip.addView(iv);
            }
            TextView txt = new TextView(ctx);
            txt.setText(nombreCorto(e.nombre));
            txt.setTextSize(11);
            txt.setTextColor(color);
            txt.setTypeface(Tipografia.metro(ctx));
            chip.addView(txt);

            h.estacionesRow.addView(chip);
        }
        h.estacionesScroll.setVisibility(estaciones.isEmpty() ? View.GONE : View.VISIBLE);
        h.lugar.setVisibility(estaciones.isEmpty() && (a.lugar == null || a.lugar.isEmpty()) ? View.GONE : View.VISIBLE);

        // Banner de línea: color oficial (esquinas superiores redondeadas, a juego con la tarjeta) + número.
        GradientDrawable banner = new GradientDrawable();
        banner.setShape(GradientDrawable.RECTANGLE);
        float r = 14 * dens;
        banner.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        banner.setColor(color);
        h.banner.setBackground(banner);
        if (a.lineaNum > 0) {
            // Mexibús suma 100 al número interno (101=L1…113=L3A) para distinguirlo de Metrobús
            // (1-7): mostrar a.lineaNum tal cual ponía "103" en vez de "3" para Mexibús L3.
            h.logo.setText(Planificador.etiquetaLineaCortaPub(a.lineaNum));
            h.logo.setTypeface(Tipografia.metro(ctx), Typeface.BOLD);
            h.logo.setVisibility(View.VISIBLE);
        } else {
            h.logo.setVisibility(View.GONE);
        }
        h.lineaNombre.setText(linea != null ? linea.nombre : "");
        h.lineaNombre.setTypeface(Tipografia.metro(ctx), Typeface.BOLD);
    }

    private static void par(View fila, TextView valor, String s) {
        int v = (s != null && !s.isEmpty()) ? View.VISIBLE : View.GONE;
        fila.setVisibility(v);
        if (v == View.VISIBLE) valor.setText(s);
    }

    /** Estaciones puntuales de 'lugar': separa un rango "A - B", varias por "y"/coma, o varias
     *  afectaciones combinadas por "/". Cada token resuelve a la {@link Estacion} real de esa
     *  línea SOLO si hace match exacto (para tomar su pictograma); si no hay match, se descarta
     *  del listado con icono (el texto crudo se sigue mostrando en 'lugar'). */
    private static List<Estacion> estacionesDe(Linea l, String lugar) {
        List<Estacion> out = new ArrayList<>();
        if (l == null || lugar == null || lugar.isEmpty()) return out;
        Set<String> vistos = new LinkedHashSet<>();
        for (String seg : lugar.split("\\s*/\\s*")) {
            for (String tok : seg.split("(?i)\\s*-\\s*|\\s*,\\s*|\\s+y\\s+")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                Estacion e = estacionReal(l, t);
                if (e != null && vistos.add(e.nombre)) out.add(e);
            }
        }
        return out;
    }

    private static Estacion estacionReal(Linea l, String texto) {
        String q = Planificador.norm(Planificador.sinMxb(texto));
        for (Estacion e : l.estaciones) {
            if (Planificador.norm(Planificador.sinMxb(e.nombre)).equals(q)) return e;
        }
        return null;
    }

    private static String nombreCorto(String nombre) {
        String s = Planificador.sinMxb(nombre);
        int par = s.indexOf('(');
        return par >= 0 ? s.substring(0, par).trim() : s;
    }

    private static String nombresDe(List<Estacion> estaciones) {
        StringBuilder sb = new StringBuilder();
        for (Estacion e : estaciones) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(nombreCorto(e.nombre));
        }
        return sb.toString();
    }

    @Override
    public int getItemCount() { return datos.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView estado, lugar, direccion, info, logo, lineaNombre;
        final View estacionesScroll, filaDir, banner;
        final LinearLayout estacionesRow;
        VH(@NonNull View v) {
            super(v);
            estado = v.findViewById(R.id.ia_estado);
            lugar = v.findViewById(R.id.ia_lugar);
            direccion = v.findViewById(R.id.ia_direccion);
            info = v.findViewById(R.id.ia_info);
            logo = v.findViewById(R.id.ia_logo);
            lineaNombre = v.findViewById(R.id.ia_linea_nombre);
            estacionesScroll = v.findViewById(R.id.ia_estaciones_scroll);
            estacionesRow = v.findViewById(R.id.ia_estaciones_row);
            filaDir = v.findViewById(R.id.ia_fila_dir);
            banner = v.findViewById(R.id.ia_banner);
        }
    }
}
