package com.memegrados.GeoMB;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LinesAdapter extends RecyclerView.Adapter<LinesAdapter.LineaViewHolder> {

    public interface OnLineaClick {
        void onClick(Linea linea);
    }

    private final List<Linea> lineas;
    private final OnLineaClick listener;

    public LinesAdapter(List<Linea> lineas, OnLineaClick listener) {
        this.lineas = lineas;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LineaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_line, parent, false);
        return new LineaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LineaViewHolder holder, int position) {
        Linea linea = lineas.get(position);
        holder.txtNumero.setText(String.valueOf(linea.numero));
        holder.txtNumero.setBackgroundTintList(ColorStateList.valueOf(linea.color));
        holder.txtNombre.setText(holder.itemView.getContext()
                .getString(R.string.linea_formato, linea.numero) + " · " + linea.nombre);
        holder.txtEstaciones.setText(holder.itemView.getContext()
                .getString(R.string.estaciones_formato, linea.estaciones.size()));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(linea);
        });
    }

    @Override
    public int getItemCount() {
        return lineas.size();
    }

    static class LineaViewHolder extends RecyclerView.ViewHolder {
        final TextView txtNumero;
        final TextView txtNombre;
        final TextView txtEstaciones;

        LineaViewHolder(@NonNull View itemView) {
            super(itemView);
            txtNumero = itemView.findViewById(R.id.txt_numero_linea);
            txtNombre = itemView.findViewById(R.id.txt_nombre_linea);
            txtEstaciones = itemView.findViewById(R.id.txt_estaciones);
        }
    }
}
