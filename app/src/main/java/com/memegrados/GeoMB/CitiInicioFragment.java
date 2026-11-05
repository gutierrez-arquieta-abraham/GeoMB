package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Estilo CITI: menú de inicio de 8 botones (fragment_menu.xml de la app de Indra), con los
 * íconos originales. Cada botón abre su pantalla con los datos y funciones de GeoMB.
 */
public class CitiInicioFragment extends CitiPantalla {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.inicio_citi, container, false);
        LinearLayout filas = v.findViewById(R.id.citi_inicio_filas);
        // Mismo orden y filas (3 · 3 · 2) que el original.
        Object[][] botones = {
                {R.drawable.citi_inicio_stops, R.string.citi_proximo_autobus, (Runnable) () -> citi().abrir(new CitiBuscarFragment())},
                {R.drawable.citi_inicio_routes, R.string.citi_rutas, (Runnable) () -> citi().abrir(CitiListaFragment.rutas(null))},
                {R.drawable.citi_inicio_map, R.string.citi_mapa, (Runnable) () -> citi().abrirMapa()},
                {R.drawable.citi_inicio_mitrayecto, R.string.citi_mi_trayecto, (Runnable) () -> citi().abrir(CitiTrayectoFragment.hacia(null))},
                {R.drawable.citi_inicio_nearstations, R.string.citi_cercanas, (Runnable) () -> citi().abrir(CitiListaFragment.cercanas())},
                {R.drawable.citi_inicio_recpoints, R.string.citi_puntos_recarga, (Runnable) () -> {
                    citi().abrirMapa();
                    citi().aviso(getString(R.string.citi_recarga_aviso));
                }},
                {R.drawable.citi_inicio_favs, R.string.citi_favoritos, (Runnable) () -> citi().abrir(CitiListaFragment.favoritos())},
                {R.drawable.citi_inicio_info, R.string.citi_informacion, (Runnable) () -> citi().abrir(new CitiInfoFragment())},
        };
        int[] porFila = {3, 3, 2};
        int i = 0;
        for (int n : porFila) {
            LinearLayout fila = new LinearLayout(requireContext());
            fila.setGravity(android.view.Gravity.CENTER);
            for (int k = 0; k < n; k++, i++) {
                Object[] b = botones[i];
                View btn = inflater.inflate(R.layout.boton_inicio_citi, fila, false);
                View icono = btn.findViewById(R.id.citi_inicio_icono);
                icono.setBackgroundResource((Integer) b[0]);
                ((TextView) btn.findViewById(R.id.citi_inicio_texto)).setText((Integer) b[1]);
                Runnable accion = (Runnable) b[2];
                icono.setOnClickListener(x -> accion.run());
                fila.addView(btn);
            }
            filas.addView(fila);
        }
        return v;
    }
}
