package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Estilo CITI: "Próximo autobús" (FragmentBuscarParada / fragment_buscar_parada.xml de la app de
 * Indra, sin el botón QR): se escribe la estación y aparecen sus botones Ver tiempos / Ver rutas /
 * Ver en mapa / Añadir a favoritos.
 */
public class CitiBuscarFragment extends CitiPantalla {

    private View raiz;
    private CitiDatos.Est sel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        raiz = inflater.inflate(R.layout.buscar_citi, container, false);
        barra(raiz, R.string.citi_buscar_estacion);
        final List<CitiDatos.Est> estaciones = CitiDatos.unicas(requireContext());
        List<String> textos = new ArrayList<>();
        for (CitiDatos.Est e : estaciones) textos.add(e.nombre + " (" + sistemaDe(e.linea) + ")");
        AutoCompleteTextView campo = raiz.findViewById(R.id.citi_buscar_texto);
        campo.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, textos));
        campo.setOnItemClickListener((p, v, i, id) -> {
            String elegido = (String) p.getItemAtPosition(i);
            int k = textos.indexOf(elegido);
            if (k >= 0) seleccionar(estaciones.get(k));
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(campo.getWindowToken(), 0);
        });
        return raiz;
    }

    @Override
    public void onDestroyView() {
        raiz = null;
        super.onDestroyView();
    }

    private void seleccionar(CitiDatos.Est e) {
        sel = e;
        iconoEstacion(raiz.findViewById(R.id.citi_buscar_icono), e, 32);
        ((TextView) raiz.findViewById(R.id.citi_buscar_estacion)).setText(e.nombre);
        LinearLayout botones = raiz.findViewById(R.id.citi_buscar_botones);
        botones.removeAllViews();
        agregar(botones, R.string.citi_ver_tiempos, () -> citi().abrir(CitiTiemposFragment.de(e)));
        agregar(botones, R.string.citi_ver_rutas, () -> citi().abrirRutasDe(e));
        agregar(botones, R.string.citi_ver_en_mapa, () -> citi().verEstacionEnMapa(e));
        agregar(botones, R.string.citi_como_llegar, () -> citi().abrir(CitiTrayectoFragment.hacia(e)));
        if (!EstiloCiti.esFavorita(requireContext(), e.linea, e.nombre, e.pos))
            agregar(botones, R.string.citi_anadir_favoritos, () -> { citi().agregarFavorita(e); seleccionar(e); });
        raiz.findViewById(R.id.citi_buscar_resultado).setVisibility(View.VISIBLE);
    }

    private void agregar(LinearLayout cont, int texto, Runnable accion) {
        TextView b = (TextView) getLayoutInflater().inflate(R.layout.boton_citi_menu, cont, false);
        b.setText(texto);
        b.setOnClickListener(v -> accion.run());
        cont.addView(b);
    }
}
