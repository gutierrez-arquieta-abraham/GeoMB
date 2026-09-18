package com.memegrados.GeoMB;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

// ============================================================
// CLASE    : LinesAdapter   (extends RecyclerView.Adapter)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// ADAPTADOR de la lista de líneas: convierte los datos (List<Linea>) en las
// filas visibles del RecyclerView de LinesFragment (nombre + color + conteo
// de unidades). Al tocar una fila abre las unidades de esa línea.
//
// ¿QUÉ ES UN RecyclerView.Adapter? El "puente" entre los DATOS y la LISTA en
// pantalla: crea las vistas (onCreateViewHolder) y las rellena
// (onBindViewHolder), RECICLANDO las que salen de pantalla para ir rápido.
// ============================================================
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
        // Etiqueta corta (no el número interno): Metrobús "1".."7"; Mexibús "1".."4"/"1A".."3A";
        // Mexicable "1"/"2" — el 10X/11X/20X es solo interno y no debe verse (ver CLAUDE.md).
        String etiqueta = Planificador.etiquetaLineaCortaPub(linea.numero);
        holder.txtNumero.setText(etiqueta);
        holder.txtNumero.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, etiqueta.length() > 1 ? 15f : 20f);
        // Badge circular con color de línea y contorno negro.
        float dens = holder.itemView.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(linea.color);
        bg.setStroke(Math.round(2 * dens), 0xFF000000);
        holder.txtNumero.setBackground(bg);
        // Metrobús: "Línea N · nombre-de-la-ruta". Mexibús/Mexicable: su nombre ya es autodescriptivo
        // ("Mexibús L1", "Mexicable L2"), así que no se antepone "Línea 101"/"Línea 201".
        holder.txtNombre.setText(linea.numero < 100
                ? holder.itemView.getContext().getString(R.string.linea_formato, linea.numero) + " · " + linea.nombre
                : linea.nombre);
        // Pestaña Líneas: solo estaciones (sin conteo de unidades).
        holder.txtEstaciones.setText(holder.itemView.getContext()
                .getString(R.string.estaciones_formato, linea.estaciones.size()));
        Tipografia.aplicar(holder.txtNumero, holder.txtNombre, holder.txtEstaciones);
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
