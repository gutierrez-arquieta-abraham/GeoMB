package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pantalla principal: mapa con las 7 líneas del Metrobús (GTFS),
 * sus estaciones y las unidades EN TIEMPO REAL (feed del backend).
 */
public class MapFragment extends Fragment {

    private static final LatLng CDMX = new LatLng(19.41, -99.14);
    private static final float ZOOM_INICIAL = 11f;
    private static final float ZOOM_ESTACIONES = 12.5f;

    private GoogleMap mapa;
    private FusedLocationProviderClient locationClient;

    private final List<Marker> marcadoresEstacion = new ArrayList<>();
    private final Map<String, Marker> marcadoresUnidad = new HashMap<>();
    private final Map<Integer, Integer> coloresLinea = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean avisoError = false;

    /** Ciclo de actualización: pide el feed, actualiza marcadores y se reprograma. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override
                public void onData(List<UnidadReal> unidades) {
                    if (mapa != null) actualizarUnidades(unidades);
                }

                @Override
                public void onError(String mensaje) {
                    if (!avisoError && isAdded()) {
                        avisoError = true;
                        Toast.makeText(requireContext(),
                                "Sin conexión con el servidor de unidades", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            handler.postDelayed(this, Config.POLL_MS);
        }
    };

    private final ActivityResultLauncher<String> permisoUbicacion =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), otorgado -> {
                if (otorgado) {
                    irAMiUbicacion();
                } else {
                    Toast.makeText(requireContext(),
                            "Se necesita el permiso de ubicación", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        locationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_container);
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.map_container, mapFragment)
                    .commit();
        }
        mapFragment.getMapAsync(this::alMapaListo);

        view.findViewById(R.id.fab_ubicacion).setOnClickListener(v -> irAMiUbicacion());
        view.findViewById(R.id.search_bar).setOnClickListener(v ->
                ((MainActivity) requireActivity()).navegarA(R.id.nav_buscar));
    }

    private void alMapaListo(GoogleMap googleMap) {
        mapa = googleMap;
        mapa.getUiSettings().setZoomControlsEnabled(true);
        mapa.getUiSettings().setMapToolbarEnabled(false);
        mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(CDMX, ZOOM_INICIAL));
        activarCapaUbicacion();

        dibujarRed();
        aplicarSeleccionLinea();

        mapa.setOnCameraIdleListener(() -> {
            boolean visibles = mapa.getCameraPosition().zoom >= ZOOM_ESTACIONES;
            for (Marker m : marcadoresEstacion) m.setVisible(visibles);
        });

        // arranca el polling en vivo de inmediato
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    /** Dibuja polylines y estaciones de las 7 líneas y guarda sus colores. */
    private void dibujarRed() {
        for (Linea linea : GtfsRepository.getLineas(requireContext())) {
            coloresLinea.put(linea.numero, linea.color);

            mapa.addPolyline(new PolylineOptions()
                    .addAll(linea.ruta)
                    .color(linea.color)
                    .width(9f)
                    .geodesic(false));

            BitmapDescriptor punto = iconoEstacion(linea.color);
            for (Estacion e : linea.estaciones) {
                Marker m = mapa.addMarker(new MarkerOptions()
                        .position(e.posicion)
                        .title(e.nombre)
                        .snippet("Línea " + linea.numero)
                        .icon(punto)
                        .anchor(0.5f, 0.5f)
                        .visible(false));
                if (m != null) marcadoresEstacion.add(m);
            }
        }
    }

    /** Crea/mueve/elimina los marcadores de unidades según el feed. */
    private void actualizarUnidades(List<UnidadReal> unidades) {
        Set<String> vistos = new HashSet<>();

        for (UnidadReal u : unidades) {
            vistos.add(u.numero);
            Marker m = marcadoresUnidad.get(u.numero);
            if (m == null) {
                m = mapa.addMarker(new MarkerOptions()
                        .position(u.posicion)
                        .title("Unidad " + u.numero)
                        .snippet(snippet(u))
                        .icon(iconoUnidad(colorDeLinea(u.linea)))
                        .anchor(0.5f, 0.5f)
                        .zIndex(10f));
                if (m != null) marcadoresUnidad.put(u.numero, m);
            } else {
                m.setPosition(u.posicion);
                m.setSnippet(snippet(u));
            }
        }

        // quitar unidades que ya no están en servicio
        Iterator<Map.Entry<String, Marker>> it = marcadoresUnidad.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Marker> e = it.next();
            if (!vistos.contains(e.getKey())) {
                e.getValue().remove();
                it.remove();
            }
        }

        avisoError = false;
        aplicarSeleccionUnidad();
    }

    private String snippet(UnidadReal u) {
        String linea = u.linea != null ? "Línea " + u.linea : "Sin línea";
        return u.destino != null && !u.destino.isEmpty() ? linea + " · " + u.destino : linea;
    }

    private int colorDeLinea(Integer linea) {
        Integer c = linea != null ? coloresLinea.get(linea) : null;
        return c != null ? c : ContextCompat.getColor(requireContext(), R.color.mb_gray);
    }

    /** Centra el mapa en la unidad buscada, si viene una selección del buscador. */
    private void aplicarSeleccionUnidad() {
        if (RealtimeRepository.unidadSeleccionada == null) return;
        Marker m = marcadoresUnidad.get(RealtimeRepository.unidadSeleccionada);
        if (m != null) {
            mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(m.getPosition(), 15f));
            m.showInfoWindow();
            RealtimeRepository.unidadSeleccionada = null;
        }
    }

    /** Hace zoom al trazado de una línea, si viene selección de la lista. */
    private void aplicarSeleccionLinea() {
        if (RealtimeRepository.lineaSeleccionada == -1) return;
        Linea l = GtfsRepository.porNumero(requireContext(), RealtimeRepository.lineaSeleccionada);
        RealtimeRepository.lineaSeleccionada = -1;
        if (l != null) {
            mapa.animateCamera(CameraUpdateFactory.newLatLngBounds(l.limites(), 80));
        }
    }

    /** Punto circular pequeño con borde blanco (estación). */
    private BitmapDescriptor iconoEstacion(int color) {
        int d = (int) (12 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(d / 2f, d / 2f, d / 2f, p);
        p.setColor(color);
        c.drawCircle(d / 2f, d / 2f, d / 2f - d / 6f, p);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /** Círculo de color de línea con el ícono de autobús (unidad). */
    private BitmapDescriptor iconoUnidad(int color) {
        int d = (int) (30 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(d / 2f, d / 2f, d / 2f, p);
        p.setColor(color);
        c.drawCircle(d / 2f, d / 2f, d / 2f - d / 12f, p);
        Drawable bus = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            int margen = d / 5;
            bus.setBounds(margen, margen, d - margen, d - margen);
            bus.setTint(Color.WHITE);
            bus.draw(c);
        }
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private boolean tienePermisoUbicacion() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void activarCapaUbicacion() {
        if (mapa != null && tienePermisoUbicacion()) {
            mapa.setMyLocationEnabled(true);
            mapa.getUiSettings().setMyLocationButtonEnabled(false); // usamos nuestro FAB
        }
    }

    @SuppressLint("MissingPermission")
    private void irAMiUbicacion() {
        if (!tienePermisoUbicacion()) {
            permisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        activarCapaUbicacion();
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null && mapa != null) {
                        LatLng aqui = new LatLng(location.getLatitude(), location.getLongitude());
                        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(aqui, 15f));
                    }
                });
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(poll);
        marcadoresEstacion.clear();
        marcadoresUnidad.clear();
        coloresLinea.clear();
        mapa = null;
        super.onDestroyView();
    }
}
