package com.memegrados.GeoMB;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de estaciones de una línea con encabezados de sección (por dirección en
 * L2/L6/L7, o por ruta en L4). Solo estaciones, sin unidades.
 */
public class EstacionesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TIPO_HEADER = 0;
    private static final int TIPO_EST = 1;

    /** Item: encabezado de sección o una estación. */
    public static final class Item {
        final boolean header;
        final String titulo;     // header o nombre de estación
        final String subtitulo;  // "Dirección X" o "" (solo estación)
        final String icono;      // solo estación
        final int color;

        private Item(boolean header, String titulo, String subtitulo, String icono, int color) {
            this.header = header; this.titulo = titulo; this.subtitulo = subtitulo;
            this.icono = icono; this.color = color;
        }
        public static Item header(String titulo, int color) {
            return new Item(true, titulo, "", "", color);
        }
        public static Item estacion(String nombre, String subtitulo, String icono, int color) {
            return new Item(false, nombre, subtitulo, icono, color);
        }
    }

    private final List<Item> datos = new ArrayList<>();

    public void set(List<Item> items) {
        datos.clear();
        if (items != null) datos.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return datos.get(position).header ? TIPO_HEADER : TIPO_EST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TIPO_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_estacion_header, parent, false));
        }
        return new EstVH(inf.inflate(R.layout.item_estacion_lista, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        Item it = datos.get(pos);
        if (h instanceof HeaderVH) {
            HeaderVH v = (HeaderVH) h;
            v.titulo.setText(it.titulo);
            v.barra.setBackgroundColor(it.color);
        } else {
            EstVH v = (EstVH) h;
            v.nombre.setText(it.titulo);
            Tipografia.aplicar(v.nombre);   // nombre de estación en Tipo Metro

            if (it.subtitulo != null && !it.subtitulo.isEmpty()) {
                v.sub.setText(it.subtitulo);
                v.sub.setVisibility(View.VISIBLE);
            } else {
                v.sub.setVisibility(View.GONE);
            }

            int id = 0;
            if (it.icono != null && !it.icono.isEmpty()) {
                id = v.itemView.getResources().getIdentifier(
                        it.icono, "drawable", v.itemView.getContext().getPackageName());
            }
            if (id != 0) v.ic.setImageResource(id);
            else v.ic.setImageDrawable(null);

            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(it.color);
            v.dot.setBackground(dot);
            v.dot.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() { return datos.size(); }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView titulo;
        final View barra;
        HeaderVH(@NonNull View v) {
            super(v);
            titulo = v.findViewById(R.id.eh_titulo);
            barra = v.findViewById(R.id.eh_barra);
        }
    }

    static final class EstVH extends RecyclerView.ViewHolder {
        final ImageView ic;
        final TextView nombre, sub;
        final View dot;
        EstVH(@NonNull View v) {
            super(v);
            ic = v.findViewById(R.id.est_ic);
            nombre = v.findViewById(R.id.est_nombre);
            sub = v.findViewById(R.id.est_sub);
            dot = v.findViewById(R.id.est_correspondencia);
        }
    }
}
