package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.Build;
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
// ============================================================
// CLASE    : MapFragment   (extends Fragment, implements FiltrosSheet.Host)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// La pantalla PRINCIPAL: un mapa (Google Maps) con las 7 líneas del
// Metrobús (y Mexibús si está activo), sus estaciones y las UNIDADES EN
// TIEMPO REAL (feed del backend), animadas sobre la vía (UnidadAnimador).
//
// ¿QUÉ ES UN FRAGMENT? Una "sub-pantalla" reutilizable que vive dentro de
// una Activity (aquí MainActivity). Cada pestaña de la app es un Fragment.
//
// Implementa FiltrosSheet.Host: recibe el aviso cuando cambian los filtros
// (línea/destino/ruta/empresa) para redibujar solo lo que corresponde.
// ============================================================
public class MapFragment extends Fragment implements FiltrosSheet.Host {

    private static final LatLng CDMX = new LatLng(19.41, -99.14);
    private static final float ZOOM_INICIAL = 11f;
    private static final float ZOOM_ESTACIONES = 12.5f;
    private static final float ZOOM_CERCANO = 14f;        // ~2 km a la vista al iniciar
    private static final double RADIO_MAPA_M = 2000.0;    // radio máx. de carga (estaciones/unidades)

    // === Resaltado visual de "Siguiendo" (ver iconoUnidad/dibujarHalo/iniciarPulso) ===============
    private static final int TAMANO_UNIDAD_DP = 40;                 // tamaño normal del icono (igual que antes)
    private static final int TAMANO_UNIDAD_SEGUIDA_DP = 50;         // 50/40 = 1.25x el normal
    private static final float ALFA_UNIDAD_NO_SEGUIDA = 0.55f;      // atenuación del resto mientras hay una seguida
    private static final double PULSO_RADIO_BASE_M = 16.0;          // radio del anillo pulsante en reposo
    private static final double PULSO_RADIO_EXTRA_M = 10.0;         // cuánto "respira" hacia afuera
    private static final int PULSO_ALFA_BASE = 70;                  // 0-255: discreto, no estridente
    private static final long PULSO_DURACION_MS = 1400;             // medio ciclo (crece/decrece), lento y suave

    private GoogleMap mapa;
    private RedViewModel red;   // capa de datos de la red (Metrobús + Mexibús) vía LiveData
    private FusedLocationProviderClient locationClient;
    private TextView chipFiltros;
    private TextView txtConteo;

    private final List<Marker> marcadoresEstacion = new ArrayList<>();
    private final Map<String, Marker> marcadoresUnidad = new HashMap<>();
    /** Económicos cuyo marcador YA tiene pintado el halo de "en seguimiento" -- permite repintar
     *  el icono solo cuando ese estado cambia, no en cada ciclo de refresco. Ver actualizarUnidades(). */
    private final Set<String> halosSeguimiento = new HashSet<>();
    private UnidadAnimador animUnidades;   // anima las unidades pegadas al grafo y por su velocidad
    private final Map<Integer, Integer> coloresLinea = new HashMap<>();

    // === Resaltado visual de "Siguiendo" (ver iconoParaUnidad/aplicarEstadoSeguimiento) ==========
    /** Caché de BitmapDescriptor por (económico, color, siguiendo) -- evita regenerar el bitmap del
     *  icono cuando no hace falta (solo se recalcula si no está en caché: una unidad nueva, o su
     *  color/siguiendo cambió). Se purga por económico cuando su marcador se quita del mapa. */
    private final Map<String, BitmapDescriptor> cacheIconoUnidad = new HashMap<>();
    /** Anillo animado (Circle, no bitmap) alrededor de cada unidad SIGUIENDO: pulsación continua y
     *  suave sin recrear icono ni bitmap -- solo cambia radio/alfa de un overlay vectorial. */
    private final Map<String, com.google.android.gms.maps.model.Circle> pulsosSeguimiento = new HashMap<>();
    private final Map<String, android.animation.ValueAnimator> pulsoAnimadores = new HashMap<>();

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
    private com.google.android.gms.maps.model.Circle destelloResidual;   // aro tenue que deja destelloEstacion()
    private boolean trafico = false;
    private boolean mostrarEstaciones = true;
    private boolean mostrarUnidades = true;
    private boolean vista3d = false;
    private int tipoMapa = GoogleMap.MAP_TYPE_NORMAL;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean avisoError = false;
    private long ultimaManifestMapa = -1;   // último Manifestaciones.actualizado() ya pintado en el mapa

    /** Ciclo de actualización: pide el feed, actualiza marcadores y se reprograma. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override
                public void onData(List<UnidadReal> unidades) {
                    avisoError = false;   // servidor OK de nuevo: permite reactivar el respaldo si vuelve a fallar
                    if (mapa != null) actualizarUnidades(unidades);
                    if (mapa != null) actualizarEstadoEstaciones();
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
            // Bloqueos/avisos de Mexibús del backend: sin esto, la tarjeta de una estación Mexibús
            // (CartaEstacion) nunca mostraba el aviso "⚠ ..." si el usuario no había visitado antes
            // Llegadas o Planificador (las únicas pantallas que llamaban a refrescar() esto).
            if (isAdded()) {
                AfectacionesMexibus.refrescar(requireContext(),
                        () -> { if (isAdded() && mapa != null) actualizarEstadoEstaciones(); });
            }
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

    // ---- Tarjeta de resultado del buscador inline (unidad/estación), flotando sobre el mapa ----
    // (única vista de búsqueda/seguimiento de unidades de la app; ver mostrarCartaUnidad()).
    private ViewGroup cartaContainer;
    private CartaUnidad.Vistas cartaVistas;
    private String ecoCartaActual;   // económico mostrado en la tarjeta flotante
    private boolean cartaExpandida;  // false = píldora compacta, true = carta completa (misma vista)
    private View panelZoom;   // controles de zoom/brújula/ubicación (ver ajustarPanelZoom())

    private final ActivityResultLauncher<String> permisoUbicacionCarta =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) intentarSeguirCarta();
                else if (isAdded()) Toast.makeText(requireContext(),
                        getString(R.string.seguir_permiso_ubicacion), Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> permisoNotifCarta =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> arrancarSeguimientoCarta());

    /** Permiso de ubicación para 🔔 Alertas (ver toggleAlertaUnidad()) -- independiente de
     *  permisoUbicacionCarta, que es para 🚌 Seguir. La preferencia ya se guardó de todos modos
     *  (Telemetria.actualizarAlertaUnidad no depende de esto); si lo niega, solo se le avisa que
     *  por ahora no va a recibir el aviso. */
    private final ActivityResultLauncher<String> permisoUbicacionAlerta =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (!ok && isAdded()) Toast.makeText(requireContext(),
                        getString(R.string.alerta_permiso_ubicacion), Toast.LENGTH_LONG).show();
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
                    .commitAllowingStateLoss();
        }
        mapFragment.getMapAsync(this::alMapaListo);

        view.findViewById(R.id.btn_ubicacion).setOnClickListener(v -> irAMiUbicacion());

        cartaContainer = view.findViewById(R.id.map_carta_container);
        panelZoom = view.findViewById(R.id.panel_zoom);

        // Buscador inline: busca el económico y centra el mapa en la unidad.
        EditText inputMapa = view.findViewById(R.id.input_mapa);
        inputMapa.setOnEditorActionListener((v, actionId, event) -> {
            buscarEnMapa(inputMapa.getText().toString());
            return true;
        });
        view.findViewById(R.id.search_icon).setOnClickListener(v ->
                buscarEnMapa(inputMapa.getText().toString()));

        // Acceso al asistente conversacional (Gemini): antes una segunda barra completa debajo
        // del buscador; ahora un ícono al final de la MISMA barra de búsqueda (recupera espacio
        // vertical del mapa). Ahora abre AsistenteOverlayFragment (ventana flotante sobre el
        // mapa, ver su javadoc) en vez de ChatAsistenteActivity de pantalla completa -- mismo
        // backend/flujo de Function Calling y confirmación de acciones, solo cambia la
        // presentación. ChatAsistenteActivity sigue existiendo sin cambios (se abre desde
        // Configuración).
        view.findViewById(R.id.btn_chat_mapa).setOnClickListener(v ->
                new AsistenteOverlayFragment().show(getChildFragmentManager(), "asistente"));

        chipFiltros = view.findViewById(R.id.chip_filtros);
        txtConteo = view.findViewById(R.id.txt_conteo);
        view.findViewById(R.id.fab_filtros).setOnClickListener(v ->
                new FiltrosSheet().show(getChildFragmentManager(), "filtros"));
        chipFiltros.setOnClickListener(v -> {
            RealtimeRepository.filtro.limpiar();
            onFiltrosCambiados();
        });
        actualizarChip();

        // "Capas": agrupa tráfico/tipo de mapa/vista 3D (secundarios, poco frecuentes) en un
        // menú contextual -- antes eran 3 botones permanentes más en esta misma barra. Ninguna
        // de las 3 funciones se eliminó ni cambió de lógica, solo de jerarquía visual.
        view.findViewById(R.id.btn_capas).setOnClickListener(this::mostrarMenuCapas);
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
    }

    /** Menú "Capas": tráfico / tipo de mapa / vista 3D -- cada ítem llama al mismo método
     *  privado que ya existía (alternarTrafico/alternarTipo/alternar3d), sin ningún cambio de
     *  lógica, solo agrupados bajo un único botón en vez de 3 permanentes. */
    private void mostrarMenuCapas(View ancla) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        menu.inflate(R.menu.mapa_capas_menu);
        menu.getMenu().findItem(R.id.menu_capas_trafico).setChecked(trafico);
        menu.getMenu().findItem(R.id.menu_capas_3d).setChecked(vista3d);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_capas_trafico) alternarTrafico();
            else if (id == R.id.menu_capas_tipo) alternarTipo();
            else if (id == R.id.menu_capas_3d) alternar3d();
            return true;
        });
        menu.show();
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
            halosSeguimiento.clear();
            detenerTodosPulsos();
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
        aplicarMexibus();   // el botón "estaciones" (y el zoom) también gobiernan la capa Mexibús
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
        // Si Mexibús/Mexicable están activos, el botón "centrar" también abarca su red (cada uno
        // según su propio ajuste; el tag del polyline dice a qué sistema pertenece).
        boolean mxb = Modos.mostrarMexibus(requireContext());
        boolean mxc = Modos.mostrarMexicable(requireContext());
        if (mxb || mxc) {
            for (Polyline p : mexibusLineas) {
                Object tag = p.getTag();
                int sis = tag instanceof Integer ? Sistemas.sistemaDe((Integer) tag) : Sistemas.MEXIBUS;
                if ((sis == Sistemas.MEXICABLE && !mxc) || (sis != Sistemas.MEXICABLE && !mxb)) continue;
                for (LatLng pt : p.getPoints()) { b.include(pt); hay = true; }
            }
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
        // getMapAsync() es asíncrono (el SDK de Maps puede tardar en inicializar): si el usuario
        // sale de esta pestaña antes de que responda, el fragment ya no está adjunto y
        // requireContext()/getViewLifecycleOwner() más abajo lanzarían IllegalStateException.
        if (!isAdded()) return;
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

        // Tocar un marcador muestra su TARJETA (unidad o estación) flotando sobre el mapa, en vez
        // del tooltip genérico de arriba -- unifica el resultado con el buscador de la pestaña
        // Buscar (CartaUnidad) y le da a las estaciones el mismo trato (CartaEstacion).
        mapa.setOnMarkerClickListener(m -> {
            // Listener nativo del SDK de Maps, se dispara en cada toque de marcador: una excepción no
            // atrapada aquí tumba la app en la interacción más frecuente de esta pantalla.
            try {
                String eco = numeroDeMarcador(m);
                if (eco != null) {
                    mostrarCartaUnidad(eco, RealtimeRepository.get().buscar(eco));
                    return true;
                }
                EstMapa em = estacionDeMarcador(m);
                if (em != null) {
                    mostrarCartaEstacion(em);
                    return true;
                }
            } catch (Exception ignore) {}
            return false;   // marcador no reconocido (o falló): comportamiento por defecto (tooltip)
        });

        // El trazado se hace cuando la red ya está en memoria, observando el LiveData del ViewModel.
        // getViewLifecycleOwner() garantiza que estos callbacks NO se ejecutan tras destruir la vista
        // (sin fugas). getLineas()/getMexibus() dentro de dibujar* son lecturas lock-free ya publicadas.
        red.getMetrobus().observe(getViewLifecycleOwner(), lineas -> {
            if (mapa == null) return;
            try {
                dibujarRed();
                aplicarSeleccionLinea();
                crearEstacionesVisibles();
                aplicarVisibilidadEstaciones();
            } catch (Exception ignore) {}
        });
        red.getMexibus().observe(getViewLifecycleOwner(), lineas -> {
            if (mapa == null) return;
            try {
                dibujarMexibus();
                crearMexibusVisibles();
                aplicarVisibilidadEstaciones();
            } catch (Exception ignore) {}
        });
        if (RealtimeRepository.unidadSeleccionada != null) {
            // Viene una unidad del buscador: se centra en ELLA, NO en la estación cercana. Así el
            // callback asíncrono de ubicación de centrarEnCercana no sobrescribe la cámara dejándote
            // en la estación (esa era la causa de que "ver en mapa" no llevara a la unidad).
            UnidadReal u = RealtimeRepository.get().buscar(RealtimeRepository.unidadSeleccionada);
            aplicarCentro(u != null ? u.posicion : CDMX);
        } else if (RealtimeRepository.estacionSeleccionadaPos != null) {
            // Viene una estación del listado de Líneas: ya se conoce su posición exacta (no hace
            // falta esperar ningún feed), así que se vuela ahí directo y se destella para ubicarla.
            LatLng pos = RealtimeRepository.estacionSeleccionadaPos;
            int color = colorDeLinea(RealtimeRepository.estacionSeleccionadaLinea);
            RealtimeRepository.estacionSeleccionadaPos = null;
            RealtimeRepository.estacionSeleccionadaLinea = -1;
            centroCarga = pos;
            mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f));
            destelloEstacion(pos, color);
        } else {
            centrarEnCercana();   // centra en la estación más cercana y carga solo ese radio
        }

        mapa.setOnCameraIdleListener(() -> {
            // Se dispara en cada movimiento de cámara (muy frecuente); igual que el listener de
            // marcadores, no debe poder tumbar la app.
            try {
                crearEstacionesVisibles();                 // carga estaciones al explorar
                aplicarVisibilidadEstaciones();
                crearMexibusVisibles();                    // crea marcadores Mexibús por demanda (no todos al inicio)
                aplicarMexibus();                          // oculta/mostrar estaciones Mexibús por zoom
                List<UnidadReal> ultimo = RealtimeRepository.get().getUltimo();
                if (ultimo != null) actualizarUnidades(ultimo);   // carga unidades del rango visible
            } catch (Exception ignore) {}
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
                    .icon(iconoEstacion(em.e, em.color, fueraDeServicio(em)))
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
        // Según cada ajuste activo, también considera las estaciones de Mexibús y/o Mexicable.
        boolean mxb = Modos.mostrarMexibus(requireContext());
        boolean mxc = Modos.mostrarMexicable(requireContext());
        if (mxb || mxc) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                boolean esMxc = Sistemas.sistemaDe(l.numero) == Sistemas.MEXICABLE;
                if (esMxc ? !mxc : !mxb) continue;
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
                    try {
                        if (!isAdded() || mapa == null) return;   // el fragment ya se desmontó: evita requireContext()
                        LatLng centro = CDMX;
                        if (loc != null) {
                            Estacion cerca = estacionMasCercana(new LatLng(loc.getLatitude(), loc.getLongitude()));
                            if (cerca != null) centro = cerca.posicion;
                        }
                        aplicarCentro(centro);
                    } catch (Exception ignore) {}
                })
                .addOnFailureListener(e -> { try { if (isAdded() && mapa != null) aplicarCentro(CDMX); } catch (Exception ignore) {} });
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
     * Capa del Mexibús + Mexicable: dibuja sus líneas y estaciones. La visibilidad de cada sistema
     * la controla su propio ajuste ("Mostrar Mexibús"/"Mostrar Mexicable", Acerca de), aplicado con
     * {@link #aplicarMexibus()}.
     */
    private void dibujarMexibus() {
        boolean visMxb = Modos.mostrarMexibus(requireContext());
        boolean visMxc = Modos.mostrarMexicable(requireContext());
        List<PatternItem> punteado = java.util.Arrays.asList(new Dash(24f), new Gap(18f));
        for (Linea l : GtfsRepository.getMexibus(requireContext())) {
            coloresLinea.put(l.numero, l.color);
            boolean esMxc = Sistemas.sistemaDe(l.numero) == Sistemas.MEXICABLE;
            boolean vis = esMxc ? visMxc : visMxb;
            boolean expres = l.numero >= 121 && l.numero <= 124;   // exprés Mexibús: punteado (Mexicable 201+ va sólido)
            PolylineOptions po = new PolylineOptions()
                    .addAll(l.ruta).color(l.color).geodesic(false).visible(vis)
                    .width(expres ? 7f : 9f).zIndex(expres ? 5f : 3f);
            if (expres) po.pattern(punteado);
            Polyline pl = mapa.addPolyline(po);
            pl.setTag(l.numero);   // permite reconocer su sistema (Mexibús/Mexicable) después, sin otra lista
            mexibusLineas.add(pl);
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

    /** Crea los marcadores Mexibús/Mexicable visibles por demanda (zoom + región), como el Metrobús.
     *  Crea mientras CUALQUIERA de los dos esté activo; {@link #aplicarMexibus()} oculta después los
     *  del sistema que siga apagado. */
    private void crearMexibusVisibles() {
        if (mapa == null || (!Modos.mostrarMexibus(requireContext()) && !Modos.mostrarMexicable(requireContext()))) return;
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

    /**
     * Repinta en gris (o de vuelta a su color) los marcadores YA CREADOS cuya estación quedó fuera
     * de servicio (afectación real o simulada en AMBOS sentidos), para que el mapa general muestre
     * de un vistazo qué estaciones no operan. Solo repinta cuando cambió algo (compara contra el
     * último Manifestaciones.actualizado() ya pintado) — se llama en cada ciclo de refresco de
     * unidades, que ya corre con la misma cadencia.
     */
    private void actualizarEstadoEstaciones() {
        long act = Manifestaciones.actualizado();
        if (act == ultimaManifestMapa) return;
        ultimaManifestMapa = act;
        java.util.Set<String> bloqueadas = Manifestaciones.bloqueadas();
        for (EstMapa em : estaciones) {
            if (em.marker == null) continue;
            em.marker.setIcon(iconoEstacion(em.e, em.color, fueraDeServicio(em, bloqueadas)));
        }
        for (EstMapa em : mexibusEst) {
            if (em.marker == null) continue;
            em.marker.setIcon(iconoMexibus(em, bloqueadas));
        }
    }

    /** Icono de un marcador Mexibús: pictograma si lo hay y el modo es "nuevos"; si no, punto/anillo. */
    private BitmapDescriptor iconoMexibus(EstMapa em) {
        return iconoMexibus(em, Manifestaciones.bloqueadas());
    }

    private BitmapDescriptor iconoMexibus(EstMapa em, java.util.Set<String> bloqueadas) {
        boolean fuera = fueraDeServicio(em, bloqueadas);
        return (em.e.icono != null && !em.e.icono.isEmpty())
                ? iconoEstacion(em.e, em.color, fuera) : iconoEstacionMexibus(em.color, fuera);
    }

    /** Icono de estación del Mexibús: anillo del color de la línea (los KML no traen pictogramas). */
    private BitmapDescriptor iconoEstacionMexibus(int color, boolean fueraDeServicio) {
        String key = "MXdot|" + color + "|" + (fueraDeServicio ? 1 : 0);
        BitmapDescriptor cached = cacheIco.get(key);
        if (cached != null) return cached;
        int disco = fueraDeServicio ? GRIS_FUERA_SERVICIO : color;
        int px = Math.round(20 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(disco);
        c.drawCircle(px / 2f, px / 2f, px * 0.46f, p);        // disco del color de la línea
        p.setColor(Color.WHITE);
        c.drawCircle(px / 2f, px / 2f, px * 0.30f, p);        // centro blanco
        p.setColor(disco);
        c.drawCircle(px / 2f, px / 2f, px * 0.16f, p);        // punto interno (estilo estación)
        if (fueraDeServicio) dibujarBadgeAfectacion(c, px);
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIco.put(key, bd);
        return bd;
    }

    /** ¿Esta estación está fuera de servicio en AMBOS sentidos ahora mismo? (afectación real o
     *  simulada que la bloquea por completo, no un solo sentido/andén — ver Manifestaciones.bloqueadas()). */
    private boolean fueraDeServicio(EstMapa em) {
        return fueraDeServicio(em, Manifestaciones.bloqueadas());
    }

    private boolean fueraDeServicio(EstMapa em, java.util.Set<String> bloqueadas) {
        String k = Planificador.claveTerminal(em.linea) + "|" + Planificador.norm(em.e.nombre);
        if (!bloqueadas.contains(k)) return false;
        // Aunque la troncal (p. ej. L1) no pare ahí, un recorrido MIXTO real (RutasMixtas: A31, H72…)
        // puede seguir sirviendo la MISMA estación por su tramo de la OTRA línea (p. ej. H72 llega a
        // "Reforma"/"Hamburgo" por L7, aunque L1 las tenga fuera por un circuito de emergencia) -- ahí
        // no está realmente fuera de servicio, solo la troncal pura. Si esa otra línea TAMBIÉN está
        // bloqueada en esta estación, no se exime (ambos caminos están cortados).
        return !sirveRutaMixtaAlterna(em.linea, em.e.nombre, em.pos, bloqueadas);
    }

    /** Distancia máxima (m) para considerar que una estación de OTRA línea, con el MISMO nombre
     *  normalizado, es la MISMA parada física (y no una coincidencia de nombre entre corredores
     *  distintos). Las 3 colisiones reales confirmadas en este catálogo caen entre ~150 y ~355 m
     *  ("Reforma" L1 vs L7 ≈152 m, "Hamburgo" L1 vs L7 ≈231-253 m, "Plaza de la República" L1 vs
     *  L4 ≈353 m -- misma zona de la ciudad, calles distintas, NO la misma parada): se usa 100 m,
     *  por debajo de la menor de ellas, para excluir las 3 sin inventar una excepción por nombre.
     *  No hay en este audit ningún caso real DEMOSTRADO que necesite un radio mayor -- si aparece
     *  uno, debe documentarse con sus coordenadas antes de ampliar este valor, no a ciegas. */
    static final double RADIO_MISMA_PARADA_M = 100.0;   // paquete-visible: reutilizada por las pruebas JUnit

    /** ¿Hay un recorrido mixto (RutasMixtas.SECUENCIAS) que sirva esta MISMA estación por OTRA línea
     *  que sigue en servicio ahí? Ver {@link #fueraDeServicio}.
     *
     *  <p>"Misma estación" se exige por NOMBRE (normalizado) Y por UBICACIÓN (ver
     *  {@link #mismaParadaFisica}): dos estaciones de corredores distintos pueden compartir nombre
     *  exacto sin ser el mismo lugar (p. ej. "Reforma"/"Hamburgo"/"Plaza de la República" existen
     *  como estación real de Avenida Insurgentes en L1 Y, por separado, como estación de Paseo de
     *  la Reforma/Eje Central en L7/L4, a varios cientos de metros) -- antes solo se comparaba el
     *  nombre, así que un circuito de emergencia en L1 eximía de "fuera de servicio" estaciones de
     *  L1 que en realidad no tienen ningún recorrido mixto real pasando por ahí. */
    private boolean sirveRutaMixtaAlterna(int lineaBloqueada, String nombreEstacion, LatLng posEstacion,
                                           java.util.Set<String> bloqueadas) {
        String nn = Planificador.norm(nombreEstacion);
        int lineaBloqueadaN = Planificador.claveTerminal(lineaBloqueada);
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            for (int i = 0; i < sm.estaciones.length; i++) {
                int ln = sm.lineas[i];
                if (Planificador.claveTerminal(ln) == lineaBloqueadaN) continue;   // mismo trazado cerrado: no cuenta
                if (!Planificador.norm(sm.estaciones[i]).equals(nn)) continue;
                if (bloqueadas.contains(Planificador.claveTerminal(ln) + "|" + nn)) continue;
                if (posEstacion != null && !mismaParadaFisica(ln, nn, posEstacion)) continue;
                return true;
            }
        }
        return false;
    }

    /** ¿La estación "nn" (ya normalizada) del catálogo de la línea "ln" está a ≤ RADIO_MISMA_PARADA_M
     *  de "posEstacion"? Resuelve la posición real de esa estación en SU línea (no asume que
     *  comparte coordenadas solo por compartir nombre) antes de comparar la distancia -- ver
     *  {@link #sirveRutaMixtaAlterna}. */
    private boolean mismaParadaFisica(int ln, String nn, LatLng posEstacion) {
        Linea l = GtfsRepository.porNumero(requireContext(), ln);
        if (l == null) return false;
        for (Estacion e : l.estaciones) {
            if (Planificador.norm(e.nombre).equals(nn)) {
                return distanciaMismaParada(posEstacion, e.posicion) <= RADIO_MISMA_PARADA_M;
            }
        }
        return false;
    }

    /** Núcleo puro (sin Context/GoogleMap) de la comparación de distancia -- separado para poder
     *  probarlo en JUnit sobre el JVM del host sin necesitar Android ni el catálogo real. */
    static double distanciaMismaParada(LatLng a, LatLng b) { return Linea.distancia(a, b); }

    /** Aplica la visibilidad de Mexibús/Mexicable, cada uno según su propio ajuste ("Mostrar
     *  Mexibús"/"Mostrar Mexicable", Acerca de): una línea o estación de un sistema apagado se
     *  oculta aunque el otro sistema esté encendido. */
    private void aplicarMexibus() {
        if (mapa == null) return;
        boolean visMxb = Modos.mostrarMexibus(requireContext());
        boolean visMxc = Modos.mostrarMexicable(requireContext());
        boolean porZoom = mapa.getCameraPosition().zoom >= ZOOM_ESTACIONES;   // igual que el Metrobús
        for (Polyline p : mexibusLineas) {
            Object tag = p.getTag();
            boolean esMxc = tag instanceof Integer && Sistemas.sistemaDe((Integer) tag) == Sistemas.MEXICABLE;
            p.setVisible(esMxc ? visMxc : visMxb);   // las líneas siempre (si el toggle de SU sistema está on)
        }
        for (EstMapa em : mexibusEst) if (em.marker != null) {
            boolean esMxc = Sistemas.sistemaDe(em.linea) == Sistemas.MEXICABLE;
            boolean vis = esMxc ? visMxc : visMxb;
            boolean mostrar = vis && porZoom && mostrarEstaciones;   // el botón "estaciones" del mapa también aplica aquí
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
            for (EstMapa em : estaciones) if (em.marker != null && em.marker.isVisible()) em.marker.setIcon(iconoEstacion(em.e, em.color, fueraDeServicio(em)));
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
        if (animUnidades == null) animUnidades = new UnidadAnimador(requireContext());
        // Unidades ocultas por el botón: no se dibujan (salvo búsqueda explícita de una unidad).
        if (!mostrarUnidades && centroForzado == null) {
            if (!marcadoresUnidad.isEmpty()) {
                for (Marker m : marcadoresUnidad.values()) m.remove();
                marcadoresUnidad.clear();
                animUnidades.limpiar();
                detenerTodosPulsos();
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
            boolean siguiendoAhora = SeguimientoService.sigue(u.numero);
            boolean haySeguidaGlobal = !SeguimientoService.ecosSeguidos.isEmpty();
            Marker m = marcadoresUnidad.get(u.numero);
            if (m == null) {
                m = mapa.addMarker(new MarkerOptions()
                        .position(animUnidades.inicial(u))
                        .title("Unidad " + u.numero)
                        .snippet(snippet(u))
                        .icon(iconoParaUnidad(u))   // ya refleja siguiendoAhora
                        .anchor(0.5f, 0.5f)
                        .zIndex(siguiendoAhora ? 20f : 10f));
                if (m != null) {
                    marcadoresUnidad.put(u.numero, m);
                    m.setAlpha(siguiendoAhora ? 1f : (haySeguidaGlobal ? ALFA_UNIDAD_NO_SEGUIDA : 1f));
                    if (siguiendoAhora) {
                        halosSeguimiento.add(u.numero);
                        iniciarPulso(u.numero, m.getPosition());
                    }
                }
            } else {
                animUnidades.animar(u, m);   // pegada al grafo + avance por velocidad (tiempo real)
                m.setSnippet(snippet(u));
                // El halo de "en seguimiento" solo se repinta cuando el estado CAMBIÓ desde la
                // última vez (no en cada ciclo de 10s): detecta tanto el toggle hecho en esta
                // misma carta como uno hecho desde la notificación de SeguimientoService o desde
                // "Detener todo", sin que MapFragment necesite enterarse de dónde vino el cambio.
                boolean teniaHalo = halosSeguimiento.contains(u.numero);
                if (siguiendoAhora != teniaHalo) {
                    m.setIcon(iconoParaUnidad(u));
                    m.setZIndex(siguiendoAhora ? 20f : 10f);
                    if (siguiendoAhora) {
                        halosSeguimiento.add(u.numero);
                        iniciarPulso(u.numero, m.getPosition());
                    } else {
                        halosSeguimiento.remove(u.numero);
                        detenerPulso(u.numero);
                    }
                }
                // Prominencia (alfa): se reevalúa SIEMPRE, no solo al cambiar el icono -- otra
                // unidad puede empezar/dejar de seguirse mientras ESTA no cambia su propio estado,
                // y su atenuación relativa debe reflejarlo igual. No regenera ningún bitmap.
                m.setAlpha(siguiendoAhora ? 1f : (haySeguidaGlobal ? ALFA_UNIDAD_NO_SEGUIDA : 1f));
            }
        }

        // quitar unidades que ya no están en servicio
        Iterator<Map.Entry<String, Marker>> it = marcadoresUnidad.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Marker> e = it.next();
            if (!vistos.contains(e.getKey())) {
                e.getValue().remove();
                animUnidades.olvidar(e.getKey());
                halosSeguimiento.remove(e.getKey());
                detenerPulso(e.getKey());
                limpiarCacheIconoUnidad(e.getKey());
                it.remove();
            }
        }

        avisoError = false;
        actualizarConteo(total);
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

    /** ¿Qué unidad corresponde a este marcador? (recorre marcadoresUnidad; null si no es de unidad). */
    private String numeroDeMarcador(Marker m) {
        for (Map.Entry<String, Marker> e : marcadoresUnidad.entrySet())
            if (e.getValue().equals(m)) return e.getKey();
        return null;
    }

    /** ¿Qué estación corresponde a este marcador? (Metrobús o Mexibús; null si no es de estación). */
    private EstMapa estacionDeMarcador(Marker m) {
        for (EstMapa em : estaciones) if (m.equals(em.marker)) return em;
        for (EstMapa em : mexibusEst) if (m.equals(em.marker)) return em;
        return null;
    }

    /** Igual que {@link #estacionDeMarcador}, pero busca por nombre+línea en vez de por marcador: la
     *  usa el buscador de texto ({@link #centrarGrupoEnMapa}), que resuelve un {@link Planificador.Match}
     *  y no necesariamente tiene ya un marcador creado para esa estación (se crean por demanda/radio). */
    private EstMapa estacionPorNombreLinea(String nombre, int linea) {
        for (EstMapa em : estaciones) if (em.linea == linea && em.e.nombre.equals(nombre)) return em;
        for (EstMapa em : mexibusEst) if (em.linea == linea && em.e.nombre.equals(nombre)) return em;
        return null;
    }

    /** Oculta la tarjeta flotante (unidad o estación) sobre el mapa. */
    private void ocultarCarta() {
        if (cartaContainer != null) cartaContainer.setVisibility(View.GONE);
        ajustarPanelZoom();
    }

    /**
     * El panel de zoom/brújula/ubicación está centrado verticalmente en pantalla (fragment_map.xml),
     * pero la tarjeta flotante de unidad/estación crece hacia arriba desde abajo y su alto varía
     * según el contenido (hasta 300dp) -- con una tarjeta alta, ambos se traslapan. En vez de fijar
     * un desplazamiento a ojo, se miden las posiciones reales ya con el layout resuelto (por eso el
     * post()) y solo se sube el panel lo que haga falta para dejar de encimarse; sin tarjeta visible
     * (o si no se traslapan) vuelve a su lugar original.
     */
    private void ajustarPanelZoom() {
        if (panelZoom == null) return;
        panelZoom.post(() -> {
            // Ajuste puramente cosmético: cualquier excepción aquí (vista ya desprendida, medidas
            // en 0 por una carrera con el layout, etc.) nunca debe tumbar la app -- en el peor caso
            // el panel se queda donde estaba.
            try {
                if (!isAdded() || panelZoom.getHeight() == 0) return;
                float desplazamiento = 0f;
                if (cartaContainer != null && cartaContainer.getVisibility() == View.VISIBLE
                        && cartaContainer.getHeight() > 0) {
                    int[] posPanel = new int[2];
                    int[] posCarta = new int[2];
                    panelZoom.getLocationOnScreen(posPanel);
                    cartaContainer.getLocationOnScreen(posCarta);
                    int panelInferior = posPanel[1] + panelZoom.getHeight();
                    int cartaSuperior = posCarta[1];
                    if (panelInferior > cartaSuperior) {
                        float aire = 12f * panelZoom.getResources().getDisplayMetrics().density;
                        desplazamiento = -(panelInferior - cartaSuperior) - aire;
                    }
                }
                panelZoom.animate().translationY(desplazamiento).setDuration(150).start();
            } catch (Exception ignore) {}
        });
    }

    /**
     * Muestra la tarjeta de UNIDAD flotando sobre el mapa (vía {@link CartaUnidad}): reemplaza el
     * tooltip genérico de 2 líneas y es el ÚNICO lugar de la app para buscar/seguir unidades (la
     * pestaña Buscar se eliminó; su seguimiento múltiple vive aquí).
     */
    private void mostrarCartaUnidad(String eco, UnidadReal u) {
        if (!isAdded() || cartaContainer == null) return;
        ecoCartaActual = eco;
        cartaContainer.removeAllViews();
        View v = getLayoutInflater().inflate(R.layout.view_carta_unidad, cartaContainer, false);
        cartaContainer.addView(v);
        cartaVistas = new CartaUnidad.Vistas(v);
        CartaUnidad.bind(requireContext(), cartaVistas, eco, u, () -> ecoCartaActual);
        cartaVistas.btnVerMapa.setVisibility(View.GONE);        // ya estás viéndola en el mapa
        actualizarBotonSeguirCarta();
        // Si esta unidad está guardada, aprovecha tener datos frescos del feed para refrescar su
        // caché de línea (solo presentación, ver EconomicoFavoritoEntity.linea). Si no está
        // guardada, el UPDATE no afecta ninguna fila -- no hace falta comprobarlo antes.
        if (u != null && u.linea != null) Telemetria.refrescarLineaCache(requireContext(), eco, u.linea);
        cartaVistas.btnSeguir.setOnClickListener(b -> {
            if (ecoCartaActual == null) return;
            if (SeguimientoService.sigue(ecoCartaActual)) {
                SeguimientoService.detener(requireContext(), ecoCartaActual);
                actualizarBotonSeguirCarta();
                refrescarResaltadoSeguimiento(ecoCartaActual);   // quita el resaltado AL INSTANTE, sin esperar al poll
            } else {
                intentarSeguirCarta();
            }
        });
        // Mantener presionado "Seguir" = guardar/quitar de "unidades guardadas" (persiste entre
        // reinicios, pero es un estado INDEPENDIENTE de si se está siguiendo: guardar NO inicia el
        // seguimiento ni dejar de seguir la quita de guardadas -- ver EconomicoFavoritoEntity).
        cartaVistas.btnSeguir.setOnLongClickListener(b -> {
            if (ecoCartaActual == null || !isAdded()) return false;
            String favEco = ecoCartaActual;
            // Mismo helper que usa "⭐ Guardar" en el menú ⋮ (ver toggleGuardarUnidad): cachea
            // línea si hay datos en vivo, mismo toast, nunca toca SeguimientoService.
            Telemetria.esFavorito(requireContext(), favEco, esFav -> {
                if (!isAdded()) return;
                toggleGuardarUnidad(favEco, esFav);
            });
            return true;
        });
        // Acciones de seguimiento MÚLTIPLE ("Seguir también"/"Detener todo"): antes dos botones
        // del mismo tamaño que "Seguir"; ahora un menú contextual para que no compitan
        // visualmente con la acción primaria -- misma lógica de siempre (intentarSeguirCarta()/
        // detenerTodosCarta()), solo se movió de dónde se dispara.
        cartaVistas.btnSeguirOpciones.setOnClickListener(this::abrirMenuOpcionesCarta);
        View btnCerrar = v.findViewById(R.id.btn_cerrar_carta);
        btnCerrar.setVisibility(View.VISIBLE);
        // Cierre en dos pasos ("isla contextual"): si está expandida, el primer toque solo
        // colapsa a la píldora; recién el segundo toque (ya colapsada) oculta todo.
        btnCerrar.setOnClickListener(b -> { if (cartaExpandida) colapsarCarta(); else ocultarCarta(); });
        // Toda selección nueva arranca COLAPSADA (píldora): identificar la unidad sin tapar el
        // mapa es el estado por defecto; el usuario decide si quiere expandir (ver expandirCarta()).
        cartaExpandida = false;
        cartaVistas.contenidoExpandido.setVisibility(View.GONE);
        cartaVistas.filaPildora.setVisibility(View.VISIBLE);
        cartaVistas.filaPildora.setOnClickListener(b -> expandirCarta());
        cartaContainer.setVisibility(View.VISIBLE);
        ajustarPanelZoom();
        LatLng posUnidad = u != null ? u.posicion : null;
        if (posUnidad == null) {
            Marker mUnidad = marcadoresUnidad.get(eco);
            if (mUnidad != null) posUnidad = mUnidad.getPosition();
        }
        if (posUnidad != null) destelloUnidad(posUnidad, ContextCompat.getColor(requireContext(), R.color.mb_red));
    }

    /** Píldora → carta completa: transición simple (fundido + reacomodo de alto, ~150ms, igual
     *  duración que ya usa ajustarPanelZoom()) sobre la MISMA tarjeta inflada -- no hay una
     *  segunda carta ni un segundo bind(), solo se intercambia qué sección se ve. */
    private void expandirCarta() {
        if (cartaVistas == null || cartaContainer == null) return;
        cartaExpandida = true;
        android.transition.TransitionManager.beginDelayedTransition(cartaContainer,
                new android.transition.AutoTransition().setDuration(150));
        cartaVistas.filaPildora.setVisibility(View.GONE);
        cartaVistas.contenidoExpandido.setVisibility(View.VISIBLE);
        ajustarPanelZoom();
    }

    /** Carta completa → píldora (ver btn_cerrar_carta en mostrarCartaUnidad(): el primer toque de
     *  cerrar colapsa, no oculta). */
    private void colapsarCarta() {
        if (cartaVistas == null || cartaContainer == null) return;
        cartaExpandida = false;
        android.transition.TransitionManager.beginDelayedTransition(cartaContainer,
                new android.transition.AutoTransition().setDuration(150));
        cartaVistas.contenidoExpandido.setVisibility(View.GONE);
        cartaVistas.filaPildora.setVisibility(View.VISIBLE);
        ajustarPanelZoom();
    }

    /** "Seguir" (inactivo) vs. "Siguiendo" (activo): mismo botón primario, solo cambia texto +
     *  icono + color para que el estado se note sin competir con ninguna otra acción -- la
     *  lógica de qué hace el click (iniciar/detener seguimiento) no cambia aquí. */
    private void actualizarBotonSeguirCarta() {
        if (cartaVistas == null) return;
        boolean sigue = ecoCartaActual != null && SeguimientoService.sigue(ecoCartaActual);
        if (sigue) {
            cartaVistas.btnSeguir.setText(R.string.siguiendo);
            cartaVistas.btnSeguir.setIconResource(R.drawable.ic_check);
            cartaVistas.btnSeguir.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.mb_exito)));
        } else {
            cartaVistas.btnSeguir.setText(R.string.seguir);
            cartaVistas.btnSeguir.setIconResource(R.drawable.ic_my_location);
            cartaVistas.btnSeguir.setBackgroundTintList(null);   // vuelve al color primario por defecto del estilo
        }
        // Misma bandera "sigue" reflejada en la píldora: NO es un estado paralelo, solo se nota
        // también cuando la carta está colapsada (selección != seguimiento, pero ambos deben
        // poder verse sin expandir).
        cartaVistas.txtPildoraEstado.setVisibility(sigue ? View.VISIBLE : View.GONE);
        // Botón de opciones (seguimiento múltiple + guardar + alerta de proximidad): visible
        // siempre que haya una carta abierta -- guardar/alertar NO dependen de estar siguiendo.
        cartaVistas.btnSeguirOpciones.setVisibility(ecoCartaActual != null ? View.VISIBLE : View.GONE);
    }

    /** Punto de entrada del menú contextual de la carta (botón ⋮): primero consulta el estado
     *  GUARDADA/ALERTA (Room, async) de la unidad actual, y solo entonces construye y muestra el
     *  menú con las etiquetas correctas -- "Siguiendo" ya se conoce sin consultar nada (en
     *  memoria, ver SeguimientoService.sigue()), así que no hace falta esperarlo. */
    private void abrirMenuOpcionesCarta(View ancla) {
        if (ecoCartaActual == null || !isAdded()) return;
        final String eco = ecoCartaActual;
        Telemetria.obtenerFavorito(requireContext(), eco, fav -> {
            if (!isAdded() || !eco.equals(ecoCartaActual)) return;   // la carta cambió mientras consultaba
            mostrarMenuOpcionesCarta(ancla, eco, fav);
        });
    }

    /** Menú contextual de la carta: "Seguir también"/"Detener todo" (seguimiento múltiple, sin
     *  cambios de lógica, solo se movieron aquí) + "Guardar"/"Alerta de proximidad" (unidades
     *  guardadas). Los tres conceptos (Guardada/Siguiendo/Alerta) son independientes: ninguna
     *  acción de guardar o de alerta llama jamás a SeguimientoService.iniciar()/detener(), y
     *  viceversa. {@code fav} es la fila actual de "economicos_favoritos" para {@code eco}, o
     *  null si todavía no está guardada. */
    private void mostrarMenuOpcionesCarta(View ancla, String eco, EconomicoFavoritoEntity fav) {
        boolean sigueEsta = SeguimientoService.sigue(eco);
        boolean guardada = fav != null;
        boolean alertaActiva = fav != null && fav.alertaActiva;
        int radioActual = fav != null ? fav.radioAlertaM : 500;

        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        menu.inflate(R.menu.seguimiento_opciones_menu);
        android.view.Menu m = menu.getMenu();
        m.findItem(R.id.menu_seguir_tambien).setEnabled(!sigueEsta);
        m.findItem(R.id.menu_guardar_unidad).setTitle(guardada ? R.string.menu_unidad_guardada : R.string.menu_guardar_unidad);
        m.findItem(R.id.menu_alerta_unidad).setTitle(alertaActiva ? R.string.menu_alerta_desactivar : R.string.menu_alerta_activar);
        // El radio solo tiene sentido (y solo se muestra) con la alerta ya activa.
        android.view.MenuItem itemRadio = m.findItem(R.id.menu_alerta_radio);
        itemRadio.setVisible(alertaActiva);
        itemRadio.setTitle(getString(R.string.menu_alerta_radio_fmt, etiquetaRadio(radioActual)));

        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_seguir_tambien) {
                if (!sigueEsta) intentarSeguirCarta();
            } else if (id == R.id.menu_detener_todo) {
                detenerTodosCarta();
            } else if (id == R.id.menu_guardar_unidad) {
                toggleGuardarUnidad(eco, guardada);
            } else if (id == R.id.menu_alerta_unidad) {
                toggleAlertaUnidad(eco, alertaActiva, radioActual);
            } else if (id == R.id.menu_alerta_radio) {
                mostrarMenuRadioAlerta(ancla, eco);
            }
            return true;
        });
        menu.show();
    }

    private static String etiquetaRadio(int metros) {
        return metros >= 1000 ? (metros / 1000) + " km" : metros + " m";
    }

    /** ⭐ Guardar / ★ Guardada (quitar) -- jamás toca SeguimientoService. Al guardar, cachea la
     *  línea actual si la unidad está en vivo en este momento (0/desconocida si no). */
    private void toggleGuardarUnidad(String eco, boolean yaGuardada) {
        if (!isAdded()) return;
        if (yaGuardada) {
            Telemetria.quitarFavorito(requireContext(), eco);
            Toast.makeText(requireContext(), getString(R.string.favorito_quitado, eco), Toast.LENGTH_SHORT).show();
        } else {
            UnidadReal u = RealtimeRepository.get().buscar(eco);
            int linea = (u != null && u.linea != null) ? u.linea : 0;
            Telemetria.guardarFavorito(requireContext(), eco, linea);
            Toast.makeText(requireContext(), getString(R.string.favorito_guardado, eco), Toast.LENGTH_SHORT).show();
        }
    }

    /** 🔔 Avisarme cuando esté cerca / 🔕 desactivar -- jamás toca SeguimientoService. Activarla
     *  guarda la unidad si todavía no lo estaba (no hay dónde persistir la preferencia si no);
     *  desactivarla nunca quita la unidad de guardadas. */
    private void toggleAlertaUnidad(String eco, boolean activaAhora, int radioActual) {
        if (!isAdded()) return;
        boolean nuevaActiva = !activaAhora;
        // Pide el permiso (si falta) para que AlertasUnidadesService pueda arrancar de verdad --
        // independiente de guardar la preferencia abajo: se pide y se guarda siempre juntos, sin
        // esperar la respuesta del permiso (ver permisoUbicacionAlerta y el javadoc de
        // AlertasUnidadesService.iniciar()).
        if (nuevaActiva && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacionAlerta.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        UnidadReal u = RealtimeRepository.get().buscar(eco);
        int linea = (u != null && u.linea != null) ? u.linea : 0;
        Telemetria.actualizarAlertaUnidad(requireContext(), eco, linea, nuevaActiva, () -> {
            if (!isAdded()) return;
            if (nuevaActiva) {
                Toast.makeText(requireContext(),
                        getString(R.string.alerta_activada_toast, eco, etiquetaRadio(radioActual)),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(),
                        getString(R.string.alerta_desactivada_toast, eco), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Submenú de radio (250/500/1km) para la unidad con alerta ya activa. */
    private void mostrarMenuRadioAlerta(View ancla, String eco) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        menu.inflate(R.menu.alerta_radio_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            int radioM = id == R.id.radio_250 ? 250 : id == R.id.radio_1000 ? 1000 : 500;
            Telemetria.actualizarRadioAlerta(requireContext(), eco, radioM, () -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        getString(R.string.radio_actualizado_toast, eco, etiquetaRadio(radioM)),
                        Toast.LENGTH_SHORT).show();
            });
            return true;
        });
        menu.show();
    }

    /** Detiene el seguimiento de TODAS las unidades (antes exclusivo de la pestaña Buscar). */
    private void detenerTodosCarta() {
        List<String> previamenteSeguidas = new ArrayList<>(SeguimientoService.ecosSeguidos);
        SeguimientoService.detener(requireContext(), null);   // sin económico = todas
        actualizarBotonSeguirCarta();
        // Quita el resaltado de cada una AL INSTANTE (sin esperar el poll de 10s).
        for (String eco : previamenteSeguidas) refrescarResaltadoSeguimiento(eco);
    }

    /** Aplica AL INSTANTE (sin esperar el siguiente poll) el cambio de resaltado visual de
     *  "Siguiendo" para {@code eco} tras una acción explícita desde la carta (Seguir/Detener/
     *  Detener todo), y reevalúa la atenuación de las demás unidades visibles. Mismo camino que ya
     *  usa el poll normal (icono solo se regenera si el estado realmente cambió); si {@code eco} ya
     *  no tiene marcador o no hay datos en vivo para recalcular su icono, el siguiente poll normal
     *  lo completa -- esto no bloquea ni falla por eso. */
    private void refrescarResaltadoSeguimiento(String eco) {
        Marker m = marcadoresUnidad.get(eco);
        if (m != null) {
            boolean siguiendoAhora = SeguimientoService.sigue(eco);
            boolean teniaHalo = halosSeguimiento.contains(eco);
            if (siguiendoAhora != teniaHalo) {
                UnidadReal u = RealtimeRepository.get().buscar(eco);
                if (u != null) m.setIcon(iconoParaUnidad(u));
                m.setZIndex(siguiendoAhora ? 20f : 10f);
                if (siguiendoAhora) { halosSeguimiento.add(eco); iniciarPulso(eco, m.getPosition()); }
                else { halosSeguimiento.remove(eco); detenerPulso(eco); }
            }
        }
        refrescarProminenciaUnidades();
    }

    /** Reevalúa la atenuación (alfa) de TODAS las unidades visibles según si hay alguna
     *  siguiéndose ahora mismo -- nunca regenera ningún icono/bitmap, solo ajusta transparencia
     *  de marcadores ya existentes (ver Marker.setAlpha, propiedad nativa del SDK de Maps). */
    private void refrescarProminenciaUnidades() {
        boolean haySeguidaGlobal = !SeguimientoService.ecosSeguidos.isEmpty();
        for (Map.Entry<String, Marker> e : marcadoresUnidad.entrySet()) {
            boolean sigueEsta = SeguimientoService.sigue(e.getKey());
            e.getValue().setAlpha(sigueEsta ? 1f : (haySeguidaGlobal ? ALFA_UNIDAD_NO_SEGUIDA : 1f));
        }
    }

    /** Verifica permisos y arranca el seguimiento de la unidad de la tarjeta. */
    private void intentarSeguirCarta() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacionCarta.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoNotifCarta.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarSeguimientoCarta();
    }

    private void arrancarSeguimientoCarta() {
        if (!isAdded() || ecoCartaActual == null) return;
        SeguimientoService.iniciar(requireContext(), ecoCartaActual);
        actualizarBotonSeguirCarta();
        refrescarResaltadoSeguimiento(ecoCartaActual);   // resaltado visible AL INSTANTE, sin esperar al poll
        Toast.makeText(requireContext(),
                getString(R.string.seguir_activado, ecoCartaActual), Toast.LENGTH_LONG).show();
    }

    /** Muestra la tarjeta de ESTACIÓN flotando sobre el mapa (vía {@link CartaEstacion}): reemplaza
     *  el tooltip genérico de 2 líneas, dándole a las estaciones el mismo trato que a las unidades. */
    private void mostrarCartaEstacion(EstMapa em) {
        if (!isAdded() || cartaContainer == null) return;
        cartaContainer.removeAllViews();
        View v = getLayoutInflater().inflate(R.layout.view_carta_estacion, cartaContainer, false);
        cartaContainer.addView(v);
        CartaEstacion.Vistas vistas = new CartaEstacion.Vistas(v);
        CartaEstacion.bind(requireContext(), vistas, em.e, em.linea, em.color);
        v.findViewById(R.id.btn_cerrar_carta_estacion).setOnClickListener(b -> ocultarCarta());
        // Manda al planificador con esta estación de destino YA fijada (línea conocida: se tocó
        // este marcador en concreto, así que no hace falta preguntar "¿a qué estación te refieres?"
        // aunque el nombre también exista en otra línea, p. ej. "1° de Mayo" en Mexibús L1 y L2).
        v.findViewById(R.id.btn_como_llegar_estacion).setOnClickListener(b ->
                ((MainActivity) requireActivity()).mostrarPlanificador(em.e.nombre, em.linea));
        cartaContainer.setVisibility(View.VISIBLE);
        ajustarPanelZoom();
        if (em.pos != null) destelloEstacion(em.pos, em.color);   // resalta el marcador seleccionado
    }

    /**
     * "Destello" para ubicar una estación: un aro (Circle, coordenadas reales) del color de su
     * línea que crece y se desvanece alrededor del punto varias veces y, al terminar, en vez de
     * quitarse por completo, se deja como un aro tenue y fijo (estado de transparencia) marcando
     * la estación -- así sigue siendo visible un momento después de que dejó de parpadear.
     * Se usa un Circle (no un Marker con bitmap animado) porque escala con el zoom del mapa y no
     * hace falta redibujar bitmaps en cada frame.
     */
    private void destelloEstacion(LatLng pos, int color) {
        destello(pos, color, true);
    }

    /** Igual que {@link #destelloEstacion}, pero sin dejar aro residual: para la unidad
     *  seleccionada, cuyo marcador se mueve y un aro fijo quedaría desactualizado. */
    private void destelloUnidad(LatLng pos, int color) {
        destello(pos, color, false);
    }

    private void destello(LatLng pos, int color, boolean dejarResidual) {
        if (mapa == null) return;
        if (destelloResidual != null) { try { destelloResidual.remove(); } catch (Exception ignore) {} }
        final com.google.android.gms.maps.model.Circle circulo = mapa.addCircle(
                new com.google.android.gms.maps.model.CircleOptions()
                        .center(pos)
                        .radius(14)
                        .strokeWidth(8f)
                        .strokeColor(color)
                        .fillColor(colorConAlfa(color, 90))
                        .zIndex(20f));
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1100);
        anim.setRepeatCount(3);
        anim.setRepeatMode(android.animation.ValueAnimator.RESTART);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            try {
                circulo.setRadius(14 + t * 70);               // 14 m -> 84 m: más notorio
                int alfa = Math.round(220 * (1 - t));          // se desvanece según crece
                circulo.setStrokeColor(colorConAlfa(color, alfa));
                circulo.setFillColor(colorConAlfa(color, Math.round(alfa * 0.35f)));
            } catch (Exception ignore) {}                      // el fragment pudo cerrarse a medio camino
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                try {
                    if (dejarResidual) {
                        // No se quita: queda un aro pequeño y muy transparente marcando la estación.
                        circulo.setRadius(16);
                        circulo.setStrokeColor(colorConAlfa(color, 90));
                        circulo.setFillColor(colorConAlfa(color, 25));
                        destelloResidual = circulo;
                    } else {
                        circulo.remove();
                    }
                } catch (Exception ignore) {}
            }
        });
        anim.start();
    }

    private static int colorConAlfa(int color, int alfa) {
        return Color.argb(alfa, Color.red(color), Color.green(color), Color.blue(color));
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
        mostrarCartaUnidad(eco, RealtimeRepository.get().buscar(eco));   // misma tarjeta que "Ver en mapa" mostraba en Buscar
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

    /** Gris neutro (Material Grey 500) para estaciones fuera de servicio en el mapa general. */
    private static final int GRIS_FUERA_SERVICIO = 0xFF9E9E9E;

    /** Paint con filtro de saturación 0: pinta un pictograma en escala de grises sin recrearlo. */
    private static Paint paintGris() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        return p;
    }

    /** Icono de estación. {@code fueraDeServicio} lo pinta en gris (pictograma desaturado, o punto
     *  gris si no hay pictograma) y le agrega el badge de afectación, para que se note en el mapa
     *  general cuál estación no opera sin tener que tocarla. */
    private BitmapDescriptor iconoEstacion(Estacion e, int color, boolean fueraDeServicio) {
        boolean nuevos = Modos.iconosNuevos(requireContext());
        String key = "E|" + (e.icono == null ? "" : e.icono) + "|" + color + "|" + (nuevos ? 1 : 0)
                + "|" + (fueraDeServicio ? 1 : 0);
        BitmapDescriptor cached = cacheIco.get(key);
        if (cached != null) return cached;

        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Bitmap escalado = Iconos.pictograma(requireContext(), e.icono, px);
        if (escalado != null) {
            c.drawBitmap(escalado, 0, 0, fueraDeServicio ? paintGris() : null);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            c.drawCircle(px / 2f, px / 2f, px * 0.34f, p);
            p.setColor(fueraDeServicio ? GRIS_FUERA_SERVICIO : color);
            c.drawCircle(px / 2f, px / 2f, px * 0.26f, p);
        }
        if (fueraDeServicio) dibujarBadgeAfectacion(c, px);
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIco.put(key, bd);
        return bd;
    }

    /** Badge rojo con "!" en la esquina superior derecha del icono, para marcar visualmente que la
     *  estación tiene una afectación activa (no solo el color gris, que por sí solo no explica por qué). */
    private void dibujarBadgeAfectacion(Canvas c, int px) {
        float r = px * 0.20f;
        float cx = px - r * 0.95f;
        float cy = r * 0.95f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r * 1.15f, p);   // borde blanco: que resalte sobre cualquier fondo
        p.setColor(0xFFC8103E);   // mb_red
        c.drawCircle(cx, cy, r, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(r * 1.5f);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText("!", cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }


    /** Icono de la unidad: degradado diagonal si va en ruta mixta, normal si no. Agrega un anillo
     *  (ver dibujarHalo) cuando la unidad está en SeguimientoService -- distinto del destello de
     *  selección (mb_red, animado y efímero): este es fijo mientras dure el seguimiento.
     *  CACHEADO (cacheIconoUnidad): el bitmap solo se regenera la primera vez que se ve esa
     *  combinación de (número, color[es], siguiendo); las llamadas repetidas (p. ej. al volver a
     *  evaluar en cada poll si hace falta repintar) reusan el BitmapDescriptor ya construido. */
    private BitmapDescriptor iconoParaUnidad(UnidadReal u) {
        boolean siguiendo = SeguimientoService.sigue(u.numero);
        RutasMixtas.Tramo t = RutasMixtas.tramo(u.origen, u.destino);
        if (t != null) {
            // arriba color de la línea de salida (origen), abajo la de término (destino)
            return iconoUnidadMixta(colorDeLinea(t.salida), colorDeLinea(t.termino), u.numero, siguiendo);
        }
        return iconoUnidad(colorDeLinea(u.linea), u.numero, siguiendo);
    }

    /** Bus del color de la línea (con halo blanco) y el económico en el parabrisas; si
     *  {@code siguiendo}, ~1.25x más grande, con halo más marcado y una cápsula con el económico
     *  debajo del bus (ver dibujarBadgeEconomico). La pulsación es un overlay aparte (Circle, ver
     *  iniciarPulso) -- este bitmap es siempre estático, nunca se anima ni se regenera por eso. */
    private BitmapDescriptor iconoUnidad(int color, String numero, boolean siguiendo) {
        String clave = numero + "|" + siguiendo + "|" + color;
        BitmapDescriptor cacheado = cacheIconoUnidad.get(clave);
        if (cacheado != null) return cacheado;

        int d = (int) ((siguiendo ? TAMANO_UNIDAD_SEGUIDA_DP : TAMANO_UNIDAD_DP) * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable bus = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            dibujarHalo(c, d, bus, siguiendo);
            int margen = d / 5;
            bus.setBounds(margen, margen, d - margen, d - margen);
            bus.setTint(color);
            bus.draw(c);
        }
        dibujarNumeroParabrisas(c, d, numero);
        if (siguiendo) dibujarBadgeEconomico(c, d, numero);
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIconoUnidad.put(clave, bd);
        return bd;
    }

    /**
     * Bus con degradado diagonal para unidades en ruta mixta (arriba línea de
     * salida, abajo línea de término), con el económico en el parabrisas. Mismo trato de
     * tamaño/halo/cápsula/caché que {@link #iconoUnidad} cuando {@code siguiendo}.
     */
    private BitmapDescriptor iconoUnidadMixta(int colorArriba, int colorAbajo, String numero, boolean siguiendo) {
        String clave = numero + "|" + siguiendo + "|" + colorArriba + "," + colorAbajo;
        BitmapDescriptor cacheado = cacheIconoUnidad.get(clave);
        if (cacheado != null) return cacheado;

        int d = (int) ((siguiendo ? TAMANO_UNIDAD_SEGUIDA_DP : TAMANO_UNIDAD_DP) * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable bus = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            dibujarHalo(c, d, bus, siguiendo);
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
        if (siguiendo) dibujarBadgeEconomico(c, d, numero);
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIconoUnidad.put(clave, bd);
        return bd;
    }

    /** Quita del caché de iconos cualquier entrada de {@code eco} (normal Y siguiendo, con
     *  cualquier color que haya tenido) -- se llama cuando su marcador se quita del mapa por no
     *  seguir en servicio, para no acumular bitmaps de unidades que ya no se ven. */
    private void limpiarCacheIconoUnidad(String eco) {
        cacheIconoUnidad.keySet().removeIf(k -> k.startsWith(eco + "|"));
    }

    /** Silueta blanca un poco más grande detrás del bus, para que resalte en el mapa. Si
     *  {@code siguiendo}, agrega ANTES un anillo verde (mb_exito) MÁS GRUESO que el de una unidad
     *  normal -- separado del halo blanco por un espacio, para que nunca se confunda con el color
     *  de la línea (ni siquiera en L7, cuyo color de línea coincide con mb_exito) ni con el
     *  destello rojo (efímero) de selección. El halo "en vivo" (pulsación) es un overlay aparte
     *  sobre el mapa (ver iniciarPulso), NO este anillo fijo del bitmap. */
    private void dibujarHalo(Canvas c, int d, Drawable bus, boolean siguiendo) {
        if (siguiendo) {
            Paint anillo = new Paint(Paint.ANTI_ALIAS_FLAG);
            anillo.setStyle(Paint.Style.STROKE);
            anillo.setStrokeWidth(d * 0.11f);   // más grueso que el 0.09f de antes: "más evidente"
            anillo.setColor(ContextCompat.getColor(requireContext(), R.color.mb_exito));
            c.drawCircle(d / 2f, d / 2f, d / 2f - anillo.getStrokeWidth() / 2f, anillo);
        }
        int margen = d / 5;
        int h = Math.round(d * 0.05f);
        bus.setBounds(margen - h, margen - h, d - margen + h, d - margen + h);
        bus.setTint(Color.WHITE);
        bus.draw(c);
    }

    /** Económico centrado en el parabrisas del bus: blanco con contorno oscuro. Mismo tratamiento
     *  para unidades normales y seguidas -- al ser {@code d} ~1.25x mayor cuando se sigue, el
     *  número ya sale proporcionalmente más grande solo por eso (ver dibujarBadgeEconomico para el
     *  refuerzo adicional, exclusivo de "Siguiendo"). */
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

    /** Cápsula con el económico COMPLETO, centrada en la parte inferior del icono (sobre el halo) --
     *  solo para la unidad SIGUIENDO (nunca por estar guardada ni por tener alerta activa: ver
     *  actualizarUnidades()/iconoParaUnidad(), que solo pasan siguiendo=true cuando
     *  SeguimientoService.sigue() lo es). El número del parabrisas (dibujarNumeroParabrisas) ya
     *  crece con el icono más grande, pero esta cápsula da una segunda referencia, de mayor
     *  contraste, que no depende de distinguir un número pequeño dentro del bus. Centrada (no en
     *  una esquina) para que un económico de 4-5 dígitos nunca se corte contra el borde del bitmap. */
    private void dibujarBadgeEconomico(Canvas c, int d, String numero) {
        if (numero == null || numero.isEmpty()) return;
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Tipografia.metro(requireContext()));
        tp.setFakeBoldText(true);
        float ts = d * 0.22f;
        tp.setTextSize(ts);
        float textW = tp.measureText(numero);

        float padH = d * 0.06f, padV = d * 0.03f;
        float capW = textW + padH * 2f, capH = ts + padV * 2f;
        float cx = d * 0.5f, cy = d * 0.84f;   // centrada, parte inferior, dentro de los límites del bitmap
        android.graphics.RectF rect = new android.graphics.RectF(cx - capW / 2f, cy - capH / 2f, cx + capW / 2f, cy + capH / 2f);

        Paint fondo = new Paint(Paint.ANTI_ALIAS_FLAG);
        fondo.setColor(0xFF10233A);   // mismo tono oscuro que el contorno del número del parabrisas
        c.drawRoundRect(rect, capH / 2f, capH / 2f, fondo);
        Paint borde = new Paint(Paint.ANTI_ALIAS_FLAG);
        borde.setStyle(Paint.Style.STROKE);
        borde.setStrokeWidth(d * 0.015f);
        borde.setColor(Color.WHITE);
        c.drawRoundRect(rect, capH / 2f, capH / 2f, borde);

        tp.setColor(Color.WHITE);
        float y = cy - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(numero, cx, y, tp);
    }

    /**
     * Pulsación suave y continua alrededor de la unidad SIGUIENDO: un {@link
     * com.google.android.gms.maps.model.Circle} (overlay vectorial del mapa, NO un bitmap) cuyo
     * radio/alfa oscilan con un {@link android.animation.ValueAnimator} infinito. No regenera
     * ningún icono ni bitmap -- es intencionalmente la opción simple (una sola forma geométrica
     * animada) en vez de redibujar el marcador en cada frame. En cada tick se reposiciona sobre
     * la posición ACTUAL del marcador (que puede estar a media animación de deslizamiento, ver
     * UnidadAnimador), así que el halo nunca se desincroniza aunque el bus se esté moviendo.
     */
    private void iniciarPulso(String eco, LatLng posInicial) {
        if (mapa == null || pulsosSeguimiento.containsKey(eco)) return;
        int color = ContextCompat.getColor(requireContext(), R.color.mb_exito);
        com.google.android.gms.maps.model.Circle circulo = mapa.addCircle(
                new com.google.android.gms.maps.model.CircleOptions()
                        .center(posInicial)
                        .radius(PULSO_RADIO_BASE_M)
                        .strokeWidth(0f)
                        .fillColor(colorConAlfa(color, PULSO_ALFA_BASE))
                        .zIndex(5f));   // bajo los marcadores (10f/20f), sobre el mapa base
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(PULSO_DURACION_MS);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);   // crece y decrece, nunca "salta"
        anim.addUpdateListener(a -> {
            try {
                float t = (float) a.getAnimatedValue();
                Marker m = marcadoresUnidad.get(eco);
                if (m != null) circulo.setCenter(m.getPosition());   // sigue al bus aunque se esté deslizando
                circulo.setRadius(PULSO_RADIO_BASE_M + t * PULSO_RADIO_EXTRA_M);
                circulo.setFillColor(colorConAlfa(color, Math.round(PULSO_ALFA_BASE * (1 - t * 0.5f))));
            } catch (Exception ignore) {}   // el fragment pudo cerrarse a medio camino
        });
        anim.start();
        pulsosSeguimiento.put(eco, circulo);
        pulsoAnimadores.put(eco, anim);
    }

    /** Detiene y quita el pulso de {@code eco} (si tenía uno). Seguro de llamar aunque no tuviera. */
    private void detenerPulso(String eco) {
        android.animation.ValueAnimator anim = pulsoAnimadores.remove(eco);
        if (anim != null) anim.cancel();
        com.google.android.gms.maps.model.Circle circulo = pulsosSeguimiento.remove(eco);
        if (circulo != null) { try { circulo.remove(); } catch (Exception ignore) {} }
    }

    /** Detiene TODOS los pulsos activos (limpieza general del mapa). */
    private void detenerTodosPulsos() {
        for (String eco : new ArrayList<>(pulsosSeguimiento.keySet())) detenerPulso(eco);
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
                if (u != null) {
                    actualizarUnidades(unidades, u.posicion);   // crea el marcador aunque esté lejos
                    centroCarga = u.posicion;
                    mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(u.posicion, 15f));
                }
                // Con u==null (no está en vivo) muestra la ficha del catálogo (offline), igual que
                // la pestaña Buscar -- ya no manda a otra pestaña para verla.
                mostrarCartaUnidad(eco, u);
            }

            @Override
            public void onError(String mensaje) {
                if (!isAdded()) return;
                mostrarCartaUnidad(eco, null);   // sin conexión: ficha del catálogo (offline)
            }
        });
    }

    /**
     * Búsqueda de estación (texto): resuelve el nombre de forma difusa y centra el mapa. Si son
     * varios andenes de la MISMA estación física (correspondencia real), centra en la zona
     * intermedia; si el nombre coincide pero son estaciones FÍSICAS distintas (p. ej. "1° de Mayo"
     * en Mexibús L1 y L2, a 828 m -- ver Planificador.agruparPorEstacionFisica), antes se promediaban
     * sus coordenadas igual y el mapa caía en un punto intermedio que no correspondía a NINGUNA de
     * las dos: ahora se pregunta a cuál se refiere, igual que ya hace el Planificador al trazar.
     */
    private void buscarEstacionEnMapa(String texto) {
        java.util.List<Planificador.Match> cs = Planificador.candidatos(requireContext(), texto);
        boolean mxb = Modos.mostrarMexibus(requireContext());
        boolean mxc = Modos.mostrarMexicable(requireContext());
        if (!mxb || !mxc) {   // al menos un sistema no-Metrobús oculto: filtra por el que corresponda
            java.util.List<Planificador.Match> f = new java.util.ArrayList<>();
            for (Planificador.Match m : cs) {
                int sis = Sistemas.sistemaDe(m.linea);
                if (sis == Sistemas.METROBUS
                        || (sis == Sistemas.MEXIBUS && mxb)
                        || (sis == Sistemas.MEXICABLE && mxc)) f.add(m);
            }
            cs = f;
        }
        if (cs.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.estacion_no_encontrada), Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<java.util.List<Planificador.Match>> grupos = Planificador.agruparPorEstacionFisica(cs);
        if (grupos.size() == 1) { centrarGrupoEnMapa(grupos.get(0)); return; }

        // Misma carta ilustrada (logo de línea + pictograma + etiqueta) que usa el Planificador al
        // trazar una ruta -- antes el buscador del mapa se quedaba con un AlertDialog de texto plano.
        CartaDesambiguacion.elegirEstacionFisica(requireContext(), getString(R.string.desamb_cual_estacion),
                grupos, this::centrarGrupoEnMapa);
    }

    /** Centra el mapa en el punto promedio de un grupo (un solo andén, o varios co-ubicados de una
     *  misma estación física real) y muestra/actualiza su carta -- antes solo se movía la cámara y la
     *  carta se quedaba pegada con lo último que se hubiera tocado (p. ej. otra estación de antes). */
    private void centrarGrupoEnMapa(java.util.List<Planificador.Match> grupo) {
        if (mapa == null) return;
        double lat = 0, lon = 0;
        for (Planificador.Match m : grupo) { lat += m.pos.latitude; lon += m.pos.longitude; }
        LatLng centro = new LatLng(lat / grupo.size(), lon / grupo.size());
        centroCarga = centro;
        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(centro, grupo.size() > 1 ? 15.5f : 16f));

        Planificador.Match rep = CartaDesambiguacion.repGrupo(grupo);
        EstMapa em = estacionPorNombreLinea(rep.nombre, rep.linea);
        if (em != null) mostrarCartaEstacion(em);
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
        halosSeguimiento.clear();
        detenerTodosPulsos();
        cacheIconoUnidad.clear();   // el GoogleMap de esta vista ya no existe; nada que reusar
        if (animUnidades != null) animUnidades.limpiar();
        estaciones.clear();
        mexibusEst.clear();
        mexibusLineas.clear();
        centroCarga = null;
        coloresLinea.clear();
        mapa = null;
        cartaContainer = null;
        cartaVistas = null;
        ecoCartaActual = null;
        cartaExpandida = false;
        panelZoom = null;
        super.onDestroyView();
    }
}
