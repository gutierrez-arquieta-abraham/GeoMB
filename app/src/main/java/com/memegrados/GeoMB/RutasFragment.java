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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rutas por código: lista las rutas de cada línea (código + recorrido
 * origen ↔ destino) derivadas del GTFS del backend, con cuántas unidades
 * hay en servicio en cada una.
 */
public class RutasFragment extends Fragment {

    private RecyclerView recycler;
    private TextView estado;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int intentos = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rutas, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recycler = view.findViewById(R.id.recycler_rutas);
        estado = view.findViewById(R.id.txt_rutas_estado);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        RutasRepository.init();
        // Trae el feed para contar unidades por ruta.
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> u) { intentarConstruir(); }
            @Override public void onError(String m) { intentarConstruir(); }
        });
        intentarConstruir();
    }

    private void intentarConstruir() {
        if (!isAdded()) return;
        if (!RutasRepository.estaCargado()) {
            if (intentos++ < 15) {
                handler.postDelayed(this::intentarConstruir, 700);
            } else {
                estado.setText(R.string.rutas_vacio);
                estado.setVisibility(View.VISIBLE);
            }
            return;
        }
        construir();
    }

    private void construir() {
        Map<String, Integer> conteo = contarPorRuta();  // "linea:codigo" -> n
        List<RutasAdapter.Fila> filas = new ArrayList<>();

        for (int linea = 1; linea <= 7; linea++) {
            List<Ruta> rutas = RutasRepository.deLinea(linea);
            if (rutas.isEmpty()) continue;

            Linea l = GtfsRepository.porNumero(requireContext(), linea);
            int color = l != null ? l.color : 0xFFD40D0D;
            String nombre = l != null ? l.nombre : "";
            filas.add(RutasAdapter.Fila.header(color,
                    getString(R.string.linea_formato, linea) + (nombre.isEmpty() ? "" : " · " + nombre)));

            // un renglón por código (ida y vuelta comparten código)
            LinkedHashMap<Integer, Ruta> porCodigo = new LinkedHashMap<>();
            for (Ruta r : rutas) if (!porCodigo.containsKey(r.codigo)) porCodigo.put(r.codigo, r);
            for (Ruta r : porCodigo.values()) {
                String recorrido = r.origen + " ↔ " + r.destino;
                Integer n = conteo.get(linea + ":" + r.codigo);
                filas.add(RutasAdapter.Fila.ruta(color, r.linea, r.codigo, recorrido, n != null ? n : 0));
            }
        }

        if (filas.isEmpty()) {
            estado.setText(R.string.rutas_vacio);
            estado.setVisibility(View.VISIBLE);
        } else {
            estado.setVisibility(View.GONE);
            recycler.setAdapter(new RutasAdapter(filas, (linea, codigo, recorrido) ->
                    ((MainActivity) requireActivity()).mostrarUnidadesRuta(linea, codigo, recorrido)));
        }
    }

    /** Cuenta unidades en servicio por "linea:codigo". */
    private Map<String, Integer> contarPorRuta() {
        Map<String, Integer> m = new HashMap<>();
        for (UnidadReal u : RealtimeRepository.get().getUltimo()) {
            Ruta r = RutasRepository.porRouteId(u.ruta);
            if (r == null) continue;
            String k = r.linea + ":" + r.codigo;
            Integer v = m.get(k);
            m.put(k, v != null ? v + 1 : 1);
        }
        return m;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
