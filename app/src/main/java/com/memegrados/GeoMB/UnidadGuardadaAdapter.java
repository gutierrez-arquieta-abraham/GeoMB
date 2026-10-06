package com.memegrados.GeoMB;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// ============================================================
// CLASE    : UnidadGuardadaAdapter   (extends RecyclerView.Adapter)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Adaptador de "Mis unidades" (UnidadesGuardadasFragment): económico + línea
// CACHEADA + estado de alerta + "Siguiendo" si corresponde -- NUNCA "Siguiendo"
// solo por estar guardada (son estados independientes, ver EconomicoFavoritoEntity).
// ============================================================
public class UnidadGuardadaAdapter extends RecyclerView.Adapter<UnidadGuardadaAdapter.VH> {

    public interface Acciones {
        /** Toca la fila: abrir la carta de esa unidad en el mapa. */
        void abrir(EconomicoFavoritoEntity f);
        /** Botón ⋮ de la fila: menú con quitar/alerta. */
        void opciones(EconomicoFavoritoEntity f, View ancla);
    }

    private final List<EconomicoFavoritoEntity> datos = new ArrayList<>();
    private final Acciones acciones;

    public UnidadGuardadaAdapter(Acciones acciones) {
        this.acciones = acciones;
    }

    public void set(List<EconomicoFavoritoEntity> nuevos) {
        datos.clear();
        datos.addAll(nuevos);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_unidad_guardada, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        EconomicoFavoritoEntity f = datos.get(pos);
        android.content.Context ctx = h.itemView.getContext();

        int gray = ContextCompat.getColor(ctx, R.color.mb_gray);
        if (f.linea > 0) {
            Linea l = GtfsRepository.porNumero(ctx, f.linea);
            h.numLinea.setText(Planificador.etiquetaLineaCortaPub(f.linea));
            h.numLinea.setBackgroundTintList(ColorStateList.valueOf(l != null ? l.color : gray));
            h.txtLinea.setText(etiquetaLinea(ctx, f.linea));
        } else {
            h.numLinea.setText("–");
            h.numLinea.setBackgroundTintList(ColorStateList.valueOf(gray));
            h.txtLinea.setText(R.string.unidad_linea_desconocida);
        }

        h.economico.setText(ctx.getString(R.string.unidad_numero, f.economico));

        h.badgeAlerta.setText(f.alertaActiva ? R.string.unidad_alerta_activada_badge : R.string.unidad_alerta_desactivada_badge);

        // "Siguiendo" es un estado en memoria (SeguimientoService), nunca derivado de "guardada".
        boolean sigue = SeguimientoService.sigue(f.economico);
        h.badgeSiguiendo.setVisibility(sigue ? View.VISIBLE : View.GONE);

        Tipografia.aplicar(h.numLinea, h.economico, h.txtLinea);

        h.itemView.setOnClickListener(v -> { if (acciones != null) acciones.abrir(f); });
        h.btnOpciones.setOnClickListener(v -> { if (acciones != null) acciones.opciones(f, v); });
    }

    @Override
    public int getItemCount() {
        return datos.size();
    }

    /** Metrobús ("Línea N") vs. Mexibús/Mexicable (nombre propio, p. ej. "Mexibús L4") -- mismo
     *  criterio que ya usa CartaUnidad.descripcionLinea(), pero sin necesitar un UnidadReal (aquí
     *  solo hay el entero cacheado). */
    private static String etiquetaLinea(android.content.Context ctx, int linea) {
        if (linea < 100) return ctx.getString(R.string.linea_formato_txt, Planificador.etiquetaLineaCortaPub(linea));
        Linea l = GtfsRepository.porNumero(ctx, linea);
        String base = linea < 200 ? "Mexibús" : "Mexicable";
        return base + " L" + Planificador.etiquetaLineaCortaPub(linea) + (l != null && l.nombre != null && !l.nombre.isEmpty() ? " · " + l.nombre : "");
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView numLinea, economico, txtLinea, badgeAlerta, badgeSiguiendo;
        final ImageView btnOpciones;

        VH(@NonNull View v) {
            super(v);
            numLinea = v.findViewById(R.id.txt_num_linea);
            economico = v.findViewById(R.id.txt_economico);
            txtLinea = v.findViewById(R.id.txt_linea_guardada);
            badgeAlerta = v.findViewById(R.id.badge_alerta);
            badgeSiguiendo = v.findViewById(R.id.badge_siguiendo);
            btnOpciones = v.findViewById(R.id.btn_opciones_guardada);
        }
    }
}
