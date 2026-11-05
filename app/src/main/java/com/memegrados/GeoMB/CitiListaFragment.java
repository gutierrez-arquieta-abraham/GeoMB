package com.memegrados.GeoMB;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Estilo CITI: las pantallas de lista de la app de Indra (fragment_lista.xml / fragment_favoritos.xml):
 * "Seleccione ruta", "Estaciones cercanas" y "Favoritos".
 */
public class CitiListaFragment extends CitiPantalla {

    private static final String A_MODO = "modo", A_LINEAS = "lineas";
    private static final int RUTAS = 0, CERCANAS = 1, FAVORITOS = 2;
    /** Cuántas estaciones cercanas se listan. */
    private static final int N_CERCANAS = 20;

    private ListView lista;
    private boolean editando;

    /** "Seleccione ruta": {@code lineas} = las de una estación, o null para todas las activas. */
    public static CitiListaFragment rutas(@Nullable ArrayList<Integer> lineas) {
        Bundle b = new Bundle();
        b.putInt(A_MODO, RUTAS);
        if (lineas != null) b.putIntegerArrayList(A_LINEAS, lineas);
        return conArgs(b);
    }

    public static CitiListaFragment cercanas() {
        Bundle b = new Bundle();
        b.putInt(A_MODO, CERCANAS);
        return conArgs(b);
    }

    public static CitiListaFragment favoritos() {
        Bundle b = new Bundle();
        b.putInt(A_MODO, FAVORITOS);
        return conArgs(b);
    }

    private static CitiListaFragment conArgs(Bundle b) {
        CitiListaFragment f = new CitiListaFragment();
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.lista_citi, container, false);
        lista = v.findViewById(R.id.citi_lista);
        switch (requireArguments().getInt(A_MODO)) {
            case CERCANAS:
                barra(v, R.string.citi_cercanas);
                cargarCercanas();
                break;
            case FAVORITOS:
                barra(v, R.string.citi_favoritos);
                View editar = v.findViewById(R.id.citi_btn_editar);
                editar.setVisibility(View.VISIBLE);
                editar.setOnClickListener(x -> { editando = !editando; mostrarFavoritos(); });
                mostrarFavoritos();
                break;
            default:
                barra(v, R.string.citi_seleccione_ruta);
                mostrarRutas();
        }
        return v;
    }

    @Override
    public void onDestroyView() {
        lista = null;
        super.onDestroyView();
    }

    // --- Seleccione ruta (item_linea.xml) ---

    private void mostrarRutas() {
        List<Integer> lineas = requireArguments().getIntegerArrayList(A_LINEAS);
        if (lineas == null) {
            lineas = new ArrayList<>();
            for (Linea l : GtfsRepository.getRuteables(requireContext()))
                if (CitiDatos.esRutaListable(l.numero) && !lineas.contains(l.numero)) lineas.add(l.numero);
        }
        final List<Integer> ls = lineas;
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return ls.size(); }
            @Override public Object getItem(int i) { return ls.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_linea_citi, parent, false);
                int ln = ls.get(i);
                TextView cod = v.findViewById(R.id.citi_linea_codigo);
                cod.setBackground(cuadroRuta(ln));
                cod.setText(CitiDatos.etiqueta(ln));
                ((TextView) v.findViewById(R.id.citi_linea_nombre)).setText(CitiDatos.nombreRuta(requireContext(), ln));
                return v;
            }
        });
        lista.setOnItemClickListener((p, v, i, id) -> citi().menuRuta(ls.get(i)));
    }

    // --- Estaciones cercanas (item_parada.xml con "A N metros") ---

    @SuppressLint("MissingPermission")
    private void cargarCercanas() {
        boolean permiso = ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!permiso) { citi().aviso(getString(R.string.citi_sin_ubicacion)); return; }
        citi().mostrarDescargando(true);
        LocationServices.getFusedLocationProviderClient(requireActivity()).getLastLocation()
                .addOnCompleteListener(t -> {
                    if (!isAdded()) return;
                    citi().mostrarDescargando(false);
                    android.location.Location loc = t.isSuccessful() ? t.getResult() : null;
                    if (loc == null) { citi().aviso(getString(R.string.citi_sin_ubicacion)); return; }
                    mostrarCercanas(CitiDatos.cercanas(requireContext(),
                            new LatLng(loc.getLatitude(), loc.getLongitude()), N_CERCANAS));
                });
    }

    private void mostrarCercanas(final List<Object[]> items) {
        if (lista == null) return;
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_parada_citi, parent, false);
                CitiDatos.Est e = (CitiDatos.Est) items.get(i)[0];
                iconoEstacion(v.findViewById(R.id.citi_parada_icono), e, 28);
                ((TextView) v.findViewById(R.id.citi_parada_nombre)).setText(e.nombre);
                ((TextView) v.findViewById(R.id.citi_parada_dato)).setText(
                        getString(R.string.citi_a_metros, (Double) items.get(i)[1]));
                return v;
            }
        });
        lista.setOnItemClickListener((p, v, i, id) -> citi().menuEstacionLista((CitiDatos.Est) items.get(i)[0]));
    }

    // --- Favoritos (item_favorito.xml; "Editar" muestra el botón de borrar) ---

    private void mostrarFavoritos() {
        if (lista == null) return;
        final List<CitiDatos.Est> favs = CitiDatos.favoritas(requireContext());
        if (favs.isEmpty() && !editando) citi().aviso(getString(R.string.citi_sin_favoritos));
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return favs.size(); }
            @Override public Object getItem(int i) { return favs.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_favorito_citi, parent, false);
                CitiDatos.Est e = favs.get(i);
                iconoEstacion(v.findViewById(R.id.citi_fav_icono), e, 28);
                ((TextView) v.findViewById(R.id.citi_fav_nombre)).setText(e.nombre);
                ((TextView) v.findViewById(R.id.citi_fav_dato)).setText(sistemaDe(e.linea));
                ImageView borrar = v.findViewById(R.id.citi_fav_borrar);
                borrar.setVisibility(editando ? View.VISIBLE : View.GONE);
                borrar.setOnClickListener(x -> {
                    EstiloCiti.quitarFavorita(requireContext(), e.linea, e.nombre, e.pos);
                    mostrarFavoritos();
                });
                return v;
            }
        });
        lista.setOnItemClickListener((p, v, i, id) -> { if (!editando) citi().menuEstacionLista(favs.get(i)); });
    }
}
