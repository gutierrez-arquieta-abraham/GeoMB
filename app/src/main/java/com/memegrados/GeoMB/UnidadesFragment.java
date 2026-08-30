package com.memegrados.GeoMB;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Listado de unidades en servicio de una línea (o todas si línea = -1),
 * respetando los filtros activos. Se refresca en vivo.
 */
public class UnidadesFragment extends Fragment {

    private static final String ARG_LINEA = "linea";
    private static final String ARG_CODIGO = "codigo";
    private static final String ARG_TITULO = "titulo";

    private int linea;
    private int codigo = -1;      // >0 => filtra por ruta (código)
    private String titulo;        // título opcional (nombre de la ruta)
    private UnidadesAdapter adapter;
    private TextView vacio;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static UnidadesFragment nueva(int linea) {
        UnidadesFragment f = new UnidadesFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_LINEA, linea);
        f.setArguments(b);
        return f;
    }

    /** Unidades de una ruta concreta (línea + código), reusando este mismo layout. */
    public static UnidadesFragment nuevaRuta(int linea, int codigo, String titulo) {
        UnidadesFragment f = new UnidadesFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_LINEA, linea);
        b.putInt(ARG_CODIGO, codigo);
        b.putString(ARG_TITULO, titulo);
        f.setArguments(b);
        return f;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override public void onData(List<UnidadReal> u) { if (isAdded()) refrescar(); }
                @Override public void onError(String m) { }
            });
            handler.postDelayed(this, Red.intervalo(getContext(), Config.POLL_MS));
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_unidades, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        linea = getArguments() != null ? getArguments().getInt(ARG_LINEA, -1) : -1;
        codigo = getArguments() != null ? getArguments().getInt(ARG_CODIGO, -1) : -1;
        titulo = getArguments() != null ? getArguments().getString(ARG_TITULO) : null;

        TextView tituloView = view.findViewById(R.id.txt_titulo);
        if (titulo != null && !titulo.isEmpty()) tituloView.setText(titulo);
        else tituloView.setText(linea > 0 ? getString(R.string.unidades_de_linea, linea)
                : getString(R.string.unidades_en_servicio));
        vacio = view.findViewById(R.id.txt_vacio);

        RecyclerView rv = view.findViewById(R.id.recycler_unidades);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new UnidadesAdapter(u -> {
            RealtimeRepository.unidadSeleccionada = u.numero;
            ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
        });
        rv.setAdapter(adapter);

        refrescar();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private void refrescar() {
        List<UnidadReal> lista = linea > 0
                ? RealtimeRepository.get().deLinea(linea)
                : RealtimeRepository.get().filtradas();
        if (codigo > 0) {   // filtra a las unidades de esta ruta (código)
            List<UnidadReal> deRuta = new java.util.ArrayList<>();
            for (UnidadReal u : lista) {
                Ruta r = RutasRepository.porRouteId(u.ruta);
                if (r != null && r.codigo == codigo) deRuta.add(u);
            }
            lista = deRuta;
        }
        adapter.set(lista);
        vacio.setVisibility(lista.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(poll);
        super.onDestroyView();
    }
}
