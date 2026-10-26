package com.memegrados.GeoMB;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Deslizador horizontal con la secuencia de estaciones de la ruta. Entre tramos intercala un CHIP de
 * instrucción ("Aborda / Toma · dirección {terminal}"), tomando la terminal del planificador
 * ({@link Planificador.Instruccion}). Durante el recorrido resalta la estación actual y el consumidor
 * la centra automáticamente ({@link #posDe(int)}).
 */
// ============================================================
// CLASE    : EstacionRutaAdapter   (extends RecyclerView.Adapter)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// ADAPTADOR del DESLIZADOR de estaciones del planificador: muestra cada
// estación con su pictograma y chips de instrucción ("Aborda / Toma ·
// dirección {terminal}", de Planificador.Instruccion). Durante el recorrido
// resalta la estación actual y el consumidor la centra (posDe(int)).
// ============================================================
public class EstacionRutaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int T_EST = 0, T_INSTR = 1;

    /** Un ítem del deslizador: estación (parada != null) o instrucción de tramo (texto != null). */
    private static final class Item {
        final Planificador.Parada parada;   // estación
        final String texto;                 // instrucción
        final int color;
        final int seqIdx;                   // índice en la secuencia original (-1 para instrucciones)
        Item(Planificador.Parada p, int seqIdx) { parada = p; texto = null; color = p.color; this.seqIdx = seqIdx; }
        Item(String t, int color) { parada = null; texto = t; this.color = color; seqIdx = -1; }
    }

    private final List<Item> items = new ArrayList<>();
    private int actual = -1;   // índice de secuencia de la estación en la que va el usuario (recorrido)

    /** Solo estaciones (sin instrucciones): compat para el listado previo a un recorrido. */
    public void set(List<Planificador.Parada> nuevas) { set(nuevas, null); }

    /** Estaciones + instrucciones de tramo (chips de dirección entre líneas). 'ctx' solo se necesita
     *  para el texto localizado del chip de cambio de servicio (ver abajo); null se tolera (el chip
     *  simplemente no aparece) para no romper otros llamadores. */
    public void set(List<Planificador.Parada> paradas, List<Planificador.Instruccion> instrucciones) {
        set(paradas, instrucciones, null);
    }

    public void set(List<Planificador.Parada> paradas, List<Planificador.Instruccion> instrucciones,
                     android.content.Context ctx) {
        items.clear();
        if (paradas != null) {
            int leg = 0;
            for (int k = 0; k < paradas.size(); k++) {
                Planificador.Parada p = paradas.get(k);
                boolean nuevoTramo = (k == 0) || p.transbordo;
                if (nuevoTramo && instrucciones != null && leg < instrucciones.size()) {
                    items.add(new Item(textoInstr(instrucciones.get(leg)), instrucciones.get(leg).color));
                    leg++;
                }
                // Cambio de ordinario↔exprés de la MISMA línea (p. ej. L4 104↔124): NO es un tramo
                // nuevo (no incrementa 'leg', no tiene su propia Instruccion -- ver el comentario de
                // Planificador.Parada.cambioServicio), así que se muestra con su PROPIO chip simple,
                // independiente del de arriba.
                if (p.cambioServicio && ctx != null) {
                    boolean express = p.linea >= 121 && p.linea <= 124;
                    String tipo = ctx.getString(express ? R.string.servicio_express : R.string.servicio_ordinario);
                    items.add(new Item(ctx.getString(R.string.ruta_cambia_servicio, tipo), p.color));
                }
                items.add(new Item(p, k));
            }
        }
        actual = -1;
        notifyDataSetChanged();
    }

    private static String textoInstr(Planificador.Instruccion ins) {
        String verbo = ins.transbordoAntes ? "Toma" : "Aborda";
        String dir = (ins.terminal == null || ins.terminal.isEmpty()) ? "" : " · dirección " + ins.terminal;
        return verbo + dir;
    }

    public void setActual(int seqIdx) {
        if (seqIdx == actual) return;
        actual = seqIdx;
        notifyDataSetChanged();
    }

    /** Posición del ítem (para centrar el deslizador) que corresponde a la estación de secuencia {@code seqIdx}. */
    public int posDe(int seqIdx) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).seqIdx == seqIdx) return i;
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).parada != null ? T_EST : T_INSTR;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == T_INSTR) {
            TextView t = new TextView(parent.getContext());
            float d = parent.getResources().getDisplayMetrics().density;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (6 * d), (int) (8 * d), (int) (6 * d), (int) (8 * d));
            t.setLayoutParams(lp);
            t.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
            t.setGravity(Gravity.CENTER);
            t.setMaxWidth((int) (170 * d));
            t.setTextSize(12);
            return new InstrVH(t);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_estacion_ruta, parent, false);
        return new EstVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        Item it = items.get(pos);
        if (h instanceof InstrVH) {
            TextView t = (TextView) h.itemView;
            t.setText(it.texto);
            t.setTextColor(it.color);
            t.setTypeface(Tipografia.metro(t.getContext()), Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(matiz(it.color, 0x22));   // fondo tenue del color de la línea
            bg.setStroke((int) (1 * t.getResources().getDisplayMetrics().density), matiz(it.color, 0x66));
            bg.setCornerRadius(14 * t.getResources().getDisplayMetrics().density);
            t.setBackground(bg);
            return;
        }
        EstVH e = (EstVH) h;
        Planificador.Parada p = it.parada;
        String limpio = Planificador.nombreMostrar(e.itemView.getContext(), p.nombre, p.linea);
        e.nombre.setText(p.transbordo ? "⇄ " + limpio : limpio);

        // Pictograma de la estación (misma lógica que la descripción de ruta: respeta nuevo/antiguo y,
        // si no hay pictograma, cae a un punto del color de la línea).
        int px = Math.round(24 * e.itemView.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap bmp = Iconos.pictograma(e.itemView.getContext(), p.icono, px);
        // Troncal L2 (Tacubaya/De la Salle): mismo ícono diagonal L7/L2 que ya llevan el marcador del
        // mapa trazado (PlanificadorFragment.dibujar()) y el resto de la app -- este deslizador tenía
        // su PROPIA ruta de dibujo, nunca enterada de la diagonal, así que mostraba el pictograma
        // sólido de siempre aunque el mapa de abajo sí mostrara la diagonal. Los 2 andenes PROPIOS de
        // H72 (Iconos.ESTACIONES_PROPIAS_H72) llevan la MISMA diagonal pero INVERTIDA, para distinguirlos.
        boolean esTroncalH72L2 = p.linea == 2 && Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm(p.nombre));
        boolean esPropioH72 = p.linea == 2 && Iconos.ESTACIONES_PROPIAS_H72.contains(Planificador.norm(p.nombre));
        if (bmp != null && (esTroncalH72L2 || esPropioH72)) {
            int arriba = colorDe(e.itemView.getContext(), esTroncalH72L2 ? 7 : 2);
            int abajo = colorDe(e.itemView.getContext(), esTroncalH72L2 ? 2 : 7);
            bmp = Iconos.recoloreaFondoDiagonal(bmp, arriba, abajo);
        }
        if (bmp != null) {
            e.dot.setImageBitmap(bmp);
        } else {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(p.color);
            dot.setSize(px, px);
            e.dot.setImageDrawable(dot);
        }

        boolean esActual = it.seqIdx == actual;
        Tipografia.aplicar(e.nombre, esActual ? Typeface.BOLD : Typeface.NORMAL);
        e.nombre.setTextColor(esActual ? p.color
                : e.nombre.getResources().getColor(R.color.mb_gray, null));
    }

    /** Mezcla un color con transparencia sobre el canal alfa dado (0x00..0xFF). */
    private static int matiz(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Color de una línea por su número (anaranjado de respaldo si no se encuentra). */
    private static int colorDe(android.content.Context ctx, int lineaNum) {
        Linea l = GtfsRepository.porNumero(ctx, lineaNum);
        return l != null ? l.color : 0xFFFF9A03;
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class EstVH extends RecyclerView.ViewHolder {
        final android.widget.ImageView dot;
        final TextView nombre;
        EstVH(@NonNull View v) {
            super(v);
            dot = v.findViewById(R.id.est_dot);
            nombre = v.findViewById(R.id.est_nombre);
        }
    }

    static final class InstrVH extends RecyclerView.ViewHolder {
        InstrVH(@NonNull View v) { super(v); }
    }
}
