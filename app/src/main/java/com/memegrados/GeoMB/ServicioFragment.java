package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;

/**
 * Une "Llegadas" y "Reportar" en una sola pestaña del menú inferior: una TabLayout alterna entre
 * LlegadasFragment y ReporteFragment como fragments hijos, sin tocar su lógica interna.
 */
// ============================================================
// CLASE    : ServicioFragment   (extends Fragment)
// PROYECTO : GeoMB
// ============================================================
public class ServicioFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_servicio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TabLayout tabs = view.findViewById(R.id.tabs_servicio);
        if (savedInstanceState == null) mostrar(new LlegadasFragment());
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                mostrar(tab.getPosition() == 0 ? new LlegadasFragment() : new ReporteFragment());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void mostrar(Fragment f) {
        if (!isAdded()) return;
        getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.servicio_container, f)
                .commitAllowingStateLoss();
    }
}
