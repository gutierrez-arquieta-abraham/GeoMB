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

        // Ruta: mostrar "Origen → Destino" (texto) pero seguir filtrando por route_id. Los route_id sin
        // nombre en RutasRepository se resuelven desde una UNIDAD con ese route_id (origen→destino del
        // feed); si aun así no hay nombre legible, se OMITEN para no mostrar números crudos (route_id).
        List<String> rutasVis = new ArrayList<>();
        List<String> rutasTxt = new ArrayList<>();
        java.util.Set<String> etiquetasLinea = new java.util.HashSet<>();
        for (String rid : rutas) {
            Ruta r = RutasRepository.porRouteId(rid);
            String txt;
            if (r != null) {
                txt = r.recorrido();
            } else {
                txt = recorridoDeUnidad(data, rid);          // origen → destino del feed
                if (txt == null) {
                    Integer ln = lineaDeUnidad(data, rid);   // último recurso: etiqueta de línea
                    if (ln == null) continue;                // sin nada legible: se omite
                    String et = etiquetaLinea(ln);
                    if (!etiquetasLinea.add(et)) continue;   // ya hay una fila de esa línea: no duplicar
                    txt = et;
                }
            }
            if (txt == null || txt.trim().isEmpty()) continue;
            rutasVis.add(rid);
            rutasTxt.add(txt);
        }
        rutas = rutasVis;   // mantiene la sincronía con rutasTxt (se usa para el valor del filtro)
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

    /** "Origen → Destino" tomado de una UNIDAD con ese route_id (para los route_id sin nombre en
     *  RutasRepository, p. ej. circuitos/rutas nuevas). null si ninguna unidad da un nombre legible. */
    private static String recorridoDeUnidad(List<UnidadReal> data, String rid) {
        if (rid == null || data == null) return null;
        for (UnidadReal u : data) {
            if (!rid.equals(u.ruta)) continue;
            boolean o = u.origen != null && !u.origen.trim().isEmpty();
            boolean d = u.destino != null && !u.destino.trim().isEmpty();
            if (o && d) return u.origen + " → " + u.destino;
            if (d) return "→ " + u.destino;
            if (o) return u.origen + " →";
        }
        return null;
    }

    /** Línea de una UNIDAD con ese route_id (para etiquetar route_id sin nombre ni origen/destino). */
    private static Integer lineaDeUnidad(List<UnidadReal> data, String rid) {
        if (rid == null || data == null) return null;
        for (UnidadReal u : data) if (rid.equals(u.ruta) && u.linea != null) return u.linea;
        return null;
    }

    /** Etiqueta legible de una línea: "Línea N" (Metrobús), "Mexibús Lx" o "Mexicable Lx". */
    private String etiquetaLinea(int n) {
        if (n < 100) return getString(R.string.manifest_linea_fmt, String.valueOf(n));
        if (n < 200) return "Mexibús L" + Planificador.etiquetaLineaCortaPub(n);
        return "Mexicable L" + (n - 200);
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
