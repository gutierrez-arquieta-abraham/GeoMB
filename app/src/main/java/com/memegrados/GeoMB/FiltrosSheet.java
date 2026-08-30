package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Hoja inferior con los 4 filtros (línea / destino / ruta / empresa).
 * Los selectores se pueblan con los valores presentes en la data en vivo.
 */
public class FiltrosSheet extends BottomSheetDialogFragment {

    /** El fragment que muestra la hoja implementa esto para refrescar al aplicar. */
    public interface Host {
        void onFiltrosCambiados();
    }

    private Spinner spLinea, spDestino, spRuta, spEmpresa;
    private List<Integer> lineas;
    private List<String> destinos, rutas, empresas;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_filtros, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spLinea = v.findViewById(R.id.sp_linea);
        spDestino = v.findViewById(R.id.sp_destino);
        spRuta = v.findViewById(R.id.sp_ruta);
        spEmpresa = v.findViewById(R.id.sp_empresa);

        List<UnidadReal> data = RealtimeRepository.get().getUltimo();
        lineas = Filtro.lineasDisponibles(data);
        destinos = Filtro.destinosDisponibles(data);
        rutas = Filtro.rutasDisponibles(data);
        empresas = Filtro.empresasDisponibles(data);

        // Línea (números)
        List<String> lineasTxt = new ArrayList<>();
        lineasTxt.add(getString(R.string.filtro_todas));
        for (Integer l : lineas) lineasTxt.add(getString(R.string.linea_formato, l));
        poblar(spLinea, lineasTxt);

        poblarConTodos(spDestino, destinos);

        // Ruta: mostrar "Origen → Destino" (texto) pero seguir filtrando por route_id.
        List<String> rutasTxt = new ArrayList<>();
        for (String rid : rutas) {
            Ruta r = RutasRepository.porRouteId(rid);
            rutasTxt.add(r != null ? r.recorrido() : rid);
        }
        poblarConTodos(spRuta, rutasTxt);

        poblarConTodos(spEmpresa, empresas);

        // Preseleccionar según el filtro actual
        Filtro f = RealtimeRepository.filtro;
        if (f.linea != null && lineas.indexOf(f.linea) >= 0) spLinea.setSelection(lineas.indexOf(f.linea) + 1);
        seleccionar(spDestino, destinos, f.destino);
        seleccionar(spRuta, rutas, f.ruta);
        seleccionar(spEmpresa, empresas, f.empresa);

        v.findViewById(R.id.btn_limpiar).setOnClickListener(x -> {
            spLinea.setSelection(0); spDestino.setSelection(0);
            spRuta.setSelection(0); spEmpresa.setSelection(0);
        });

        v.findViewById(R.id.btn_aplicar).setOnClickListener(x -> {
            int li = spLinea.getSelectedItemPosition();
            f.linea = li > 0 ? lineas.get(li - 1) : null;
            f.destino = valor(spDestino, destinos);
            f.ruta = valor(spRuta, rutas);
            f.empresa = valor(spEmpresa, empresas);
            if (getParentFragment() instanceof Host) {
                ((Host) getParentFragment()).onFiltrosCambiados();
            }
            dismiss();
        });
    }

    private void poblarConTodos(Spinner sp, List<String> valores) {
        List<String> txt = new ArrayList<>();
        txt.add(getString(R.string.filtro_todos));
        txt.addAll(valores);
        poblar(sp, txt);
    }

    private void poblar(Spinner sp, List<String> items) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    private void seleccionar(Spinner sp, List<String> valores, String actual) {
        if (actual == null) return;
        int idx = valores.indexOf(actual);
        if (idx >= 0) sp.setSelection(idx + 1);
    }

    private String valor(Spinner sp, List<String> valores) {
        int i = sp.getSelectedItemPosition();
        return i > 0 ? valores.get(i - 1) : null;
    }
}
