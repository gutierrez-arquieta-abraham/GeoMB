package com.memegrados.GeoMB;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Lista de rutas por línea: encabezado de línea + filas de ruta (código, recorrido). */
public class RutasAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TIPO_HEADER = 0;
    private static final int TIPO_RUTA = 1;

    /** Clic en una ruta: abre el listado de unidades de esa ruta. */
    public interface OnRuta {
        void abrir(int linea, int codigo, String recorrido);
    }

    /** Fila: encabezado de línea o una ruta. */
    public static class Fila {
        final boolean header;
        final int color;
        final String titulo;   // header: "Línea N · nombre"; ruta: "Origen ↔ Destino"
        final int linea;       // solo ruta
        final int codigo;      // solo ruta
        final int unidades;    // solo ruta

        private Fila(boolean header, int color, String titulo, int linea, int codigo, int unidades) {
            this.header = header;
            this.color = color;
            this.titulo = titulo;
            this.linea = linea;
            this.codigo = codigo;
            this.unidades = unidades;
        }

        public static Fila header(int color, String titulo) {
            return new Fila(true, color, titulo, 0, 0, 0);
        }

        public static Fila ruta(int color, int linea, int codigo, String recorrido, int unidades) {
            return new Fila(false, color, recorrido, linea, codigo, unidades);
        }
    }

    private final List<Fila> filas;
    private final OnRuta onRuta;

    public RutasAdapter(List<Fila> filas) {
        this(filas, null);
    }

    public RutasAdapter(List<Fila> filas, OnRuta onRuta) {
        this.filas = filas;
        this.onRuta = onRuta;
    }

    @Override
    public int getItemViewType(int position) {
        return filas.get(position).header ? TIPO_HEADER : TIPO_RUTA;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TIPO_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_ruta_header, parent, false));
        }
        return new RutaVH(inf.inflate(R.layout.item_ruta, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Fila f = filas.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.txt.setText(f.titulo);
            h.barra.setBackgroundColor(f.color);
            Tipografia.aplicar(h.txt);
        } else {
            RutaVH r = (RutaVH) holder;
            r.codigo.setText(String.valueOf(f.codigo));
            r.codigo.setBackgroundTintList(ColorStateList.valueOf(f.color));
            r.recorrido.setText(f.titulo);
            r.unidades.setText(r.itemView.getContext()
                    .getString(R.string.ruta_unidades_formato, f.unidades));
            Tipografia.aplicar(r.codigo, r.recorrido, r.unidades);
            r.itemView.setOnClickListener(v -> {
                if (onRuta != null) onRuta.abrir(f.linea, f.codigo, f.titulo);
            });
        }
    }

    @Override
    public int getItemCount() {
        return filas.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView txt;
        final View barra;
        HeaderVH(@NonNull View v) {
            super(v);
            txt = v.findViewById(R.id.txt_header_linea);
            barra = v.findViewById(R.id.barra_linea);
        }
    }

    static class RutaVH extends RecyclerView.ViewHolder {
        final TextView codigo, recorrido, unidades;
        RutaVH(@NonNull View v) {
            super(v);
            codigo = v.findViewById(R.id.txt_codigo);
            recorrido = v.findViewById(R.id.txt_recorrido);
            unidades = v.findViewById(R.id.txt_ruta_unidades);
        }
    }
}
