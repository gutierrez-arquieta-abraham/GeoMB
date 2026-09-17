package com.memegrados.GeoMB;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tarjetas de afectación dentro de la app, con el formato de la imagen (todo en Tipo Metro):
 * Estado / Estación / DIRECCIÓN / valor / INFORMACIÓN ADICIONAL / valor + logo de línea.
 */
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

        h.estado.setText(a.estado.isEmpty()
                ? h.itemView.getContext().getString(R.string.manifest_generico) : a.estado);
        par(h.lblEst, h.lugar, a.lugar);   // "Estaciones afectadas" + estaciones
        par(h.lblDir, h.direccion, a.direccion);
        par(h.lblInfo, h.info, a.info);

        // Todo el texto en Tipo Metro
        Tipografia.aplicar(h.estado, h.lugar, h.direccion, h.info);
        Tipografia.aplicar(h.lblEst);
        Tipografia.aplicar(h.lblDir);
        Tipografia.aplicar(h.lblInfo);

        // Chips con las estaciones puntuales mencionadas en 'lugar' (más precisos que leerlas
        // dentro del párrafo): 'lugar' puede traer un rango "A - B", varias por "y"/coma, o varias
        // afectaciones combinadas por "/". Se muestra el nombre TAL COMO lo reportan (limpio de
        // "MXB "/paréntesis si hace match exacto con una estación real de esa línea) — sin adivinar
        // estaciones intermedias de un rango, que en la lista base de la línea no van en orden físico.
        Linea linea = a.lineaNum > 0 ? GtfsRepository.porNumero(h.itemView.getContext(), a.lineaNum) : null;
        List<String> estaciones = estacionesDe(linea, a.lugar);
        h.estacionesRow.removeAllViews();
        if (estaciones.isEmpty()) {
            h.estacionesScroll.setVisibility(View.GONE);
        } else {
            int color = linea != null ? linea.color : 0xFFD40D0D;
            float dens = h.itemView.getResources().getDisplayMetrics().density;
            for (String nombre : estaciones) {
                TextView chip = new TextView(h.itemView.getContext());
                chip.setText(nombre);
                chip.setTextSize(11);
                chip.setTextColor(color);
                chip.setTypeface(Tipografia.metro(h.itemView.getContext()));
                chip.setBackgroundResource(R.drawable.bg_pill_soft);
                int padH = Math.round(9 * dens), padV = Math.round(3 * dens);
                chip.setPadding(padH, padV, padH, padV);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(Math.round(6 * dens));
                chip.setLayoutParams(lp);
                h.estacionesRow.addView(chip);
            }
            h.estacionesScroll.setVisibility(View.VISIBLE);
        }

        // Logo de línea: cuadro redondeado con color oficial + número (Tipo Metro)
        if (a.lineaNum > 0) {
            Linea l = linea;
            int color = l != null ? l.color : 0xFFD40D0D;
            float dens = h.itemView.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(10 * dens);
            bg.setColor(color);
            bg.setStroke(Math.round(2 * dens), 0xFF000000);   // contorno negro
            h.logo.setBackground(bg);
            // Mexibús suma 100 al número interno (101=L1…113=L3A) para distinguirlo de Metrobús
            // (1-7): mostrar a.lineaNum tal cual ponía "103" en vez de "3" para Mexibús L3.
            h.logo.setText(Planificador.etiquetaLineaCortaPub(a.lineaNum));
            h.logo.setTypeface(Tipografia.metro(h.itemView.getContext()), Typeface.BOLD);
            h.logo.setVisibility(View.VISIBLE);
        } else {
            h.logo.setVisibility(View.GONE);
        }
    }

    private static void par(TextView label, TextView valor, String s) {
        int v = (s != null && !s.isEmpty()) ? View.VISIBLE : View.GONE;
        label.setVisibility(v);
        valor.setVisibility(v);
        if (v == View.VISIBLE) valor.setText(s);
    }

    /** Nombres de estación sueltos de 'lugar': separa un rango "A - B", varias por "y"/coma, o
     *  varias afectaciones combinadas por "/". Cada token se limpia (sin "MXB "/paréntesis) SOLO si
     *  hace match EXACTO con una estación real de esa línea; si no, se muestra tal cual vino — no se
     *  "adivinan" estaciones intermedias de un rango (la lista base de la línea no va en orden físico). */
    private static List<String> estacionesDe(Linea l, String lugar) {
        Set<String> out = new LinkedHashSet<>();
        if (lugar == null || lugar.isEmpty()) return new ArrayList<>(out);
        for (String seg : lugar.split("\\s*/\\s*")) {
            for (String tok : seg.split("(?i)\\s*-\\s*|\\s*,\\s*|\\s+y\\s+")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                out.add(l != null ? nombreReal(l, t) : t);
            }
        }
        return new ArrayList<>(out);
    }

    private static String nombreReal(Linea l, String texto) {
        String q = Planificador.norm(Planificador.sinMxb(texto));
        for (Estacion e : l.estaciones) {
            if (Planificador.norm(Planificador.sinMxb(e.nombre)).equals(q)) {
                String s = Planificador.sinMxb(e.nombre);
                int par = s.indexOf('(');
                return par >= 0 ? s.substring(0, par).trim() : s;
            }
        }
        return texto;
    }

    @Override
    public int getItemCount() { return datos.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView estado, lblEst, lugar, lblDir, direccion, lblInfo, info, logo;
        final View estacionesScroll;
        final LinearLayout estacionesRow;
        VH(@NonNull View v) {
            super(v);
            estado = v.findViewById(R.id.ia_estado);
            lblEst = v.findViewById(R.id.ia_lbl_est);
            lugar = v.findViewById(R.id.ia_lugar);
            lblDir = v.findViewById(R.id.ia_lbl_dir);
            direccion = v.findViewById(R.id.ia_direccion);
            lblInfo = v.findViewById(R.id.ia_lbl_info);
            info = v.findViewById(R.id.ia_info);
            logo = v.findViewById(R.id.ia_logo);
            estacionesScroll = v.findViewById(R.id.ia_estaciones_scroll);
            estacionesRow = v.findViewById(R.id.ia_estaciones_row);
        }
    }
}
