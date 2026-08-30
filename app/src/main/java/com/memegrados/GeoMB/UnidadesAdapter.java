package com.memegrados.GeoMB;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class UnidadesAdapter extends RecyclerView.Adapter<UnidadesAdapter.VH> {

    public interface OnVer {
        void ver(UnidadReal u);
    }

    private final List<UnidadReal> unidades = new ArrayList<>();
    private final OnVer onVer;

    public UnidadesAdapter(OnVer onVer) {
        this.onVer = onVer;
    }

    public void set(List<UnidadReal> nuevas) {
        unidades.clear();
        unidades.addAll(nuevas);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_unidad, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        UnidadReal u = unidades.get(pos);
        int gray = ContextCompat.getColor(h.itemView.getContext(), R.color.mb_gray);
        int color = gray;
        if (u.linea != null) {
            Linea l = GtfsRepository.porNumero(h.itemView.getContext(), u.linea);
            if (l != null) color = l.color;
            h.numLinea.setText(String.valueOf(u.linea));
        } else {
            h.numLinea.setText("–");
        }
        h.numLinea.setBackgroundTintList(ColorStateList.valueOf(color));

        h.economico.setText(h.itemView.getContext().getString(R.string.unidad_numero, u.numero));
        String destino = u.destino != null && !u.destino.isEmpty()
                ? u.destino
                : h.itemView.getContext().getString(R.string.unidad_sin_linea);
        h.destino.setText(destino);
        h.empresa.setText(u.empresa != null && !Modelos.SIN_ASIGNAR.equals(u.empresa)
                ? u.empresa
                : h.itemView.getContext().getString(R.string.unidad_sin_empresa));

        // Tipografía Tipo Metro en números de unidad/línea y nombre de estación
        Tipografia.aplicar(h.numLinea, h.economico, h.destino);

        h.btnVer.setOnClickListener(v -> { if (onVer != null) onVer.ver(u); });
    }

    @Override
    public int getItemCount() {
        return unidades.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView numLinea, economico, destino, empresa;
        final MaterialButton btnVer;

        VH(@NonNull View v) {
            super(v);
            numLinea = v.findViewById(R.id.txt_num_linea);
            economico = v.findViewById(R.id.txt_economico);
            destino = v.findViewById(R.id.txt_destino);
            empresa = v.findViewById(R.id.txt_empresa);
            btnVer = v.findViewById(R.id.btn_ver);
        }
    }
}
