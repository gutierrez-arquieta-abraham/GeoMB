package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Buscador de unidad por número económico contra el feed en tiempo real.
 */
public class SearchFragment extends Fragment {

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

        TextInputEditText input = view.findViewById(R.id.input_unidad);
        MaterialButton btnBuscar = view.findViewById(R.id.btn_buscar);
        MaterialCardView cardResultado = view.findViewById(R.id.card_resultado);
        TextView txtUnidad = view.findViewById(R.id.txt_unidad);
        TextView txtLinea = view.findViewById(R.id.txt_linea);
        TextView txtActualizacion = view.findViewById(R.id.txt_actualizacion);

        cardResultado.setVisibility(View.GONE);

        btnBuscar.setOnClickListener(v -> {
            String numero = input.getText() != null ? input.getText().toString().trim() : "";
            if (numero.isEmpty()) return;
            ocultarTeclado(input);

            btnBuscar.setEnabled(false);
            // pide el feed fresco y busca en el resultado
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override
                public void onData(List<UnidadReal> unidades) {
                    if (!isAdded()) return;
                    btnBuscar.setEnabled(true);

                    UnidadReal u = RealtimeRepository.get().buscar(numero);
                    if (u == null) {
                        cardResultado.setVisibility(View.GONE);
                        Toast.makeText(requireContext(),
                                getString(R.string.unidad_no_encontrada, numero),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    txtUnidad.setText(getString(R.string.unidad_numero, u.numero));
                    txtLinea.setText(descripcionLinea(u));
                    txtActualizacion.setText(u.destino != null && !u.destino.isEmpty()
                            ? "Destino: " + u.destino : "En servicio");
                    cardResultado.setVisibility(View.VISIBLE);

                    view.findViewById(R.id.btn_ver_mapa).setOnClickListener(b -> {
                        RealtimeRepository.unidadSeleccionada = u.numero;
                        ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
                    });
                }

                @Override
                public void onError(String mensaje) {
                    if (!isAdded()) return;
                    btnBuscar.setEnabled(true);
                    cardResultado.setVisibility(View.GONE);
                    Toast.makeText(requireContext(),
                            "Sin conexión con el servidor de unidades", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private String descripcionLinea(UnidadReal u) {
        if (u.linea == null) return "Sin línea asignada";
        Linea l = GtfsRepository.porNumero(requireContext(), u.linea);
        String nombre = l != null ? l.nombre : "";
        return getString(R.string.linea_formato, u.linea) + (nombre.isEmpty() ? "" : " · " + nombre);
    }

    private void ocultarTeclado(View v) {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
