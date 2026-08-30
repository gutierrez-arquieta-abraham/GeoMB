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

/** Deslizador horizontal con la secuencia de estaciones de la ruta. */
public class EstacionRutaAdapter extends RecyclerView.Adapter<EstacionRutaAdapter.VH> {

    private final List<Planificador.Parada> datos = new ArrayList<>();
    private int actual = -1;   // estación en la que va el usuario (recorrido)

    public void set(List<Planificador.Parada> nuevas) {
        datos.clear();
        if (nuevas != null) datos.addAll(nuevas);
        actual = -1;
        notifyDataSetChanged();
    }

    public void setActual(int i) {
        if (i == actual) return;
        actual = i;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_estacion_ruta, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Planificador.Parada p = datos.get(pos);
        String limpio = Planificador.nombreMostrar(h.itemView.getContext(), p.nombre, p.linea);
        String nombre = p.transbordo ? "⇄ " + limpio : limpio;
        h.nombre.setText(nombre);

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(p.color);
        h.dot.setBackground(dot);

        boolean esActual = pos == actual;
        Tipografia.aplicar(h.nombre, esActual ? Typeface.BOLD : Typeface.NORMAL);
        h.nombre.setTextColor(esActual ? p.color
                : h.nombre.getResources().getColor(R.color.mb_gray, null));
    }

    @Override
    public int getItemCount() { return datos.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final View dot;
        final TextView nombre;
        VH(@NonNull View v) {
            super(v);
            dot = v.findViewById(R.id.est_dot);
            nombre = v.findViewById(R.id.est_nombre);
        }
    }
}
