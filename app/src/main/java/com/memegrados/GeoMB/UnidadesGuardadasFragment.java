package com.memegrados.GeoMB;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * "⭐ Mis unidades": lista de unidades GUARDADAS (economicos_favoritos), con su línea cacheada,
 * su preferencia de alerta de proximidad y si está SIGUIENDO ahora mismo -- los tres estados se
 * muestran independientes (ver EconomicoFavoritoEntity). Guardar/quitar/alternar la alerta aquí
 * nunca inicia ni detiene SeguimientoService; abrir la carta (un toque en la fila) es donde
 * seguir/dejar de seguir sigue viviendo, como siempre.
 */
// ============================================================
// CLASE    : UnidadesGuardadasFragment   (extends Fragment)
// PROYECTO : GeoMB
// ============================================================
public class UnidadesGuardadasFragment extends Fragment {

    private UnidadGuardadaAdapter adapter;
    private TextView vacio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Refresco periódico SOLO para reflejar cambios de "Siguiendo" hechos desde otra pantalla/
    // notificación mientras esta lista está abierta -- es una consulta a Room en el hilo de E/S
    // (Telemetria.listaFavoritos), no un poll al feed ni ninguna detección de proximidad.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refrescar();
            handler.postDelayed(this, 5000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_unidades_guardadas, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_titulo));
        vacio = view.findViewById(R.id.txt_vacio);

        RecyclerView rv = view.findViewById(R.id.recycler_guardadas);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new UnidadGuardadaAdapter(new UnidadGuardadaAdapter.Acciones() {
            @Override public void abrir(EconomicoFavoritoEntity f) {
                RealtimeRepository.unidadSeleccionada = f.economico;
                ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
            }
            @Override public void opciones(EconomicoFavoritoEntity f, View ancla) {
                mostrarMenuFila(f, ancla);
            }
        });
        rv.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    private void refrescar() {
        if (!isAdded()) return;
        Telemetria.listaFavoritos(requireContext(), this::aplicarLista);
    }

    private void aplicarLista(List<EconomicoFavoritoEntity> lista) {
        if (!isAdded() || adapter == null) return;
        adapter.set(lista);
        vacio.setVisibility(lista.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Menú ⋮ de una fila: abrir en el mapa / alternar alerta (+ radio) / quitar de guardadas.
     *  Ninguna de estas acciones toca SeguimientoService. */
    private void mostrarMenuFila(EconomicoFavoritoEntity f, View ancla) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        menu.inflate(R.menu.unidad_guardada_opciones_menu);
        android.view.Menu m = menu.getMenu();
        m.findItem(R.id.menu_alerta_unidad_lista).setTitle(f.alertaActiva ? R.string.menu_alerta_desactivar : R.string.menu_alerta_activar);
        android.view.MenuItem itemRadio = m.findItem(R.id.menu_alerta_radio_lista);
        itemRadio.setVisible(f.alertaActiva);
        itemRadio.setTitle(getString(R.string.menu_alerta_radio_fmt, etiquetaRadio(f.radioAlertaM)));

        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_abrir_en_mapa) {
                RealtimeRepository.unidadSeleccionada = f.economico;
                ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
            } else if (id == R.id.menu_quitar_guardada) {
                Telemetria.quitarFavorito(requireContext(), f.economico);
                Toast.makeText(requireContext(), getString(R.string.favorito_quitado, f.economico), Toast.LENGTH_SHORT).show();
                refrescar();
            } else if (id == R.id.menu_alerta_unidad_lista) {
                boolean nuevaActiva = !f.alertaActiva;
                Telemetria.actualizarAlertaUnidad(requireContext(), f.economico, f.linea, nuevaActiva, () -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), nuevaActiva
                            ? getString(R.string.alerta_activada_toast, f.economico, etiquetaRadio(f.radioAlertaM))
                            : getString(R.string.alerta_desactivada_toast, f.economico), Toast.LENGTH_SHORT).show();
                    refrescar();
                });
            } else if (id == R.id.menu_alerta_radio_lista) {
                mostrarMenuRadio(f, ancla);
            }
            return true;
        });
        menu.show();
    }

    private void mostrarMenuRadio(EconomicoFavoritoEntity f, View ancla) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        menu.inflate(R.menu.alerta_radio_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            int radioM = id == R.id.radio_250 ? 250 : id == R.id.radio_1000 ? 1000 : 500;
            Telemetria.actualizarRadioAlerta(requireContext(), f.economico, radioM, () -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        getString(R.string.radio_actualizado_toast, f.economico, etiquetaRadio(radioM)),
                        Toast.LENGTH_SHORT).show();
                refrescar();
            });
            return true;
        });
        menu.show();
    }

    private static String etiquetaRadio(int metros) {
        return metros >= 1000 ? (metros / 1000) + " km" : metros + " m";
    }
}
