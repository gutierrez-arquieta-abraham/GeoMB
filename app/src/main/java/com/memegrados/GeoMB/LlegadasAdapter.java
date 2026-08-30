package com.memegrados.GeoMB;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Lista de próximas llegadas (unidad + tiempo/distancia estimados). */
public class LlegadasAdapter extends RecyclerView.Adapter<LlegadasAdapter.VH> {

    private final List<Llegadas.Prox> items = new ArrayList<>();

    public void set(List<Llegadas.Prox> nuevas) {
        items.clear();
        items.addAll(nuevas);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_llegada, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Llegadas.Prox p = items.get(position);
        String eco = h.itemView.getContext().getString(R.string.llegada_item_formato, p.eco);
        if (p.destino != null && !p.destino.isEmpty()) eco += "  →  " + p.destino;
        h.eco.setText(eco);
        Tipografia.aplicar(h.eco, h.eta);   // número de unidad / estación en Tipo Metro
        String dist = distTxt(p.metros);
        if (p.etaSeg <= 30) {
            h.eta.setText(h.itemView.getContext().getString(R.string.llegada_eta_ya, dist));
        } else {
            int min = Math.max(1, Math.round(p.etaSeg / 60f));
            h.eta.setText(h.itemView.getContext().getString(R.string.llegada_eta_formato, min, dist));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String distTxt(int metros) {
        if (metros >= 1000) return String.format(java.util.Locale.getDefault(), "%.1f km", metros / 1000f);
        return metros + " m";
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView eco, eta;
        VH(@NonNull View v) {
            super(v);
            eco = v.findViewById(R.id.txt_llegada_eco);
            eta = v.findViewById(R.id.txt_llegada_eta);
        }
    }
}
