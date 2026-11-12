package com.memegrados.GeoMB;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Estilo CITI: Información (fragment_info.xml), con los datos de GeoMB y la salida del estilo. */
public class CitiInfoFragment extends CitiPantalla {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.info_citi, container, false);
        barra(v, R.string.citi_informacion);
        v.findViewById(R.id.citi_info_servicio).setOnClickListener(x -> citi().abrirGeoMB(CitiContenedorFragment.SERVICIO));
        v.findViewById(R.id.citi_info_reportar).setOnClickListener(x -> citi().abrirGeoMB(CitiContenedorFragment.REPORTE));
        v.findViewById(R.id.citi_info_unidades).setOnClickListener(x -> citi().abrirGeoMB(CitiContenedorFragment.MIS_UNIDADES));
        v.findViewById(R.id.citi_info_config).setOnClickListener(x -> citi().abrirGeoMB(CitiContenedorFragment.CONFIGURACION));
        v.findViewById(R.id.citi_info_privacidad).setOnClickListener(x -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Config.PRIVACY_URL))); }
            catch (Exception ignore) {}
        });
        v.findViewById(R.id.citi_info_salir).setOnClickListener(x -> citi().salirDelEstilo());
        return v;
    }
}
