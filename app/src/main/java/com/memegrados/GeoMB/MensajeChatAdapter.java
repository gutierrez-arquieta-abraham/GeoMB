package com.memegrados.GeoMB;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

// ============================================================
// CLASE    : MensajeChatAdapter   (extends RecyclerView.Adapter)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Lista de burbujas de la conversación con el asistente Gemini (ChatAsistenteActivity):
// mensajes del usuario alineados a la derecha, del asistente a la izquierda, con el mismo
// item_mensaje_chat.xml para ambos (solo cambia gravedad + color de la burbuja).
// ============================================================
public class MensajeChatAdapter extends RecyclerView.Adapter<MensajeChatAdapter.VH> {

    /** Un turno de la conversación. */
    public static final class Mensaje {
        final String texto;
        final boolean deUsuario;
        public Mensaje(String texto, boolean deUsuario) { this.texto = texto; this.deUsuario = deUsuario; }
    }

    private final List<Mensaje> items = new ArrayList<>();

    public void agregar(String texto, boolean deUsuario) {
        items.add(new Mensaje(texto, deUsuario));
        notifyItemInserted(items.size() - 1);
    }

    public int cantidad() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mensaje_chat, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Mensaje m = items.get(position);
        h.texto.setText(m.texto);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
        lp.gravity = m.deUsuario ? Gravity.END : Gravity.START;
        h.card.setLayoutParams(lp);

        int colorFondo = MaterialColors.getColor(h.itemView,
                m.deUsuario ? com.google.android.material.R.attr.colorPrimaryContainer
                        : com.google.android.material.R.attr.colorSurfaceVariant);
        int colorTexto = MaterialColors.getColor(h.itemView,
                m.deUsuario ? com.google.android.material.R.attr.colorOnPrimaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant);
        h.card.setCardBackgroundColor(colorFondo);
        h.texto.setTextColor(colorTexto);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView texto;
        VH(View v) {
            super(v);
            card = v.findViewById(R.id.card_mensaje);
            texto = v.findViewById(R.id.txt_mensaje);
        }
    }
}
