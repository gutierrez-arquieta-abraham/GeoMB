package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Estilo CITI: una pantalla de GeoMB que la app de Indra no tenía (estado del servicio, reporte de
 * irregularidad, configuración, mis unidades) dentro de la barra "título / Menú" del estilo, para
 * que el estilo CITI conserve TODAS las funciones de la app.
 */
public class CitiContenedorFragment extends CitiPantalla {

    public static final int SERVICIO = 0, REPORTE = 1, CONFIGURACION = 2, MIS_UNIDADES = 3;
    private static final String A_TIPO = "tipo";

    public static CitiContenedorFragment de(int tipo) {
        Bundle b = new Bundle();
        b.putInt(A_TIPO, tipo);
        CitiContenedorFragment f = new CitiContenedorFragment();
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.contenedor_citi, container, false);
        int tipo = requireArguments().getInt(A_TIPO);
        int titulo;
        switch (tipo) {
            case REPORTE:       titulo = R.string.citi_reportar; break;
            case CONFIGURACION: titulo = R.string.citi_configuracion; break;
            case MIS_UNIDADES:  titulo = R.string.citi_mis_unidades; break;
            default:            titulo = R.string.citi_estado_servicio;
        }
        barra(v, titulo);
        if (savedInstanceState == null) {
            Fragment f;
            switch (tipo) {
                case REPORTE:       f = new ReporteFragment(); break;
                case CONFIGURACION: f = new ConfiguracionFragment(); break;
                case MIS_UNIDADES:  f = new UnidadesGuardadasFragment(); break;
                default:            f = new ServicioFragment();
            }
            getChildFragmentManager().beginTransaction().replace(R.id.citi_contenedor, f).commit();
        }
        return v;
    }
}
