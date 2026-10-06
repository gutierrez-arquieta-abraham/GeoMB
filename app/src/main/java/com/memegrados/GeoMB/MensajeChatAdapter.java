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
public class MensajeChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TIPO_MENSAJE = 0;
    /** Burbuja de confirmación inline (ver {@link #agregarConfirmacion}) -- SOLO la usa
     *  AsistenteOverlayFragment; ChatAsistenteActivity nunca llama agregarConfirmacion(), así
     *  que para ella este tipo de item nunca existe y su comportamiento queda idéntico a antes. */
    private static final int TIPO_CONFIRMACION = 1;

    /** Un turno de la conversación, o (si {@code accion != null}) una propuesta de Gemini
     *  pendiente de Confirmar/Cancelar (ver {@link #agregarConfirmacion}). */
    public static final class Mensaje {
        final String texto;
        final boolean deUsuario;
        final AccionPendiente accion;   // null para un mensaje normal
        boolean resuelta;               // true tras tocar Confirmar o Cancelar (solo si accion != null)
        public Mensaje(String texto, boolean deUsuario) {
            this.texto = texto; this.deUsuario = deUsuario; this.accion = null;
        }
        Mensaje(AccionPendiente accion) {
            this.texto = accion.resumen; this.deUsuario = false; this.accion = accion;
        }
    }

    /** Implementada SOLO por quien use {@link #agregarConfirmacion} (AsistenteOverlayFragment) --
     *  igual que antes, la ejecución real de la acción nunca vive aquí, solo se avisa. */
    public interface ListenerConfirmacion {
        void onConfirmar(AccionPendiente a);
        void onCancelar(AccionPendiente a);
    }

    private final List<Mensaje> items = new ArrayList<>();
    private ListenerConfirmacion listenerConfirmacion;

    public void setListenerConfirmacion(ListenerConfirmacion l) { listenerConfirmacion = l; }

    public void agregar(String texto, boolean deUsuario) {
        items.add(new Mensaje(texto, deUsuario));
        notifyItemInserted(items.size() - 1);
    }

    /** Agrega la burbuja de confirmación de {@code a} (ver {@link #setListenerConfirmacion}). */
    public void agregarConfirmacion(AccionPendiente a) {
        items.add(new Mensaje(a));
        notifyItemInserted(items.size() - 1);
    }

    public int cantidad() { return items.size(); }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).accion != null ? TIPO_CONFIRMACION : TIPO_MENSAJE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TIPO_CONFIRMACION) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_confirmacion, parent, false);
            return new VHConfirmacion(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mensaje_chat, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Mensaje m = items.get(position);
        if (holder instanceof VHConfirmacion) {
            VHConfirmacion h = (VHConfirmacion) holder;
            h.texto.setText(m.texto);
            h.btnConfirmar.setEnabled(!m.resuelta);
            h.btnCancelar.setEnabled(!m.resuelta);
            h.btnConfirmar.setOnClickListener(v -> {
                if (m.resuelta) return;
                m.resuelta = true;
                notifyItemChanged(position);
                if (listenerConfirmacion != null) listenerConfirmacion.onConfirmar(m.accion);
            });
            h.btnCancelar.setOnClickListener(v -> {
                if (m.resuelta) return;
                m.resuelta = true;
                notifyItemChanged(position);
                if (listenerConfirmacion != null) listenerConfirmacion.onCancelar(m.accion);
            });
            return;
        }

        VH h = (VH) holder;
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

    static final class VHConfirmacion extends RecyclerView.ViewHolder {
        final TextView texto;
        final com.google.android.material.button.MaterialButton btnConfirmar, btnCancelar;
        VHConfirmacion(View v) {
            super(v);
            texto = v.findViewById(R.id.txt_confirmacion_resumen);
            btnConfirmar = v.findViewById(R.id.btn_confirmacion_confirmar);
            btnCancelar = v.findViewById(R.id.btn_confirmacion_cancelar);
        }
    }
}
