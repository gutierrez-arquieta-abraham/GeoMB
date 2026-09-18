package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de las 7 líneas del Metrobús con el conteo de unidades en servicio.
 * Al tocar una línea se abre el listado de sus unidades.
 */
// ============================================================
// CLASE    : LinesFragment   (extends Fragment)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Lista de las 7 líneas del Metrobús con el conteo de unidades en servicio.
// Al tocar una línea se abre el listado de sus unidades (UnidadesFragment).
// ============================================================
public class LinesFragment extends Fragment {

    private LinesAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lines, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btn_rutas).setOnClickListener(v ->
                ((MainActivity) requireActivity()).mostrarRutas());

        RecyclerView recycler = view.findViewById(R.id.recycler_lineas);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new LinesAdapter(
                lineasAMostrar(),
                linea -> ((MainActivity) requireActivity()).mostrarEstaciones(linea.numero));
        recycler.setAdapter(adapter);

        // Trae el feed para mostrar cuántas unidades hay en servicio por línea.
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> u) {
                if (isAdded() && adapter != null) adapter.notifyDataSetChanged();
            }
            @Override public void onError(String m) { }
        });
    }

    /** Metrobús 1..7 siempre; si "Mostrar Mexibús" está activo (Acerca de), agrega Mexibús
     *  troncal/ramal (101..104, 111..113) y Mexicable (201..202). No incluye exprés (12x): no
     *  son una línea física distinta, sino un servicio más rápido sobre la misma vía. */
    private List<Linea> lineasAMostrar() {
        List<Linea> res = new ArrayList<>(GtfsRepository.getLineas(requireContext()));
        if (Modos.mostrarMexibus(requireContext())) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                boolean troncal = l.numero >= 101 && l.numero <= 104;
                boolean ramal = l.numero >= 111 && l.numero <= 113;
                boolean cable = l.numero >= 201 && l.numero <= 202;
                if (troncal || ramal || cable) res.add(l);
            }
        }
        return res;
    }
}
