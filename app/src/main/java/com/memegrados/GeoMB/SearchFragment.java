package com.memegrados.GeoMB;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Buscador de unidad por número económico. Permite ver la unidad en el mapa
 * o "Seguir" (aviso de cercanía a 500 m mediante SeguimientoService).
 */
// ============================================================
// CLASE    : SearchFragment   (extends Fragment)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Buscador de UNIDAD por número económico. Permite ver la unidad en el mapa
// o "Seguir" (aviso de cercanía mediante SeguimientoService). La tarjeta de
// resultado (view_carta_unidad.xml) la rellena CartaUnidad -- compartida con
// el buscador inline del mapa (MapFragment) para no duplicar esa lógica.
// ============================================================
public class SearchFragment extends Fragment {

    private MaterialButton btnBuscar;
    private TextInputEditText inputUnidad;
    private CartaUnidad.Vistas carta;
    private String ecoActual;   // económico mostrado en la tarjeta

    private final ActivityResultLauncher<String> permisoUbicacion =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) intentarSeguir();
                else if (isAdded()) Toast.makeText(requireContext(),
                        getString(R.string.seguir_permiso_ubicacion), Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> permisoNotif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> arrancarSeguimiento());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        inputUnidad = view.findViewById(R.id.input_unidad);
        btnBuscar = view.findViewById(R.id.btn_buscar);
        carta = new CartaUnidad.Vistas(view);
        carta.btnAnadirSeguir.setOnClickListener(v -> { if (ecoActual != null && !SeguimientoService.sigue(ecoActual)) intentarSeguir(); });
        carta.btnDetenerTodos.setOnClickListener(v -> detenerTodos());

        carta.card.setVisibility(View.GONE);

        btnBuscar.setOnClickListener(v -> ejecutarBusqueda(
                inputUnidad.getText() != null ? inputUnidad.getText().toString().trim() : ""));

        carta.btnSeguir.setOnClickListener(v -> {
            if (ecoActual == null) return;
            if (SeguimientoService.sigue(ecoActual)) {
                detenerSeguimiento();
            } else {
                intentarSeguir();   // añade esta unidad (sin quitar las que ya se siguen)
            }
        });
        // Mantener presionado "Seguir" = guardar/quitar de favoritos (persiste entre reinicios:
        // ArranqueReceiver retoma el seguimiento de los favoritos guardados al arrancar el teléfono).
        carta.btnSeguir.setOnLongClickListener(v -> {
            if (ecoActual == null || !isAdded()) return false;
            Telemetria.esFavorito(requireContext(), ecoActual, esFav -> {
                if (!isAdded()) return;
                if (esFav) {
                    Telemetria.quitarFavorito(requireContext(), ecoActual);
                    Toast.makeText(requireContext(), getString(R.string.favorito_quitado, ecoActual), Toast.LENGTH_SHORT).show();
                } else {
                    Telemetria.guardarFavorito(requireContext(), ecoActual);
                    Toast.makeText(requireContext(), getString(R.string.favorito_guardado, ecoActual), Toast.LENGTH_LONG).show();
                }
            });
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarBotonSeguir();
    }

    /** Busca un económico: valida el límite y consulta el feed (o muestra la ficha offline). */
    private void ejecutarBusqueda(String numero) {
        if (numero == null || numero.isEmpty()) return;
        if (inputUnidad != null) ocultarTeclado(inputUnidad);

        int max = Modelos.maxEconomico();
        try {
            int n = Integer.parseInt(numero.replaceAll("[^0-9]", ""));
            if (max > 0 && n > max) {
                Toast.makeText(requireContext(),
                        getString(R.string.eco_max, max), Toast.LENGTH_LONG).show();
                return;
            }
        } catch (NumberFormatException ignore) {}

        final String eco = numero;
        Telemetria.registrarBusqueda(requireContext(), eco);   // para "unidades más buscadas"
        btnBuscar.setEnabled(false);
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override
            public void onData(List<UnidadReal> unidades) {
                if (!isAdded()) return;
                btnBuscar.setEnabled(true);
                mostrarInfo(eco, RealtimeRepository.get().buscar(eco));
            }

            @Override
            public void onError(String mensaje) {
                if (!isAdded()) return;
                btnBuscar.setEnabled(true);
                mostrarInfo(eco, null);   // sin conexión: ficha del catálogo (offline)
            }
        });
    }

    /** Verifica permisos y arranca el seguimiento del económico actual. */
    private void intentarSeguir() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoNotif.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarSeguimiento();
    }

    private void arrancarSeguimiento() {
        if (!isAdded() || ecoActual == null) return;
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .putExtra(SeguimientoService.EXTRA_ECO, ecoActual);
        try { ContextCompat.startForegroundService(requireContext(), i); } catch (Exception ignore) {}
        SeguimientoService.ecosSeguidos.add(ecoActual);   // reflejo inmediato en la UI
        actualizarBotonSeguir();
        Toast.makeText(requireContext(),
                getString(R.string.seguir_activado, ecoActual), Toast.LENGTH_LONG).show();
    }

    private void detenerSeguimiento() {
        if (ecoActual == null) return;
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .setAction(SeguimientoService.ACCION_DETENER)
                .putExtra(SeguimientoService.EXTRA_ECO, ecoActual);   // detiene SOLO esta unidad
        requireContext().startService(i);
        SeguimientoService.ecosSeguidos.remove(ecoActual);
        actualizarBotonSeguir();
    }

    /** Detiene el seguimiento de TODAS las unidades. */
    private void detenerTodos() {
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .setAction(SeguimientoService.ACCION_DETENER);   // sin económico = todas
        requireContext().startService(i);
        SeguimientoService.ecosSeguidos.clear();
        actualizarBotonSeguir();
    }

    private void actualizarBotonSeguir() {
        if (carta == null) return;
        boolean sigueEsta = ecoActual != null && SeguimientoService.sigue(ecoActual);
        carta.btnSeguir.setText(sigueEsta ? R.string.dejar_de_seguir : R.string.seguir);
        // Fila de acciones múltiples: solo cuando ya hay unidad(es) en seguimiento en curso.
        boolean hay = !SeguimientoService.ecosSeguidos.isEmpty();
        carta.filaSeguirMulti.setVisibility(hay ? View.VISIBLE : View.GONE);
        carta.btnAnadirSeguir.setEnabled(ecoActual != null && !sigueEsta);   // añadir la actual
    }

    /**
     * Muestra la ficha del económico (CartaUnidad.bind se encarga de los campos). Si {@code u}
     * viene del feed, agrega el listener de "Ver en mapa" (navega a la pestaña Mapa); si es
     * null (no en servicio o sin conexión), CartaUnidad ya deja ese botón oculto.
     */
    private void mostrarInfo(String numero, UnidadReal u) {
        ecoActual = numero;
        CartaUnidad.bind(requireContext(), carta, numero, u, () -> ecoActual);
        if (u != null) {
            carta.btnVerMapa.setOnClickListener(b -> {
                RealtimeRepository.unidadSeleccionada = numero;
                ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
            });
        }
        actualizarBotonSeguir();
    }

    private void ocultarTeclado(View v) {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
