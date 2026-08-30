package com.memegrados.GeoMB;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

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

        // Logo de línea: cuadro redondeado con color oficial + número (Tipo Metro)
        if (a.lineaNum > 0) {
            Linea l = GtfsRepository.porNumero(h.itemView.getContext(), a.lineaNum);
            int color = l != null ? l.color : 0xFFD40D0D;
            float dens = h.itemView.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(10 * dens);
            bg.setColor(color);
            bg.setStroke(Math.round(2 * dens), 0xFF000000);   // contorno negro
            h.logo.setBackground(bg);
            h.logo.setText(String.valueOf(a.lineaNum));
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

    @Override
    public int getItemCount() { return datos.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView estado, lblEst, lugar, lblDir, direccion, lblInfo, info, logo;
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
        }
    }
}
