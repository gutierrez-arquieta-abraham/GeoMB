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

    private static final String A_MODO = "modo", A_LINEAS = "lineas", A_LINEA = "linea",
            A_ESTACIONES = "estaciones", A_ORIGEN = "origen", A_DESTINO = "destino";
    private static final int RUTAS = 0, CERCANAS = 1, FAVORITOS = 2, ESTACIONES = 3;
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

    /** "Seleccione ruta" de una estación ("Ver rutas"): los servicios que paran ahí, de cada una
     *  de sus líneas ({@code lineas}[i] con el nombre de su andén {@code estaciones}[i]). */
    public static CitiListaFragment rutasDe(ArrayList<Integer> lineas, ArrayList<String> estaciones) {
        Bundle b = new Bundle();
        b.putInt(A_MODO, RUTAS);
        b.putIntegerArrayList(A_LINEAS, lineas);
        b.putStringArrayList(A_ESTACIONES, estaciones);
        return conArgs(b);
    }

    /** "Seleccione estación" (title_paradas): las estaciones de una ruta, en su orden. */
    public static CitiListaFragment estaciones(int linea) {
        return estaciones(linea, null, null);
    }

    /** Las estaciones de un SERVICIO (p. ej. Indios Verdes-Insurgentes): solo su tramo de la línea. */
    public static CitiListaFragment estaciones(int linea, @Nullable String origen, @Nullable String destino) {
        Bundle b = new Bundle();
        b.putInt(A_MODO, ESTACIONES);
        b.putInt(A_LINEA, linea);
        if (origen != null) { b.putString(A_ORIGEN, origen); b.putString(A_DESTINO, destino); }
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
            case ESTACIONES:
                barra(v, R.string.citi_seleccione_estacion);
                mostrarEstaciones(requireArguments().getInt(A_LINEA),
                        requireArguments().getString(A_ORIGEN), requireArguments().getString(A_DESTINO));
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

    /** Una fila: cuadro de la línea, "Origen-Destino" y, si es un servicio de horarios.json, el
     *  servicio (null = la línea completa, p. ej. Mexibús/Mexicable, que no traen servicios). */
    private static final class FilaRuta {
        final int linea; final String nombre; final Horarios.Ruta servicio;
        FilaRuta(int linea, String nombre, Horarios.Ruta servicio) { this.linea = linea; this.nombre = nombre; this.servicio = servicio; }
    }

    /** Como "Seleccione ruta" del original: los SERVICIOS (Indios Verdes-El Caminero, Indios
     *  Verdes-Insurgentes, Indios Verdes-Dr. Gálvez, …), no solo una fila por línea. Desde el inicio,
     *  todos; desde una estación, los que paran en ella. */
    private void mostrarRutas() {
        List<Integer> lineas = requireArguments().getIntegerArrayList(A_LINEAS);
        List<String> ests = requireArguments().getStringArrayList(A_ESTACIONES);
        if (lineas == null) {
            lineas = new ArrayList<>();
            for (Linea l : GtfsRepository.getRuteables(requireContext()))
                if (CitiDatos.esRutaListable(l.numero) && !lineas.contains(l.numero)) lineas.add(l.numero);
        }
        final List<FilaRuta> filas = new ArrayList<>();
        java.util.Set<String> vistas = new java.util.HashSet<>();   // una mixta aparece en sus 2 líneas
        for (int i = 0; i < lineas.size(); i++) {
            int ln = lineas.get(i);
            String est = ests != null && i < ests.size() ? ests.get(i) : null;
            List<Horarios.Ruta> servicios = est != null
                    ? Horarios.quePasanPor(requireContext(), ln, est) : Horarios.deLinea(requireContext(), ln);
            if (servicios.isEmpty()) {
                filas.add(new FilaRuta(ln, CitiDatos.nombreRuta(requireContext(), ln), null));
                continue;
            }
            for (Horarios.Ruta r : servicios) {
                if (!vistas.add(r.origen + "|" + r.destino + "|" + r.variante)) continue;
                String nombre = r.origen + "-" + r.destino + (r.variante != null ? " (" + r.variante + ")" : "");
                filas.add(new FilaRuta(r.linea, nombre, r));
            }
        }
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return filas.size(); }
            @Override public Object getItem(int i) { return filas.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_linea_citi, parent, false);
                FilaRuta f = filas.get(i);
                TextView cod = v.findViewById(R.id.citi_linea_codigo);
                cod.setBackground(cuadroRuta(f.linea));
                cod.setText(CitiDatos.etiqueta(f.linea));
                ((TextView) v.findViewById(R.id.citi_linea_nombre)).setText(f.nombre);
                return v;
            }
        });
        lista.setOnItemClickListener((p, v, i, id) -> {
            FilaRuta f = filas.get(i);
            if (f.servicio != null) citi().menuServicio(f.linea, f.servicio);
            else citi().menuRuta(f.linea);
        });
    }

    // --- Estaciones de una ruta (item_parada.xml) ---

    private void mostrarEstaciones(int linea, @Nullable String origen, @Nullable String destino) {
        final List<CitiDatos.Est> ests = new ArrayList<>();
        java.util.Set<String> vistas = new java.util.HashSet<>();
        for (CitiDatos.Est e : CitiDatos.todas(requireContext()))   // ya vienen en el orden de la línea
            if (e.linea == linea && vistas.add(Planificador.norm(e.nombre))) ests.add(e);
        // Servicio (p. ej. Indios Verdes-Insurgentes): solo las estaciones de su tramo.
        Horarios.Ruta r = origen != null ? Horarios.buscar(requireContext(), linea, origen, destino) : null;
        int[] tramo = r != null ? Horarios.tramoEnLinea(requireContext(), linea, r) : null;
        if (tramo != null) {
            Linea l = GtfsRepository.porNumero(requireContext(), linea);
            List<CitiDatos.Est> enTramo = new ArrayList<>();
            for (CitiDatos.Est e : ests) {
                int k = l != null ? Horarios.idxEnLinea(l, e.canon) : -1;
                if (k < 0 || (k >= tramo[0] && k <= tramo[1])) enTramo.add(e);
            }
            ests.clear();
            ests.addAll(enTramo);
        }
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return ests.size(); }
            @Override public Object getItem(int i) { return ests.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_parada_citi, parent, false);
                CitiDatos.Est e = ests.get(i);
                iconoEstacion(v.findViewById(R.id.citi_parada_icono), e, 28);
                ((TextView) v.findViewById(R.id.citi_parada_nombre)).setText(e.nombre);
                ((TextView) v.findViewById(R.id.citi_parada_dato)).setText(sistemaDe(e.linea));
                return v;
            }
        });
        lista.setOnItemClickListener((p, v, i, id) -> citi().menuEstacionLista(ests.get(i)));
    }

    // --- Estaciones cercanas (item_parada.xml con "A N metros") ---

    @SuppressLint("MissingPermission")
    private void cargarCercanas() {
        boolean permiso = ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!permiso) { citi().conUbicacion(() -> { if (isAdded()) cargarCercanas(); }); return; }
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
