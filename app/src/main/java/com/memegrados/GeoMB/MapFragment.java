package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.PatternItem;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
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
public class MapFragment extends Fragment implements FiltrosSheet.Host {

    private static final LatLng CDMX = new LatLng(19.41, -99.14);
    private static final float ZOOM_INICIAL = 11f;
    private static final float ZOOM_ESTACIONES = 12.5f;
    private static final float ZOOM_CERCANO = 14f;        // ~2 km a la vista al iniciar
    private static final double RADIO_MAPA_M = 2000.0;    // radio máx. de carga (estaciones/unidades)

    private GoogleMap mapa;
    private RedViewModel red;   // capa de datos de la red (Metrobús + Mexibús) vía LiveData
    private FusedLocationProviderClient locationClient;
    private TextView chipFiltros;
    private TextView txtConteo;

    private final List<Marker> marcadoresEstacion = new ArrayList<>();
    private final Map<String, Marker> marcadoresUnidad = new HashMap<>();
    private final Map<Integer, Integer> coloresLinea = new HashMap<>();

    /** Datos de todas las estaciones; sus marcadores se crean por demanda (radio). */
    private static final class EstMapa {
        final Estacion e; final int linea; final int color; Marker marker;
        LatLng pos; String titulo;   // posición/título propios (p. ej. andenes sur/norte de Indios Verdes)
        EstMapa(Estacion e, int linea, int color) {
            this.e = e; this.linea = linea; this.color = color;
            this.pos = e.posicion; this.titulo = e.nombre;
        }
    }
    private final List<EstMapa> estaciones = new ArrayList<>();
    // Capa Mexibús (líneas + estaciones); su visibilidad la controla "Mostrar Mexibús" (Acerca de).
    private final List<Polyline> mexibusLineas = new ArrayList<>();
    private final List<EstMapa> mexibusEst = new ArrayList<>();
    private LatLng centroCarga = null;   // centro del área cargada (null = aún sin ubicar)
    private boolean trafico = false;
    private boolean mostrarEstaciones = true;
    private boolean mostrarUnidades = true;
    private boolean vista3d = false;
    private int tipoMapa = GoogleMap.MAP_TYPE_NORMAL;
    private final Map<String, Long> animToken = new HashMap<>();
    private long animSeq = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean avisoError = false;

    /** Ciclo de actualización: pide el feed, actualiza marcadores y se reprograma. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override
                public void onData(List<UnidadReal> unidades) {
                    avisoError = false;   // servidor OK de nuevo: permite reactivar el respaldo si vuelve a fallar
                    if (mapa != null) actualizarUnidades(unidades);
                }

                @Override
                public void onError(String mensaje) {
                    if (!avisoError && isAdded()) {
                        avisoError = true;
                        Toast.makeText(requireContext(),
                                "Sin conexión con el servidor de unidades", Toast.LENGTH_SHORT).show();
                        // El servidor (EC2/SONDA) falló: activa el sondeo de respaldo automáticamente
                        // (si el usuario no lo tenía en manual). Se apagará solo cuando el servidor vuelva.
                        if (!Modos.sincronizacionFondo(requireContext()) && !SincronizacionService.activo)
                            SincronizacionService.iniciar(requireContext());
                    }
                }
            });
            handler.postDelayed(this, Red.intervalo(getContext(), Modos.mapaRefrescoMs(getContext())));
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

        // La red (Metrobús + Mexibús) se carga a través de un ViewModel: el parseo en streaming corre en
        // el executor del RedViewModel (2º plano) y se publica por LiveData. Al observar con
        // getViewLifecycleOwner(), la suscripción se cancela sola si el usuario cierra el fragment antes
        // de que termine la carga → sin fugas de memoria ni dibujos sobre vistas destruidas.
        red = new ViewModelProvider(this).get(RedViewModel.class);
        red.getMetrobus();   // dispara la carga en 2º plano ya (aunque el mapa aún no esté listo)
        red.getMexibus();

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

        view.findViewById(R.id.btn_ubicacion).setOnClickListener(v -> irAMiUbicacion());

        // Buscador inline: busca el económico y centra el mapa en la unidad.
        EditText inputMapa = view.findViewById(R.id.input_mapa);
        inputMapa.setOnEditorActionListener((v, actionId, event) -> {
            buscarEnMapa(inputMapa.getText().toString());
            return true;
        });
        view.findViewById(R.id.search_icon).setOnClickListener(v ->
                buscarEnMapa(inputMapa.getText().toString()));

        chipFiltros = view.findViewById(R.id.chip_filtros);
        txtConteo = view.findViewById(R.id.txt_conteo);
        view.findViewById(R.id.fab_filtros).setOnClickListener(v ->
                new FiltrosSheet().show(getChildFragmentManager(), "filtros"));
        chipFiltros.setOnClickListener(v -> {
            RealtimeRepository.filtro.limpiar();
            onFiltrosCambiados();
        });
        actualizarChip();

        view.findViewById(R.id.btn_trafico).setOnClickListener(v -> alternarTrafico());
        view.findViewById(R.id.btn_tipo).setOnClickListener(v -> alternarTipo());
        view.findViewById(R.id.btn_centrar).setOnClickListener(v -> ajustarRed());
        view.findViewById(R.id.btn_zoom_in).setOnClickListener(v -> {
            if (mapa != null) mapa.animateCamera(CameraUpdateFactory.zoomIn());
        });
        view.findViewById(R.id.btn_zoom_out).setOnClickListener(v -> {
            if (mapa != null) mapa.animateCamera(CameraUpdateFactory.zoomOut());
        });
        view.findViewById(R.id.btn_norte).setOnClickListener(v -> orientarNorte());
        view.findViewById(R.id.btn_estaciones).setOnClickListener(v -> alternarEstaciones());
        view.findViewById(R.id.btn_unidades).setOnClickListener(v -> alternarUnidades());
        view.findViewById(R.id.btn_iconos).setOnClickListener(v -> alternarIconos());
        actualizarLogoIconos(Modos.iconosNuevos(requireContext()));   // logo inicial del botón según el modo guardado
        view.findViewById(R.id.btn_3d).setOnClickListener(v -> alternar3d());
    }

    /** Muestra u oculta los iconos (pictogramas) de las estaciones. */
    private void alternarEstaciones() {
        mostrarEstaciones = !mostrarEstaciones;
        aplicarVisibilidadEstaciones();
        Toast.makeText(requireContext(),
                getString(mostrarEstaciones ? R.string.mapa_estaciones_on
                                            : R.string.mapa_estaciones_off),
                Toast.LENGTH_SHORT).show();
    }

    /** Logo del botón de iconos: Movimex "B" en modo nuevo, "M" verde (CDMX/Mexibús) en modo antiguo. */
    private void actualizarLogoIconos(boolean nuevos) {
        android.view.View v = getView();
        if (v == null) return;
        android.widget.ImageButton b = v.findViewById(R.id.btn_iconos);
        if (b == null) return;
        b.setImageTintList(null);   // logos a color: sin tinte
        b.setImageResource(nuevos ? R.drawable.ic_mexibus_nuevo : R.drawable.ic_mexibus_ant);
    }

    /** Muestra u oculta las unidades (vehículos) en tiempo real del mapa. */
    private void alternarUnidades() {
        mostrarUnidades = !mostrarUnidades;
        if (!mostrarUnidades) {
            for (Marker m : marcadoresUnidad.values()) m.remove();
            marcadoresUnidad.clear();
        } else if (mapa != null) {
            actualizarUnidades(RealtimeRepository.get().getUltimo());
        }
        Toast.makeText(requireContext(),
                getString(mostrarUnidades ? R.string.mapa_unidades_on : R.string.mapa_unidades_off),
                Toast.LENGTH_SHORT).show();
    }

    /** Aplica la regla de visibilidad de estaciones (toggle + zoom). */
    private void aplicarVisibilidadEstaciones() {
        if (mapa == null) return;
        boolean porZoom = mapa.getCameraPosition().zoom >= ZOOM_ESTACIONES;
        boolean visibles = mostrarEstaciones && porZoom;
        for (Marker m : marcadoresEstacion) m.setVisible(visibles);
    }

    /** Alterna la vista 3D: inclina la cámara y activa edificios. */
    private void alternar3d() {
        if (mapa == null) return;
        vista3d = !vista3d;
        mapa.setBuildingsEnabled(vista3d);
        CameraPosition c = new CameraPosition.Builder(mapa.getCameraPosition())
                .tilt(vista3d ? 55f : 0f).build();
        mapa.animateCamera(CameraUpdateFactory.newCameraPosition(c));
    }

    /** Regresa la cámara a norte arriba (bearing y tilt 0). */
    private void orientarNorte() {
        if (mapa == null) return;
        CameraPosition c = new CameraPosition.Builder(mapa.getCameraPosition())
                .bearing(0).tilt(0).build();
        mapa.animateCamera(CameraUpdateFactory.newCameraPosition(c));
    }

    /** ¿El dispositivo está en modo oscuro? */
    private boolean esNoche() {
        int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Muestra/oculta la capa de nivel de tráfico. */
    private void alternarTrafico() {
        if (mapa == null) return;
        trafico = !trafico;
        mapa.setTrafficEnabled(trafico);
    }

    /** Cicla el tipo de mapa: normal → satélite → híbrido. */
    private void alternarTipo() {
        if (mapa == null) return;
        if (tipoMapa == GoogleMap.MAP_TYPE_NORMAL) tipoMapa = GoogleMap.MAP_TYPE_SATELLITE;
        else if (tipoMapa == GoogleMap.MAP_TYPE_SATELLITE) tipoMapa = GoogleMap.MAP_TYPE_HYBRID;
        else tipoMapa = GoogleMap.MAP_TYPE_NORMAL;
        mapa.setMapType(tipoMapa);
        Toast.makeText(requireContext(), nombreTipo(tipoMapa), Toast.LENGTH_SHORT).show();
    }

    private String nombreTipo(int t) {
        if (t == GoogleMap.MAP_TYPE_SATELLITE) return getString(R.string.mapa_tipo_satelite);
        if (t == GoogleMap.MAP_TYPE_HYBRID) return getString(R.string.mapa_tipo_hibrido);
        return getString(R.string.mapa_tipo_normal);
    }

    /** Ajusta la cámara para ver toda la red del Metrobús. */
    private void ajustarRed() {
        if (mapa == null) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        boolean hay = false;
        for (Linea l : GtfsRepository.getLineas(requireContext())) {
            for (LatLng p : l.ruta) { b.include(p); hay = true; }
        }
        if (hay) mapa.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80));
        else mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(CDMX, ZOOM_INICIAL));
    }

    /** Callback de FiltrosSheet: re-dibuja con los filtros nuevos. */
    @Override
    public void onFiltrosCambiados() {
        if (mapa != null) actualizarUnidades(RealtimeRepository.get().getUltimo());
        actualizarChip();
    }

    private void actualizarChip() {
        if (chipFiltros == null) return;
        int n = RealtimeRepository.filtro.activos();
        if (n > 0) {
            chipFiltros.setText(getString(R.string.filtros_activos_n, n));
            chipFiltros.setVisibility(View.VISIBLE);
        } else {
            chipFiltros.setVisibility(View.GONE);
        }
    }

    private void actualizarConteo(int mostradas) {
        if (txtConteo != null) txtConteo.setText(getString(R.string.unidades_conteo, mostradas));
    }

    private void alMapaListo(GoogleMap googleMap) {
        mapa = googleMap;
        mapa.getUiSettings().setZoomControlsEnabled(false);   // usamos botones propios
        mapa.getUiSettings().setCompassEnabled(false);        // reemplazada por btn_norte (brújula propia)
        mapa.getUiSettings().setMapToolbarEnabled(false);
        // El botón de norte actúa como brújula: rota según el bearing de la cámara.
        final View btnNorte = getView() != null ? getView().findViewById(R.id.btn_norte) : null;
        if (btnNorte != null) mapa.setOnCameraMoveListener(() ->
                btnNorte.setRotation(-mapa.getCameraPosition().bearing));
        mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(CDMX, ZOOM_CERCANO));
        mapa.setTrafficEnabled(trafico);
        mapa.setMapType(tipoMapa);
        if (esNoche()) {   // sigue el tema del dispositivo automáticamente
            mapa.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark));
        }
        activarCapaUbicacion();

        // Ventana de info (al seleccionar estación o unidad) con tipografía Tipo Metro.
        mapa.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(com.google.android.gms.maps.model.Marker m) { return null; }
            @Override public View getInfoContents(com.google.android.gms.maps.model.Marker m) {
                View v = getLayoutInflater().inflate(R.layout.map_info_window, null, false);
                TextView t = v.findViewById(R.id.info_titulo);
                t.setText(m.getTitle());
                Tipografia.aplicar(t);
                TextView s = v.findViewById(R.id.info_sub);
                String snip = m.getSnippet();
                if (snip != null && !snip.isEmpty()) { s.setText(snip); Tipografia.aplicar(s); s.setVisibility(View.VISIBLE); }
                else s.setVisibility(View.GONE);
                return v;
            }
        });

        // El trazado se hace cuando la red ya está en memoria, observando el LiveData del ViewModel.
        // getViewLifecycleOwner() garantiza que estos callbacks NO se ejecutan tras destruir la vista
        // (sin fugas). getLineas()/getMexibus() dentro de dibujar* son lecturas lock-free ya publicadas.
        red.getMetrobus().observe(getViewLifecycleOwner(), lineas -> {
            if (mapa == null) return;
            dibujarRed();
            aplicarSeleccionLinea();
            crearEstacionesVisibles();
            aplicarVisibilidadEstaciones();
        });
        red.getMexibus().observe(getViewLifecycleOwner(), lineas -> {
            if (mapa == null) return;
            dibujarMexibus();
            crearMexibusVisibles();
            aplicarVisibilidadEstaciones();
        });
        if (RealtimeRepository.unidadSeleccionada != null) {
            // Viene una unidad del buscador: se centra en ELLA, NO en la estación cercana. Así el
            // callback asíncrono de ubicación de centrarEnCercana no sobrescribe la cámara dejándote
            // en la estación (esa era la causa de que "ver en mapa" no llevara a la unidad).
            UnidadReal u = RealtimeRepository.get().buscar(RealtimeRepository.unidadSeleccionada);
            aplicarCentro(u != null ? u.posicion : CDMX);
        } else {
            centrarEnCercana();   // centra en la estación más cercana y carga solo ese radio
        }

        mapa.setOnCameraIdleListener(() -> {
            crearEstacionesVisibles();                 // carga estaciones al explorar
            aplicarVisibilidadEstaciones();
            crearMexibusVisibles();                    // crea marcadores Mexibús por demanda (no todos al inicio)
            aplicarMexibus();                          // oculta/mostrar estaciones Mexibús por zoom
            List<UnidadReal> ultimo = RealtimeRepository.get().getUltimo();
            if (ultimo != null) actualizarUnidades(ultimo);   // carga unidades del rango visible
        });

        // arranca el polling en vivo de inmediato
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    /** Dibuja polylines y estaciones de las 7 líneas y guarda sus colores. */
    private void dibujarRed() {
        for (Linea linea : GtfsRepository.getLineas(requireContext())) {
            coloresLinea.put(linea.numero, linea.color);

            if (linea.segmentos != null && !linea.segmentos.isEmpty()) {
                // Trazado oficial por tramos (ida/vuelta y ramales): sin huecos.
                for (java.util.List<LatLng> tramo : linea.segmentos) {
                    mapa.addPolyline(new PolylineOptions()
                            .addAll(tramo)
                            .color(linea.color)
                            .width(9f)
                            .geodesic(false));
                }
            } else {
                mapa.addPolyline(new PolylineOptions()
                        .addAll(linea.ruta)
                        .color(linea.color)
                        .width(9f)
                        .geodesic(false));
            }

            for (Estacion e : linea.estaciones) {
                estaciones.add(new EstMapa(e, linea.numero, linea.color));
            }
        }
        dibujarMixtas();
    }

    /** Región visible del mapa, ampliada un poco para precargar más allá del borde. */
    private LatLngBounds rangoVisible() {
        LatLngBounds b = mapa.getProjection().getVisibleRegion().latLngBounds;
        double dLat = (b.northeast.latitude - b.southwest.latitude) * 0.25;
        double dLon = (b.northeast.longitude - b.southwest.longitude) * 0.25;
        return new LatLngBounds(
                new LatLng(b.southwest.latitude - dLat, b.southwest.longitude - dLon),
                new LatLng(b.northeast.latitude + dLat, b.northeast.longitude + dLon));
    }

    /**
     * Crea los marcadores de estación visibles en pantalla (por demanda). Solo cuando
     * el zoom ya las muestra, para no generar cientos de marcadores al alejar.
     */
    private void crearEstacionesVisibles() {
        if (mapa == null || mapa.getCameraPosition().zoom < ZOOM_ESTACIONES) return;
        LatLngBounds vista = rangoVisible();
        boolean visibles = mostrarEstaciones;
        for (EstMapa em : estaciones) {
            if (em.marker != null) continue;
            if (!vista.contains(em.e.posicion)) continue;
            Marker m = mapa.addMarker(new MarkerOptions()
                    .position(em.e.posicion)
                    .title(em.e.nombre)
                    .snippet("Línea " + em.linea)
                    .icon(iconoEstacion(em.e, em.color))
                    .anchor(0.5f, 0.5f)
                    .visible(visibles));
            if (m != null) { em.marker = m; marcadoresEstacion.add(m); }
        }
    }

    /** Estación más cercana a un punto (para centrar el mapa al iniciar). */
    private Estacion estacionMasCercana(LatLng p) {
        Estacion mejor = null;
        double best = Double.MAX_VALUE;
        for (EstMapa em : estaciones) {
            double d = Linea.distancia(em.e.posicion, p);
            if (d < best) { best = d; mejor = em.e; }
        }
        // Si "Mostrar Mexibús" está activo, también considera sus estaciones para la más cercana.
        if (Modos.mostrarMexibus(requireContext())) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                for (Estacion e : l.estaciones) {
                    double d = Linea.distancia(e.posicion, p);
                    if (d < best) { best = d; mejor = e; }
                }
            }
        }
        return mejor;
    }

    /**
     * Centra el mapa en la estación más cercana a la ubicación del usuario y carga
     * solo esa zona (radio {@link #RADIO_MAPA_M}); si no hay ubicación usa el centro
     * de CDMX. Evita crear cientos de marcadores al arrancar.
     */
    @SuppressLint("MissingPermission")
    private void centrarEnCercana() {
        if (!tienePermisoUbicacion()) { aplicarCentro(CDMX); return; }
        locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (!isAdded() || mapa == null) return;   // el fragment ya se desmontó: evita requireContext()
                    LatLng centro = CDMX;
                    if (loc != null) {
                        Estacion cerca = estacionMasCercana(new LatLng(loc.getLatitude(), loc.getLongitude()));
                        if (cerca != null) centro = cerca.posicion;
                    }
                    aplicarCentro(centro);
                })
                .addOnFailureListener(e -> { if (isAdded() && mapa != null) aplicarCentro(CDMX); });
    }

    private void aplicarCentro(LatLng centro) {
        if (mapa == null || !isAdded()) return;
        centroCarga = centro;
        // Visibilidad de ~500 m: encuadra un recuadro de ±500 m alrededor del punto (no un zoom fijo).
        double dLat = 500.0 / 111320.0;
        double dLon = 500.0 / (111320.0 * Math.cos(Math.toRadians(centro.latitude)));
        LatLngBounds caja = new LatLngBounds(
                new LatLng(centro.latitude - dLat, centro.longitude - dLon),
                new LatLng(centro.latitude + dLat, centro.longitude + dLon));
        try { mapa.moveCamera(CameraUpdateFactory.newLatLngBounds(caja, 0)); }
        catch (Exception e) { mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(centro, ZOOM_CERCANO)); }
        crearEstacionesVisibles();
        aplicarVisibilidadEstaciones();
        List<UnidadReal> ultimo = RealtimeRepository.get().getUltimo();
        if (ultimo != null) actualizarUnidades(ultimo);
    }

    /** Trazos de rutas mixtas (A31: L1↔L3; H72: L7↔L2) con su shape REAL del GTFS, punteado bicolor. */
    private void dibujarMixtas() {
        dibujarMixtaShape("MX-A31", 1, 3);   // Indios Verdes ↔ Pueblo (incluye Eje 2 Norte)
        dibujarMixtaShape("MX-H72", 7, 2);   // Tacubaya ↔ Glorieta Cuitláhuac (couplet)
    }

    /**
     * Capa del Mexibús (servicio ordinario): dibuja sus líneas y estaciones. Su visibilidad la
     * controla el ajuste "Mostrar Mexibús" (Acerca de), aplicado con {@link #aplicarMexibus()}.
     */
    private void dibujarMexibus() {
        boolean vis = Modos.mostrarMexibus(requireContext());
        List<PatternItem> punteado = java.util.Arrays.asList(new Dash(24f), new Gap(18f));
        for (Linea l : GtfsRepository.getMexibus(requireContext())) {
            coloresLinea.put(l.numero, l.color);
            boolean expres = l.numero >= 121 && l.numero <= 124;   // exprés Mexibús: punteado (Mexicable 201+ va sólido)
            PolylineOptions po = new PolylineOptions()
                    .addAll(l.ruta).color(l.color).geodesic(false).visible(vis)
                    .width(expres ? 7f : 9f).zIndex(expres ? 5f : 3f);
            if (expres) po.pattern(punteado);
            mexibusLineas.add(mapa.addPolyline(po));
            // Los MARCADORES NO se crean aquí (eran cientos de golpe → congelaba el arranque). Solo se
            // registran; se crean por demanda al acercar, en crearMexibusVisibles() (igual que el Metrobús).
            for (Estacion e : l.estaciones) {
                if (l.numero == 104 && e.nombre.startsWith("Indios Verdes")) {
                    // Indios Verdes L4: 2 andenes separados (sur/norte).
                    EstMapa a = new EstMapa(e, l.numero, l.color);
                    a.pos = new LatLng(19.493912, -99.119961); a.titulo = "Indios Verdes · andén sur (dir. La Raza)";
                    EstMapa b = new EstMapa(e, l.numero, l.color);
                    b.pos = new LatLng(19.496143, -99.119133); b.titulo = "Indios Verdes · andén norte (dir. UMB Tecámac)";
                    mexibusEst.add(a); mexibusEst.add(b);
                    continue;
                }
                EstMapa em = new EstMapa(e, l.numero, l.color);
                em.titulo = Planificador.nombreMostrar(requireContext(), e.nombre, l.numero);
                mexibusEst.add(em);
            }
        }
    }

    /** Crea los marcadores Mexibús visibles por demanda (zoom + región), como el Metrobús. */
    private void crearMexibusVisibles() {
        if (mapa == null || !Modos.mostrarMexibus(requireContext())) return;
        if (mapa.getCameraPosition().zoom < ZOOM_ESTACIONES) return;
        LatLngBounds vista = rangoVisible();
        for (EstMapa em : mexibusEst) {
            if (em.marker != null || !vista.contains(em.pos)) continue;
            Marker m = mapa.addMarker(new MarkerOptions()
                    .position(em.pos).title(em.titulo).snippet("Mexibús")
                    .icon(iconoMexibus(em)).anchor(0.5f, 0.5f).zIndex(4f));
            if (m != null) em.marker = m;
        }
    }

    /** Icono de un marcador Mexibús: pictograma si lo hay y el modo es "nuevos"; si no, punto/anillo. */
    private BitmapDescriptor iconoMexibus(EstMapa em) {
        return (em.e.icono != null && !em.e.icono.isEmpty())
                ? iconoEstacion(em.e, em.color) : iconoEstacionMexibus(em.color);
    }

    /** Icono de estación del Mexibús: anillo del color de la línea (los KML no traen pictogramas). */
    private BitmapDescriptor iconoEstacionMexibus(int color) {
        String key = "MXdot|" + color;
        BitmapDescriptor cached = cacheIco.get(key);
        if (cached != null) return cached;
        int px = Math.round(20 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(px / 2f, px / 2f, px * 0.46f, p);        // disco del color de la línea
        p.setColor(Color.WHITE);
        c.drawCircle(px / 2f, px / 2f, px * 0.30f, p);        // centro blanco
        p.setColor(color);
        c.drawCircle(px / 2f, px / 2f, px * 0.16f, p);        // punto interno (estilo estación)
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIco.put(key, bd);
        return bd;
    }

    /** Aplica la visibilidad del Mexibús según el ajuste "Mostrar Mexibús" (Acerca de). */
    private void aplicarMexibus() {
        if (mapa == null) return;
        boolean vis = Modos.mostrarMexibus(requireContext());
        boolean porZoom = mapa.getCameraPosition().zoom >= ZOOM_ESTACIONES;   // igual que el Metrobús
        boolean mostrar = vis && porZoom;
        for (Polyline p : mexibusLineas) p.setVisible(vis);                    // las líneas siempre (si el toggle está on)
        for (EstMapa em : mexibusEst) if (em.marker != null) {
            if (mostrar && !em.marker.isVisible()) em.marker.setIcon(iconoMexibus(em));  // refresca al reaparecer (modo actual)
            em.marker.setVisible(mostrar);   // estaciones por zoom
        }
    }

    /** Alterna estilo de iconos (pictogramas nuevos ↔ puntos antiguos), lo guarda y refresca el mapa. */
    private void alternarIconos() {
        boolean nuevos = !Modos.iconosNuevos(requireContext());
        Modos.setIconosNuevos(requireContext(), nuevos);
        actualizarLogoIconos(nuevos);
        // Solo se refrescan los marcadores VISIBLES (los ocultos por zoom se actualizan al reaparecer):
        // así el cambio es liviano y no se reconstruyen cientos de bitmaps de golpe (evita OOM/ANR).
        try {
            for (EstMapa em : estaciones) if (em.marker != null && em.marker.isVisible()) em.marker.setIcon(iconoEstacion(em.e, em.color));
            for (EstMapa em : mexibusEst) if (em.marker != null && em.marker.isVisible()) em.marker.setIcon(iconoMexibus(em));
        } catch (Throwable t) {
            android.util.Log.e("MapFragment", "Error al alternar iconos", t);
        }
        Toast.makeText(requireContext(),
                getString(nuevos ? R.string.mapa_iconos_nuevos : R.string.mapa_iconos_antiguos),
                Toast.LENGTH_SHORT).show();
    }

    /** Un recorrido mixto (ambos sentidos) con su shape del GTFS, punteado en los 2 colores de línea. */
    private void dibujarMixtaShape(String base, int lineaA, int lineaB) {
        Linea a = GtfsRepository.porNumero(requireContext(), lineaA);
        Linea b = GtfsRepository.porNumero(requireContext(), lineaB);
        if (a == null || b == null) return;
        List<PatternItem> punteado = java.util.Arrays.asList(new Dash(26f), new Gap(26f));
        for (String suf : new String[]{"-ida", "-vuelta"}) {
            List<LatLng> sh = GtfsRepository.sublinea(requireContext(), base + suf);
            if (sh == null || sh.size() < 2) continue;
            mapa.addPolyline(new PolylineOptions().addAll(sh).color(a.color).width(7f).zIndex(2f));
            mapa.addPolyline(new PolylineOptions().addAll(sh).color(b.color).width(7f)
                    .zIndex(2f).pattern(punteado));
        }
    }

    private void actualizarUnidades(List<UnidadReal> unidades) {
        actualizarUnidades(unidades, null);
    }

    /**
     * Crea/mueve/elimina los marcadores de unidades según el feed, limitando a las que
     * están dentro del radio ({@link #RADIO_MAPA_M}) del centro de la cámara (o de
     * {@code centroForzado} si se indica, p. ej. al buscar una unidad concreta).
     */
    private void actualizarUnidades(List<UnidadReal> unidades, LatLng centroForzado) {
        // Unidades ocultas por el botón: no se dibujan (salvo búsqueda explícita de una unidad).
        if (!mostrarUnidades && centroForzado == null) {
            if (!marcadoresUnidad.isEmpty()) {
                for (Marker m : marcadoresUnidad.values()) m.remove();
                marcadoresUnidad.clear();
            }
            return;
        }
        Set<String> vistos = new HashSet<>();
        int total = 0;
        // Normal: se muestran las unidades dentro de la región visible del mapa (progresivo
        // al alejar). En búsqueda (centroForzado) se usa un radio alrededor del punto.
        LatLngBounds vista = (mapa != null && centroForzado == null) ? rangoVisible() : null;

        for (UnidadReal u : unidades) {
            if (!RealtimeRepository.filtro.cumple(u)) continue;   // oculta las que no pasan el filtro
            total++;   // total en servicio (para el contador), aunque no todas se dibujen
            boolean enRango = vista != null ? vista.contains(u.posicion)
                    : (centroForzado == null || Linea.distancia(u.posicion, centroForzado) <= RADIO_MAPA_M);
            if (!enRango) continue;   // fuera de la vista: no se crea marcador
            vistos.add(u.numero);
            Marker m = marcadoresUnidad.get(u.numero);
            if (m == null) {
                m = mapa.addMarker(new MarkerOptions()
                        .position(u.posicion)
                        .title("Unidad " + u.numero)
                        .snippet(snippet(u))
                        .icon(iconoParaUnidad(u))
                        .anchor(0.5f, 0.5f)
                        .zIndex(10f));
                if (m != null) marcadoresUnidad.put(u.numero, m);
            } else {
                moverMarcador(u.numero, m, u.posicion);
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
        actualizarConteo(total);
        aplicarSeleccionUnidad();
    }

    /** Desliza un marcador desde su posición actual hasta la nueva de forma suave. */
    private void moverMarcador(String numero, Marker marker, LatLng destino) {
        final LatLng inicio = marker.getPosition();
        if (inicio.latitude == destino.latitude && inicio.longitude == destino.longitude) return;

        final long token = ++animSeq;
        animToken.put(numero, token);
        final long t0 = SystemClock.uptimeMillis();

        handler.post(new Runnable() {
            @Override
            public void run() {
                Long actual = animToken.get(numero);
                if (mapa == null || actual == null || actual != token) return; // reemplazado/destruido
                float t = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) Config.ANIM_MS);
                double lat = inicio.latitude + t * (destino.latitude - inicio.latitude);
                double lon = inicio.longitude + t * (destino.longitude - inicio.longitude);
                try {
                    marker.setPosition(new LatLng(lat, lon));
                } catch (Exception e) {
                    return; // el marcador ya fue removido
                }
                if (t < 1f) handler.postDelayed(this, 16);
            }
        });
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
        String eco = RealtimeRepository.unidadSeleccionada;
        Marker m = marcadoresUnidad.get(eco);
        // Posición REAL de la unidad: del marcador si ya existe, o directo del feed si su marcador
        // aún no se creó (la unidad está fuera de la zona que se cargó al abrir el mapa). Así "ver
        // en mapa" siempre lleva a la unidad y no se queda en la estación más cercana.
        LatLng destino = m != null ? m.getPosition() : null;
        if (destino == null) {
            UnidadReal u = RealtimeRepository.get().buscar(eco);
            if (u != null) destino = u.posicion;
        }
        if (destino == null) return;   // aún no llega el dato; se reintenta en el próximo refresco
        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(destino, 15f));
        if (m != null) m.showInfoWindow();
        RealtimeRepository.unidadSeleccionada = null;
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

    /**
     * Marcador de estación: si existe el pictograma en drawable (ic_est_{nombre}),
     * lo usa; si no, un punto del color de la línea.
     */
    // Caché de descriptores de icono: evita recrear cientos de bitmaps (y presionar la memoria nativa
    // de Google Maps → OOM) cada vez que se alterna entre iconos nuevos y antiguos.
    private final java.util.Map<String, BitmapDescriptor> cacheIco = new java.util.HashMap<>();

    private BitmapDescriptor iconoEstacion(Estacion e, int color) {
        boolean nuevos = Modos.iconosNuevos(requireContext());
        String key = "E|" + (e.icono == null ? "" : e.icono) + "|" + color + "|" + (nuevos ? 1 : 0);
        BitmapDescriptor cached = cacheIco.get(key);
        if (cached != null) return cached;

        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Bitmap escalado = Iconos.pictograma(requireContext(), e.icono, px);
        if (escalado != null) {
            c.drawBitmap(escalado, 0, 0, null);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            c.drawCircle(px / 2f, px / 2f, px * 0.34f, p);
            p.setColor(color);
            c.drawCircle(px / 2f, px / 2f, px * 0.26f, p);
        }
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIco.put(key, bd);
        return bd;
    }


    /** Icono de la unidad: degradado diagonal si va en ruta mixta, normal si no. */
    private BitmapDescriptor iconoParaUnidad(UnidadReal u) {
        RutasMixtas.Tramo t = RutasMixtas.tramo(u.origen, u.destino);
        if (t != null) {
            // arriba color de la línea de salida (origen), abajo la de término (destino)
            return iconoUnidadMixta(colorDeLinea(t.salida), colorDeLinea(t.termino), u.numero);
        }
        return iconoUnidad(colorDeLinea(u.linea), u.numero);
    }

    /** Bus del color de la línea (con halo blanco) y el económico en el parabrisas. */
    private BitmapDescriptor iconoUnidad(int color, String numero) {
        int d = (int) (40 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable bus = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            dibujarHalo(c, d, bus);
            int margen = d / 5;
            bus.setBounds(margen, margen, d - margen, d - margen);
            bus.setTint(color);
            bus.draw(c);
        }
        dibujarNumeroParabrisas(c, d, numero);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /**
     * Bus con degradado diagonal para unidades en ruta mixta (arriba línea de
     * salida, abajo línea de término), con el económico en el parabrisas.
     */
    private BitmapDescriptor iconoUnidadMixta(int colorArriba, int colorAbajo, String numero) {
        int d = (int) (40 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable bus = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            dibujarHalo(c, d, bus);
            int margen = d / 5;
            // Se pinta el bus en blanco y se tiñe mitad/mitad en diagonal a 45° (SRC_IN).
            Bitmap capa = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
            Canvas cc = new Canvas(capa);
            bus.setBounds(margen, margen, d - margen, d - margen);
            bus.setTint(Color.WHITE);
            bus.draw(cc);
            Paint tri = new Paint(Paint.ANTI_ALIAS_FLAG);
            tri.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            Path arriba = new Path();                 // triángulo superior-izquierdo = salida
            arriba.moveTo(0, 0); arriba.lineTo(d, 0); arriba.lineTo(0, d); arriba.close();
            tri.setColor(colorArriba);
            cc.drawPath(arriba, tri);
            Path abajo = new Path();                  // triángulo inferior-derecho = término
            abajo.moveTo(d, 0); abajo.lineTo(d, d); abajo.lineTo(0, d); abajo.close();
            tri.setColor(colorAbajo);
            cc.drawPath(abajo, tri);
            c.drawBitmap(capa, 0, 0, null);
        }
        dibujarNumeroParabrisas(c, d, numero);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /** Silueta blanca un poco más grande detrás del bus, para que resalte en el mapa. */
    private void dibujarHalo(Canvas c, int d, Drawable bus) {
        int margen = d / 5;
        int h = Math.round(d * 0.05f);
        bus.setBounds(margen - h, margen - h, d - margen + h, d - margen + h);
        bus.setTint(Color.WHITE);
        bus.draw(c);
    }

    /** Económico centrado en el parabrisas del bus: blanco con contorno oscuro. */
    private void dibujarNumeroParabrisas(Canvas c, int d, String numero) {
        if (numero == null || numero.isEmpty()) return;
        int margen = d / 5;
        // Parabrisas del vector ic_bus: x 6..18, y 6..11 en un viewport de 24.
        float busSize = d - 2f * margen;
        float cx = margen + busSize * (12f / 24f);          // centro horizontal
        float cyWin = margen + busSize * (8.6f / 24f);      // centro de la ventana
        float winW = busSize * (12f / 24f);                 // ancho de la ventana

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Tipografia.metro(requireContext()));   // número de unidad en Tipo Metro
        tp.setFakeBoldText(true);
        float ts = winW / Math.max(2.2f, numero.length() * 0.62f);
        tp.setTextSize(ts);
        float y = cyWin - (tp.descent() + tp.ascent()) / 2f;
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(Math.max(1.2f, ts * 0.20f));
        tp.setColor(0xFF10233A);
        c.drawText(numero, cx, y, tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(Color.WHITE);
        c.drawText(numero, cx, y, tp);
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

    /** Busca un económico (número) o una estación (texto) y centra el mapa ahí. */
    private void buscarEnMapa(String texto) {
        final String eco = texto != null ? texto.trim() : "";
        if (eco.isEmpty() || mapa == null) return;
        ocultarTeclado();

        // Si el texto trae letras, se trata como nombre de estación (búsqueda difusa).
        if (eco.matches(".*[A-Za-zÁÉÍÓÚáéíóúÑñ].*")) {
            buscarEstacionEnMapa(eco);
            return;
        }

        int max = Modelos.maxEconomico();
        try {
            int n = Integer.parseInt(eco.replaceAll("[^0-9]", ""));
            if (max > 0 && n > max) {
                Toast.makeText(requireContext(), getString(R.string.eco_max, max), Toast.LENGTH_LONG).show();
                return;
            }
        } catch (NumberFormatException ignore) {}

        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override
            public void onData(List<UnidadReal> unidades) {
                if (!isAdded() || mapa == null) return;
                UnidadReal u = RealtimeRepository.get().buscar(eco);
                if (u == null) {
                    // No está en vivo: mándala al buscador de unidades (ficha offline).
                    RealtimeRepository.ecoParaBuscar = eco;
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).navegarA(R.id.nav_buscar);
                    }
                    return;
                }
                actualizarUnidades(unidades, u.posicion);   // crea el marcador aunque esté lejos
                centroCarga = u.posicion;
                mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(u.posicion, 15f));
                Marker m = marcadoresUnidad.get(u.numero);
                if (m != null) m.showInfoWindow();
            }

            @Override
            public void onError(String mensaje) {
                if (isAdded()) Toast.makeText(requireContext(),
                        "Sin conexión con el servidor de unidades", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Búsqueda de estación (texto): resuelve el nombre de forma difusa y centra el mapa.
     * Si es correspondencia (varias líneas), centra en la zona intermedia. Sin puntero.
     */
    private void buscarEstacionEnMapa(String texto) {
        String nombre = Planificador.estacionParecida(requireContext(), texto);
        if (nombre == null) {
            Toast.makeText(requireContext(), getString(R.string.estacion_no_encontrada), Toast.LENGTH_SHORT).show();
            return;
        }
        String nn = Planificador.norm(nombre);
        double lat = 0, lon = 0;
        int c = 0;
        for (EstMapa em : estaciones) {
            if (Planificador.norm(em.e.nombre).equals(nn)) { lat += em.e.posicion.latitude; lon += em.e.posicion.longitude; c++; }
        }
        if (c == 0 && Modos.mostrarMexibus(requireContext())) {   // estación del Mexibús
            for (Linea l : GtfsRepository.getMexibus(requireContext()))
                for (Estacion e : l.estaciones)
                    if (Planificador.norm(e.nombre).equals(nn)) { lat += e.posicion.latitude; lon += e.posicion.longitude; c++; }
        }
        if (c == 0) return;
        LatLng centro = new LatLng(lat / c, lon / c);   // zona intermedia si es correspondencia
        centroCarga = centro;
        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(centro, c > 1 ? 15.5f : 16f));
    }

    private void ocultarTeclado() {
        View v = getView();
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        aplicarMexibus();   // refleja el ajuste "Mostrar Mexibús" al volver al mapa
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(poll);
        marcadoresEstacion.clear();
        marcadoresUnidad.clear();
        estaciones.clear();
        mexibusEst.clear();
        mexibusLineas.clear();
        centroCarga = null;
        coloresLinea.clear();
        animToken.clear();
        mapa = null;
        super.onDestroyView();
    }
}
