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
        boolean transbordo;   // true = punto real de una ruta mixta (ver dibujarMixtas()): ícono diagonal
        // Nombre a mandar al planificador en "Cómo llegar aquí" cuando DIFIERE del nombre mostrado
        // (p. ej. los 3 puntos de H72 en agregarEstacionesH72(): "Alameda Tacubaya"/"De la Salle ·
        // dirección..." no existen así en ningún catálogo real, así que el planificador nunca
        // encontraba ruta -- null = usar e.nombre tal cual (el caso normal).
        String nombreRuta;
        EstMapa(Estacion e, int linea, int color) {
            this.e = e; this.linea = linea; this.color = color;
            this.pos = e.posicion; this.titulo = e.nombre;
        }
    }
    private final List<EstMapa> estaciones = new ArrayList<>();
    // Líneas base de Metrobús (dibujarRed()), para poder atenuarlas cuando hay una ruta trazada
    // encima (ver dibujarRuta()/aplicarOpacidadRed()) y que el trazo resalte más.
    private final List<Polyline> lineasRed = new ArrayList<>();
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
    private TextView bannerServidor;
    private long ultimaManifestMapa = -1;   // último Manifestaciones.actualizado() ya pintado en el mapa

    // ---- Planificador: calcular/dibujar la ruta + seguimiento en vivo, fusionado en el mapa
    // general (ya no hay pestaña ni pantalla aparte). No porta las unidades animadas sobre el
    // trazo de la versión vieja: el mapa general ya las muestra en todos lados.
    private EditText inOrigen, inDestino;
    private View panelResultado, panelResultadoDetalle, filaResultadoResumen, progressTrazando;
    private android.widget.ImageView icContraerResultado;
    private TextView resResumen, resAviso, resEstado, txtVelocidad;
    private android.widget.LinearLayout resPasosList;
    private android.widget.ScrollView resPasosScroll;
    private com.google.android.material.button.MaterialButton btnRecorrido;
    private androidx.appcompat.widget.SwitchCompat swExpress;   // Ordinario/Express (viaje por una sola línea Mexibús)
    private int svcBase;
    private androidx.recyclerview.widget.RecyclerView rvEstacionesRuta;
    private final EstacionRutaAdapter sliderAdapterRuta = new EstacionRutaAdapter();
    private String origenCanon, destinoCanon;
    private int origenLinea, destinoLinea;
    private LatLng origenSeleccionPos;
    private Planificador.Ruta rutaActiva;
    // Última ruta trazada (origen/destino/preferencia): ESTÁTICOS a propósito, para sobrevivir a que
    // se destruya y recree el fragmento (p. ej. al cambiar de pestaña y volver) -- permiten
    // retrazarSiRecorridoRuta() si el recorrido sigue activo, aunque rutaActiva (instancia) ya se
    // haya perdido.
    private static String ultOrigenRuta, ultDestinoRuta;
    private static int ultLineaORuta, ultLineaDRuta;
    private static java.util.Map<Integer, Boolean> ultPrefRuta;
    private static LatLng ultOrigenPosRuta;
    private final List<Polyline> trazoRuta = new ArrayList<>();
    private final List<Marker> marcadoresRuta = new ArrayList<>();
    private LatLngBounds ultimosLimitesRuta;
    private final java.util.concurrent.ExecutorService calcExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    // ---- Recorrido (seguimiento GPS en vivo sobre la ruta trazada) ----
    private static final double PASO_TRAZO_M = 12;   // separación de los puntos finos del trazo (progreso suave)
    private static final long TICK_RECORRIDO_MS = 400L;
    private List<LatLng> rutaPuntos;
    private List<LatLng> anclasZona;   // puntos densificados del trazo activo (progreso suave)
    private LatLng origenPos;
    private boolean avisoUnidad = false;
    private boolean recorrido = false;
    private boolean seguirCamara;      // durante el recorrido, la cámara sigue al usuario (como Maps)
    private Polyline progresoLine;     // overlay que opaca lo ya recorrido
    private final Runnable tickRecorrido = new Runnable() {
        @Override public void run() {
            try {
                if (recorrido) refrescarRecorridoUI();
            } catch (Exception e) {
                GeoMBApplication app = GeoMBApplication.get();
                if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "MapFragment.tickRecorrido", String.valueOf(e.getMessage()));
            } finally {
                if (isAdded()) handler.postDelayed(this, TICK_RECORRIDO_MS);
            }
        }
    };

    /** Ciclo de actualización: pide el feed, actualiza marcadores y se reprograma. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override
                public void onData(List<UnidadReal> unidades) {
                    avisoError = false;   // servidor OK de nuevo: permite reactivar el respaldo si vuelve a fallar
                    if (bannerServidor != null) {
                        // El feed sí llegó, pero por un camino que decodifica el .proto EN EL
                        // TELÉFONO (UnidadRtEdge/UnidadRtDirecto) en vez del JSON ya armado por el
                        // servidor -- avisa que puede ser menos preciso y que las notificaciones
                        // (FCM, siempre del lado del servidor) van a llegar más tarde. No es el
                        // mismo caso que onError (ahí NINGÚN camino respondió).
                        if (RealtimeRepository.ultimoCaminoLocal) {
                            bannerServidor.setText(R.string.mapa_interpretado_telefono);
                            bannerServidor.setVisibility(View.VISIBLE);
                        } else {
                            bannerServidor.setVisibility(View.GONE);
                        }
                    }
                    if (mapa != null) actualizarUnidades(unidades);
                    if (mapa != null) actualizarEstadoEstaciones();
                    revisarUnidad(unidades);
                }

                @Override
                public void onError(String mensaje) {
                    if (!isAdded()) return;
                    // En pausa no es un error: MainActivity ya mostró el aviso de "Servicio en
                    // pausa", y el banner/Toast de "sin conexión" de aquí abajo confundiría (daría
                    // a entender una falla técnica en vez de una pausa intencional).
                    if (EstadoServicio.enPausa()) return;
                    // Banner PERSISTENTE (no solo el Toast de antes, que se esfuma en segundos):
                    // pensado para una caída larga del servidor (p. ej. por falta de pago del EC2),
                    // no solo un bache de red pasajero -- se queda visible hasta que vuelva a
                    // responder (onData lo oculta arriba). Se fija el texto explícito -- no se
                    // puede asumir que quedó éste del XML si antes se mostró el de "interpretado
                    // en el teléfono" (onData con ultimoCaminoLocal). Un HTTP 404 (el servidor SÍ
                    // contesta, pero no tiene el recurso -- confirmado: así responde Railway hoy,
                    // con el deploy roto) es un caso distinto de un timeout/sin conexión: aquí se
                    // sabe que es el servidor el que está mal, no la red del usuario.
                    if (bannerServidor != null) {
                        bannerServidor.setText(Backend.ultimoHttpStatus == 404
                                ? R.string.mapa_servidor_caido : R.string.mapa_servidor_sin_respuesta);
                        bannerServidor.setVisibility(View.VISIBLE);
                    }
                    if (!avisoError) {
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
                // Desvíos/servicios de apoyo temporales (/admin/trazos del backend): se instalan en
                // GtfsRepository para que el siguiente "Trazar" ya los vea, sin recompilar la app.
                RutasTemporales.refrescar(requireContext(), null);
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
    private View panelOrigenDestino;   // panel origen/destino del planificador (también lo usa ajustarPanelZoom())
    private View searchBar;   // barra de búsqueda; btn_planificar la reemplaza por panelOrigenDestino en su lugar

    // Estilo CITI (Modos.estiloCiti): se decide al crear la vista -- cambiar de pestaña la recrea.
    private boolean citi;
    private int lineaCiti = 0;   // ruta elegida en "Menú" (0 = todas)
    private View barraCiti;
    // Deshabilitado salvo mientras panelOrigenDestino está visible (lo habilita btn_planificar):
    // consume el botón atrás del sistema SOLO en ese momento, para cerrar el planificador y
    // devolver la barra de búsqueda en vez de la acción atrás normal (salir de la pantalla/app).
    private final androidx.activity.OnBackPressedCallback callbackCerrarPlanificador =
            new androidx.activity.OnBackPressedCallback(false) {
                @Override public void handleOnBackPressed() { cerrarPlanificador(); }
            };

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
        bannerServidor = view.findViewById(R.id.banner_servidor);

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

        configurarPanelPlanificador(view);

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
        configurarCiti(view);
    }

    /** Estilo CITI: muestra la barra de la app de Indra y esconde el buscador y los paneles de
     *  GeoMB (esa app solo tenía el mapa, el +/- y "mi ubicación" nativos de Google Maps). */
    private void configurarCiti(View view) {
        citi = Modos.estiloCiti(requireContext());
        barraCiti = view.findViewById(R.id.barra_citi);
        if (!citi) return;
        barraCiti.setVisibility(View.VISIBLE);
        int[] ocultar = {R.id.search_bar, R.id.panel_zoom, R.id.panel_mapa, R.id.fab_filtros,
                R.id.txt_conteo, R.id.chip_filtros};
        for (int id : ocultar) view.findViewById(id).setVisibility(View.GONE);
        view.findViewById(R.id.citi_btn_menu).setOnClickListener(this::mostrarMenuCiti);
        view.findViewById(R.id.citi_modo_cerrar).setOnClickListener(v -> seleccionarRutaCiti(0));
    }

    /** "Menú": las rutas como en "Seleccione ruta" de la app original, más volver a ver todas o
     *  salir del estilo. */
    private void mostrarMenuCiti(View ancla) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), ancla);
        for (Linea l : GtfsRepository.getLineas(requireContext()))
            menu.getMenu().add(0, l.numero, l.numero, l.numero + "   " + EstiloCiti.nombreRuta(l.nombre));
        menu.getMenu().add(1, 0, 100, R.string.citi_ver_todas);
        menu.getMenu().add(1, -1, 101, R.string.citi_salir);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == -1) salirCiti();
            else seleccionarRutaCiti(item.getItemId());
            return true;
        });
        menu.show();
    }

    /** Deja solo la ruta {@code linea} (trazo, estaciones y unidades), como goToRoute() de la app
     *  original; 0 = todas. */
    private void seleccionarRutaCiti(int linea) {
        lineaCiti = linea;
        RealtimeRepository.filtro.linea = linea == 0 ? null : linea;
        for (Polyline p : lineasRed) {
            Object t = p.getTag();
            p.setVisible(linea == 0 || (t instanceof Integer && (Integer) t == linea));
        }
        aplicarVisibilidadEstaciones();
        onFiltrosCambiados();
        View caja = barraCiti != null ? barraCiti.findViewById(R.id.citi_modo_mapa) : null;
        if (caja != null) {
            caja.setVisibility(linea == 0 ? View.GONE : View.VISIBLE);
            if (linea != 0) {
                TextView t = caja.findViewById(R.id.citi_modo_linea);
                t.setText(String.valueOf(linea));
                android.graphics.drawable.GradientDrawable fondo = new android.graphics.drawable.GradientDrawable();
                fondo.setCornerRadius(3 * getResources().getDisplayMetrics().density);   // box_route
                fondo.setColor(EstiloCiti.color(linea, colorDeLinea(linea)));
                t.setBackground(fondo);
            }
        }
        Linea l = linea != 0 ? GtfsRepository.porNumero(requireContext(), linea) : null;
        if (l != null && mapa != null) mapa.animateCamera(CameraUpdateFactory.newLatLngBounds(l.limites(),
                (int) getResources().getDimension(R.dimen.citi_estacion)));
    }

    /** Apaga el estilo y vuelve a crear la vista del mapa con el de GeoMB. */
    private void salirCiti() {
        Modos.setEstiloCiti(requireContext(), false);
        if (lineaCiti != 0) RealtimeRepository.filtro.linea = null;
        getParentFragmentManager().beginTransaction().detach(this).commitNow();
        getParentFragmentManager().beginTransaction().attach(this).commitNow();
    }

    /** Toque de estación en estilo CITI (selectStopMarker() de la app original): acerca la cámara a
     *  zoom 16, abre el globo y sube el menú de la estación. */
    private void tocarEstacionCiti(EstMapa em, Marker m) {
        if (mapa != null) {
            if (mapa.getCameraPosition().zoom >= 16f) mapa.animateCamera(CameraUpdateFactory.newLatLng(m.getPosition()));
            else mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(m.getPosition(), 16f));
        }
        m.showInfoWindow();
        java.util.List<Object[]> botones = new java.util.ArrayList<>();
        botones.add(new Object[]{getString(R.string.citi_ver_tiempos), (Runnable) () -> {
            cerrarMenuCiti();
            mostrarCartaEstacion(em);
        }});
        botones.add(new Object[]{getString(R.string.citi_ver_rutas), (Runnable) () -> mostrarRutasEstacionCiti(em)});
        // Igual que el original: el botón no aparece si la estación ya es favorita.
        if (!EstiloCiti.esFavorita(requireContext(), em.linea, em.e.nombre)) {
            botones.add(new Object[]{getString(R.string.citi_anadir_favoritos), (Runnable) () -> {
                EstiloCiti.agregarFavorita(requireContext(), em.linea, em.e.nombre);
                Toast.makeText(requireContext(), getString(R.string.citi_favorito_agregado, em.e.nombre),
                        Toast.LENGTH_SHORT).show();
                cerrarMenuCiti();
            }});
        }
        abrirMenuCiti(botones);
    }

    /** "Ver rutas": las rutas que paran en esa estación (incluye las de otra línea con el mismo
     *  nombre, p. ej. un transbordo); elegir una la deja sola en el mapa. */
    private void mostrarRutasEstacionCiti(EstMapa em) {
        java.util.TreeSet<Integer> lineas = new java.util.TreeSet<>();
        String n = Planificador.norm(em.e.nombre);
        for (EstMapa otra : estaciones) if (Planificador.norm(otra.e.nombre).equals(n)) lineas.add(otra.linea);
        lineas.add(em.linea);
        java.util.List<Object[]> botones = new java.util.ArrayList<>();
        for (int ln : lineas) {
            Linea l = GtfsRepository.porNumero(requireContext(), ln);
            String txt = ln + "   " + (l != null ? EstiloCiti.nombreRuta(l.nombre) : "");
            botones.add(new Object[]{txt.trim(), (Runnable) () -> {
                cerrarMenuCiti();
                seleccionarRutaCiti(ln);
            }});
        }
        abrirMenuCiti(botones);
    }

    /** Llena la capa del menú con {@code botones} (texto, acción) + "Cancelar" y la sube desde abajo
     *  (slide_in_bottom de la app original: medio alto de pantalla en config_mediumAnimTime). */
    private void abrirMenuCiti(java.util.List<Object[]> botones) {
        android.widget.LinearLayout capa = getView() != null ? getView().findViewById(R.id.citi_menu_estacion) : null;
        if (capa == null) return;
        capa.removeAllViews();
        botones.add(new Object[]{getString(R.string.citi_cancelar), (Runnable) this::cerrarMenuCiti});
        LayoutInflater inf = getLayoutInflater();
        for (Object[] b : botones) {
            TextView btn = (TextView) inf.inflate(R.layout.boton_citi_menu, capa, false);
            btn.setText((String) b[0]);
            Runnable accion = (Runnable) b[1];
            btn.setOnClickListener(v -> accion.run());
            capa.addView(btn);
        }
        capa.setOnClickListener(v -> cerrarMenuCiti());   // tocar fuera de los botones = Cancelar
        int dur = getResources().getInteger(android.R.integer.config_mediumAnimTime);
        capa.setVisibility(View.VISIBLE);
        capa.post(() -> {
            View raiz = getView();
            capa.setTranslationY(raiz != null ? raiz.getHeight() * 0.5f : 0f);
            capa.animate().translationY(0f).setDuration(dur).start();
        });
    }

    private void cerrarMenuCiti() {
        View raiz = getView();
        View capa = raiz != null ? raiz.findViewById(R.id.citi_menu_estacion) : null;
        if (capa == null || capa.getVisibility() != View.VISIBLE) return;
        int dur = getResources().getInteger(android.R.integer.config_mediumAnimTime);
        capa.animate().translationY(raiz.getHeight() * 0.5f).setDuration(dur)
                .withEndAction(() -> { capa.setVisibility(View.GONE); capa.setTranslationY(0f); }).start();
    }

    /** ¿La línea se ve con la ruta elegida en estilo CITI? Fuera de ese estilo, siempre. */
    private boolean visibleCiti(int linea) {
        return !citi || lineaCiti == 0 || linea == lineaCiti;
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
        if (citi) {
            for (EstMapa em : estaciones) if (em.marker != null) em.marker.setVisible(visibles && visibleCiti(em.linea));
        } else {
            for (Marker m : marcadoresEstacion) m.setVisible(visibles);
        }
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
        if (citi) { chipFiltros.setVisibility(View.GONE); return; }
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
        if (citi) {
            // Igual que el fragment_mapa.xml de la app de Indra: sin brújula, rotación ni
            // inclinación; con el +/- y "mi ubicación" nativos de Google, debajo de la barra.
            mapa.getUiSettings().setZoomControlsEnabled(true);
            mapa.getUiSettings().setRotateGesturesEnabled(false);
            mapa.getUiSettings().setTiltGesturesEnabled(false);
            if (barraCiti != null) barraCiti.post(() -> {
                View fin = barraCiti.findViewById(R.id.citi_barra_fin);
                if (mapa != null && fin != null) mapa.setPadding(0, barraCiti.getTop() + fin.getBottom(), 0, 0);
            });
        } else if (esNoche()) {   // sigue el tema del dispositivo automáticamente
            mapa.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark));
        }
        activarCapaUbicacion();

        // Ventana de info (al seleccionar estación o unidad) con tipografía Tipo Metro.
        mapa.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(com.google.android.gms.maps.model.Marker m) { return null; }
            @Override public View getInfoContents(com.google.android.gms.maps.model.Marker m) {
                if (citi) {   // map_popup.xml de la app de Indra: sin la tipografía de GeoMB
                    View v = getLayoutInflater().inflate(R.layout.map_info_window_citi, null, false);
                    ((TextView) v.findViewById(R.id.citi_info_titulo)).setText(m.getTitle());
                    TextView s = v.findViewById(R.id.citi_info_sub);
                    String snip = m.getSnippet();
                    if (snip != null && !snip.isEmpty()) s.setText(snip); else s.setVisibility(View.GONE);
                    return v;
                }
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
            if (citi) {
                // Como la app original: la estación abre su menú; la unidad, solo su globo.
                EstMapa em = estacionDeMarcador(m);
                if (em == null) return false;
                tocarEstacionCiti(em, m);
                return true;
            }
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
        handler.removeCallbacks(tickRecorrido);
        handler.post(tickRecorrido);

        mapa.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) seguirCamara = false;
        });

        retrazarSiRecorridoRuta();   // si el recorrido sigue activo, vuelve a trazar la ruta guardada
    }

    /** Dibuja polylines y estaciones de las 7 líneas y guarda sus colores. */
    private void dibujarRed() {
        for (Linea linea : GtfsRepository.getLineas(requireContext())) {
            coloresLinea.put(linea.numero, linea.color);
            int colorTrazo = citi ? EstiloCiti.color(linea.numero, linea.color) : linea.color;
            float anchoTrazo = citi ? EstiloCiti.anchoTrazo(requireContext()) : 9f;

            if (linea.segmentos != null && !linea.segmentos.isEmpty()) {
                // Trazado oficial por tramos (ida/vuelta y ramales): sin huecos.
                for (java.util.List<LatLng> tramo : linea.segmentos) {
                    Polyline pl = mapa.addPolyline(new PolylineOptions()
                            .addAll(tramo)
                            .color(colorTrazo)
                            .width(anchoTrazo)
                            .geodesic(false));
                    pl.setTag(linea.numero);   // para filtrar por ruta en estilo CITI
                    lineasRed.add(pl);
                }
            } else {
                Polyline pl = mapa.addPolyline(new PolylineOptions()
                        .addAll(linea.ruta)
                        .color(colorTrazo)
                        .width(anchoTrazo)
                        .geodesic(false));
                pl.setTag(linea.numero);   // para filtrar por ruta en estilo CITI
                lineasRed.add(pl);
            }

            for (Estacion e : linea.estaciones) {
                if (linea.numero == 1 && "Indios Verdes".equals(e.nombre)) {
                    // Metrobús L1 Indios Verdes: 2 andenes separados (sur/norte), mismas coordenadas
                    // EXACTAS que usa Planificador.plataformaIndiosVerdes() para el aviso de llegada --
                    // antes el mapa solo mostraba un pin (el del catálogo, que coincidía con el andén
                    // sur) y nunca el norte. Único punto de verdad: si se corrige IV_L1_*, el mapa
                    // cambia solo (ver mismo patrón en Mexibús L4, abajo).
                    EstMapa a = new EstMapa(e, linea.numero, linea.color);
                    a.pos = Planificador.IV_L1_LLEGA; a.titulo = "Indios Verdes · andén sur (dir. Deportivo 18 de Marzo)";
                    EstMapa b = new EstMapa(e, linea.numero, linea.color);
                    b.pos = Planificador.IV_L1_SALE; b.titulo = "Indios Verdes · andén norte (ascenso)";
                    estaciones.add(a); estaciones.add(b);
                    continue;
                }
                EstMapa em = new EstMapa(e, linea.numero, linea.color);
                // "Tacubaya"/"De la Salle" de L2 son el extremo real de H72 hacia L7 -- llevan el
                // mismo ícono diagonal que los 3 puntos propios de H72 (agregarEstacionesH72()), a
                // pedido explícito del usuario tras ver la comparación: unifica visualmente toda la
                // zona de transbordo en vez de distinguir "punto exacto de H72" vs. "estación
                // troncal cercana".
                if (linea.numero == 2 && Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm(e.nombre)))
                    em.transbordo = true;
                estaciones.add(em);
            }
        }
        if (citi) return;   // la app original no tenía rutas mixtas ni corredores de calibración
        dibujarMixtas();
        dibujarZonasIndiosVerdes();
    }

    /** Dibuja los 4 corredores de cobertura de Indios Verdes (Planificador.Z_IV_*) como líneas
     *  delgadas sobre el mapa -- la misma fuente de verdad que usa Planificador.distanciaZona() para
     *  disparar el aviso de "llegando". Permite comparar a simple vista contra satélite/calle si el
     *  corredor registrado se mete donde no debe (p. ej. hasta el andén de la otra línea). Puramente
     *  visual/de calibración: no afecta la lógica de avisos. */
    private void dibujarZonasIndiosVerdes() {
        int grosor = 4;
        mapa.addPolyline(new PolylineOptions().addAll(java.util.Arrays.asList(Planificador.Z_IV_L1_LLEGA))
                .color(0xA0D40D0D).width(grosor).zIndex(6f).clickable(false));
        mapa.addPolyline(new PolylineOptions().addAll(java.util.Arrays.asList(Planificador.Z_IV_L1_SALE))
                .color(0xA0D40D0D).width(grosor).zIndex(6f).clickable(false));
        mapa.addPolyline(new PolylineOptions().addAll(java.util.Arrays.asList(Planificador.Z_IV_L4_LLEGA))
                .color(0xA0F8971B).width(grosor).zIndex(6f).clickable(false));
        mapa.addPolyline(new PolylineOptions().addAll(java.util.Arrays.asList(Planificador.Z_IV_L4_SALE))
                .color(0xA0F8971B).width(grosor).zIndex(6f).clickable(false));
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
                    .icon(iconoEstacionOTransbordo(em, fueraDeServicio(em)))
                    .anchor(0.5f, 0.5f)
                    .visible(visibles && visibleCiti(em.linea)));
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
        agregarEstacionesH72();
    }

    /** Las 3 paradas REALES del servicio de apoyo H72/SL01 ("París - Alameda Tacubaya", GTFS oficial
     *  CDMX, agencia MB, route_id B_CMX03SL01) -- NO son las mismas paradas físicas que "Tacubaya"/
     *  "De la Salle" del catálogo troncal de L2 (confirmado contra el GTFS: 103-150 m de diferencia,
     *  por encima de RADIO_MISMA_PARADA_M). Coordenadas tomadas directamente de stops.txt:
     *  B_03SL01-ATACUBAYA (Alameda Tacubaya), B_03SL01-SALLEOP (De La Salle, andén dirección Alameda
     *  Tacubaya) y B_03SL01-SALLE (De La Salle, andén dirección Glorieta Cuitláhuac). Se agregan como
     *  estaciones aparte (mismo patrón que los andenes sur/norte de Indios Verdes L4 en
     *  dibujarMexibus()), con el pictograma ya existente de Tacubaya/De la Salle reutilizado, y
     *  {@code transbordo=true} para que iconoEstacionOTransbordo() les dé el ícono diagonal L7/L2. */
    private void agregarEstacionesH72() {
        // nombreRuta: estos 3 nombres no existen así en ningún catálogo real (son el nombre del GTFS
        // de apoyo/con sufijo de andén), así que "Cómo llegar aquí" debe enrutar contra el nombre
        // REAL de la estación troncal de L2 más cercana -- si no, el planificador no encuentra ruta
        // (defecto confirmado en dispositivo).
        agregarEstacionTransbordo("Alameda Tacubaya", 19.401473, -99.185882, "ic_est_2_2", "Tacubaya");
        agregarEstacionTransbordo("De la Salle · dirección Alameda Tacubaya", 19.4083683433015, -99.18420775806, "ic_est_2_4", "De La Salle");
        agregarEstacionTransbordo("De la Salle · dirección Glorieta Cuitláhuac", 19.4090200863265, -99.1834667351685, "ic_est_2_4", "De La Salle");
    }

    private void agregarEstacionTransbordo(String nombre, double lat, double lon, String icono, String nombreRuta) {
        EstMapa em = new EstMapa(new Estacion(nombre, lat, lon, icono), 2, colorDeLinea(2));
        em.transbordo = true;
        em.nombreRuta = nombreRuta;
        estaciones.add(em);
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
            // Sentido contrario (ver Linea.rutaVuelta): calle físicamente distinta del otro carril,
            // NO la misma l.ruta invertida -- se dibuja como una segunda polyline independiente,
            // misma visibilidad/estilo, para no dejar sin trazo las calles exclusivas de ese sentido.
            if (l.rutaVuelta != null) {
                PolylineOptions pov = new PolylineOptions()
                        .addAll(l.rutaVuelta).color(l.color).geodesic(false).visible(vis)
                        .width(expres ? 7f : 9f).zIndex(expres ? 5f : 3f);
                if (expres) pov.pattern(punteado);
                Polyline plv = mapa.addPolyline(pov);
                plv.setTag(l.numero);
                mexibusLineas.add(plv);
            }
            // Los MARCADORES NO se crean aquí (eran cientos de golpe → congelaba el arranque). Solo se
            // registran; se crean por demanda al acercar, en crearMexibusVisibles() (igual que el Metrobús).
            for (Estacion e : l.estaciones) {
                if (l.numero == 104 && e.nombre.startsWith("Indios Verdes")) {
                    // Indios Verdes L4: 2 andenes separados (sur/norte), mismas coordenadas EXACTAS que
                    // Planificador.IV_L4_LLEGA/IV_L4_SALE (antes este mapa tenía su propio par de
                    // coordenadas hardcodeado aparte, que se fue desalineando ~10-26 m de la lógica real
                    // de avisos -- único punto de verdad ahora).
                    EstMapa a = new EstMapa(e, l.numero, l.color);
                    a.pos = Planificador.IV_L4_LLEGA; a.titulo = "Indios Verdes · andén sur (dir. La Raza)";
                    EstMapa b = new EstMapa(e, l.numero, l.color);
                    b.pos = Planificador.IV_L4_SALE; b.titulo = "Indios Verdes · andén norte (dir. UMB Tecámac)";
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
            em.marker.setIcon(iconoEstacionOTransbordo(em, fueraDeServicio(em, bloqueadas)));
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
        // En estilo CITI no hay Mexibús/Mexicable: la app de Indra era solo del Metrobús.
        boolean visMxb = !citi && Modos.mostrarMexibus(requireContext());
        boolean visMxc = !citi && Modos.mostrarMexicable(requireContext());
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
            for (EstMapa em : estaciones) if (em.marker != null && em.marker.isVisible()) em.marker.setIcon(iconoEstacionOTransbordo(em, fueraDeServicio(em)));
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
            lineasRed.add(mapa.addPolyline(new PolylineOptions().addAll(sh).color(a.color).width(7f).zIndex(2f)));
            lineasRed.add(mapa.addPolyline(new PolylineOptions().addAll(sh).color(b.color).width(7f)
                    .zIndex(2f).pattern(punteado)));
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
                        .title(citi ? getString(R.string.citi_autobus, u.numero) : "Unidad " + u.numero)
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
        if (citi && u.linea != null) {   // "Línea 1 El Caminero-Indios Verdes", como la app original
            String ruta = (u.origen != null && u.destino != null) ? u.origen + "-" + u.destino
                    : (u.destino != null ? u.destino : "");
            return getString(R.string.citi_linea_ruta, u.linea, ruta).trim();
        }
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
    /** Sube/baja panelZoom (fuera de su posición base, centrada entre search_bar y panel_mapa) para
     *  que no quede tapado por lo que esté visible encima o debajo: la carta de unidad/estación O el
     *  resultado del planificador (abajo, no coinciden visibles a la vez) y el panel de origen/
     *  destino del planificador (arriba). Antes solo consideraba la carta -- con el planificador
     *  fusionado al mapa general, panelZoom se quedaba fijo aunque esos otros paneles ocuparan todo
     *  su espacio. */
    private void ajustarPanelZoom() {
        if (panelZoom == null) return;
        panelZoom.post(() -> {
            // Ajuste puramente cosmético: cualquier excepción aquí (vista ya desprendida, medidas
            // en 0 por una carrera con el layout, etc.) nunca debe tumbar la app -- en el peor caso
            // el panel se queda donde estaba.
            try {
                if (!isAdded() || panelZoom.getHeight() == 0) return;
                float aire = 12f * panelZoom.getResources().getDisplayMetrics().density;
                int[] posPanel = new int[2];
                panelZoom.getLocationOnScreen(posPanel);
                int panelSuperior = posPanel[1];
                int altura = panelZoom.getHeight();

                // Banda vertical permitida: no antes de limiteArriba (abajo del panel de origen/
                // destino, si está visible) ni después de limiteAbajo (arriba de la carta o el
                // resultado del planificador, lo que esté visible; no coinciden a la vez).
                int limiteArriba = Integer.MIN_VALUE;
                if (panelOrigenDestino != null && panelOrigenDestino.getVisibility() == View.VISIBLE
                        && panelOrigenDestino.getHeight() > 0) {
                    int[] posOd = new int[2];
                    panelOrigenDestino.getLocationOnScreen(posOd);
                    limiteArriba = posOd[1] + panelOrigenDestino.getHeight() + (int) aire;
                }

                View limiteAbajoVista = null;
                if (cartaContainer != null && cartaContainer.getVisibility() == View.VISIBLE && cartaContainer.getHeight() > 0) {
                    limiteAbajoVista = cartaContainer;
                } else if (panelResultado != null && panelResultado.getVisibility() == View.VISIBLE && panelResultado.getHeight() > 0) {
                    limiteAbajoVista = panelResultado;
                }
                int limiteAbajo = Integer.MAX_VALUE;
                if (limiteAbajoVista != null) {
                    int[] posLim = new int[2];
                    limiteAbajoVista.getLocationOnScreen(posLim);
                    limiteAbajo = posLim[1] - (int) aire;
                }

                // Top deseado: lo más cerca posible de su posición natural, pero sin salirse de la
                // banda permitida -- si la banda es más angosta que el propio panel (pantalla chica,
                // ambos paneles expandidos a la vez), prioriza no treparse sobre el de abajo (el
                // activo/en primer plano) aunque eso implique quedar detrás del de arriba.
                int deseado = Math.max(limiteArriba, Math.min(panelSuperior, limiteAbajo - altura));
                panelZoom.animate().translationY(deseado - panelSuperior).setDuration(150).start();
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
        CartaEstacion.bind(requireContext(), vistas, em.e, em.linea, em.color, em.transbordo);
        v.findViewById(R.id.btn_cerrar_carta_estacion).setOnClickListener(b -> ocultarCarta());
        // Manda al planificador con esta estación de destino YA fijada (línea conocida: se tocó
        // este marcador en concreto, así que no hace falta preguntar "¿a qué estación te refieres?"
        // aunque el nombre también exista en otra línea, p. ej. "Las Américas" en Mexibús L1 y L2).
        // em.nombreRuta (si no es null) sustituye al nombre mostrado -- los 3 puntos de H72 muestran
        // un nombre que no existe en ningún catálogo real (ver agregarEstacionesH72()), así que
        // enrutan contra la estación troncal real en su lugar.
        String nombreParaRuta = em.nombreRuta != null ? em.nombreRuta : em.e.nombre;
        v.findViewById(R.id.btn_como_llegar_estacion).setOnClickListener(b -> {
            ocultarCarta();
            abrirPlanificadorConDestino(nombreParaRuta, em.linea);
        });
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

    /** Icono de estación: el normal de {@link #iconoEstacion}, salvo que {@code em.transbordo} esté
     *  activo -- ahí usa {@link #iconoEstacionTransbordo} (diagonal entre el tono de línea 7 y el de
     *  línea 2, ambos sacados de {@link #colorDeLinea}, que ya trae el mismo tono que los drawables
     *  linea_2.png/linea_7.png). Lo llevan los 3 puntos reales de H72 ({@link #agregarEstacionesH72})
     *  Y las estaciones troncales "Tacubaya"/"De la Salle" de L2 (ver
     *  {@link Iconos#ESTACIONES_TRANSBORDO_H72_L2}) -- pero con la diagonal INVERTIDA entre uno y
     *  otro grupo (a pedido explícito), para poder distinguirlos aunque ambos sean diagonales:
     *  troncal L2 = línea 7 arriba-izq / línea 2 abajo-der; puntos propios de H72 = al revés. */
    private BitmapDescriptor iconoEstacionOTransbordo(EstMapa em, boolean fueraDeServicio) {
        if (citi) {
            Linea l = GtfsRepository.porNumero(requireContext(), em.linea);
            java.util.List<Float> rumbos = l != null ? EstiloCiti.rumbos(l, em.pos) : new java.util.ArrayList<>();
            return EstiloCiti.iconoEstacion(requireContext(), em.e.icono,
                    EstiloCiti.color(em.linea, em.color), rumbos, fueraDeServicio);
        }
        if (em.transbordo) {
            boolean esTroncalL2 = Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm(em.e.nombre));
            int arriba = esTroncalL2 ? colorDeLinea(7) : colorDeLinea(2);
            int abajo = esTroncalL2 ? colorDeLinea(2) : colorDeLinea(7);
            return iconoEstacionTransbordo(em.e, arriba, abajo, fueraDeServicio);
        }
        return iconoEstacion(em.e, em.color, fueraDeServicio);
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

    /** Icono de estación TRANSBORDO (ver {@link #iconoEstacionOTransbordo}): mismo pictograma que
     *  {@link #iconoEstacion}, pero con su FONDO (no el símbolo blanco) partido en diagonal a 45°
     *  entre los 2 tonos de línea -- mismo truco visual que {@link #iconoUnidadMixta} usa para el
     *  bus, adaptado a un pictograma ya renderizado (PNG con el color horneado, no un vector con
     *  tint) vía {@link Iconos#recoloreaFondoDiagonal}. Sin pictograma, el disco de respaldo de
     *  {@link #iconoEstacion} se parte igual en 2 mitades diagonales. */
    private BitmapDescriptor iconoEstacionTransbordo(Estacion e, int colorA, int colorB, boolean fueraDeServicio) {
        boolean nuevos = Modos.iconosNuevos(requireContext());
        String key = "ET|" + (e.icono == null ? "" : e.icono) + "|" + colorA + "," + colorB + "|" + (nuevos ? 1 : 0)
                + "|" + (fueraDeServicio ? 1 : 0);
        BitmapDescriptor cached = cacheIco.get(key);
        if (cached != null) return cached;

        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Bitmap escalado = Iconos.pictograma(requireContext(), e.icono, px);
        if (escalado != null) {
            c.drawBitmap(fueraDeServicio ? escalado : Iconos.recoloreaFondoDiagonal(escalado, colorA, colorB),
                    0, 0, fueraDeServicio ? paintGris() : null);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            c.drawCircle(px / 2f, px / 2f, px * 0.34f, p);
            float cx = px / 2f, cy = px / 2f, r = px * 0.26f;
            Path arriba = new Path();   // triángulo superior-izquierdo
            arriba.moveTo(cx - r, cy - r); arriba.lineTo(cx + r, cy - r); arriba.lineTo(cx - r, cy + r); arriba.close();
            Path abajo = new Path();    // triángulo inferior-derecho
            abajo.moveTo(cx + r, cy - r); abajo.lineTo(cx + r, cy + r); abajo.lineTo(cx - r, cy + r); abajo.close();
            p.setColor(fueraDeServicio ? GRIS_FUERA_SERVICIO : colorA);
            c.save(); c.clipPath(arriba); c.drawCircle(cx, cy, r, p); c.restore();
            p.setColor(fueraDeServicio ? GRIS_FUERA_SERVICIO : colorB);
            c.save(); c.clipPath(abajo); c.drawCircle(cx, cy, r, p); c.restore();
        }
        if (fueraDeServicio) dibujarBadgeAfectacion(c, px);
        BitmapDescriptor bd = BitmapDescriptorFactory.fromBitmap(bmp);
        cacheIco.put(key, bd);
        return bd;
    }

    /** Icono de la unidad: degradado diagonal si va en ruta mixta, normal si no. Agrega un anillo
     *  (ver dibujarHalo) cuando la unidad está en SeguimientoService -- distinto del destello de
     *  selección (mb_red, animado y efímero): este es fijo mientras dure el seguimiento.
     *  CACHEADO (cacheIconoUnidad): el bitmap solo se regenera la primera vez que se ve esa
     *  combinación de (número, color[es], siguiendo); las llamadas repetidas (p. ej. al volver a
     *  evaluar en cada poll si hace falta repintar) reusan el BitmapDescriptor ya construido. */
    private BitmapDescriptor iconoParaUnidad(UnidadReal u) {
        if (citi) return EstiloCiti.iconoBus(requireContext());
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

    /** Cierra el panel de origen/destino (en cualquiera de sus 2 estados, expandido o colapsado a
     *  pastilla) y devuelve la barra de búsqueda a su lugar -- vía btn_cerrar_od o el botón atrás
     *  del sistema mientras el panel está abierto (ver callbackCerrarPlanificador). No toca
     *  panelResultado: si hay una ruta trazada, se sigue viendo normal. */
    private void cerrarPlanificador() {
        if (panelOrigenDestino == null || searchBar == null) return;
        panelOrigenDestino.setVisibility(View.GONE);
        searchBar.setVisibility(View.VISIBLE);
        callbackCerrarPlanificador.setEnabled(false);
        recentrarTrasPanel();
        ajustarPanelZoom();
    }

    /** Panel de origen/destino: abre/cierra, intercambia y traza sobre el mapa único (mismo
     *  Planificador.calcular(), sin tocarlo), incluida la pregunta Ordinario/Exprés por cada
     *  troncal Mexibús con ambos servicios. btn_planificar reemplaza la barra de búsqueda por este
     *  panel (misma posición, como si se transformara en la "pastilla de ruta") en vez de apilarlo
     *  debajo -- btn_cerrar_od o el botón atrás del sistema revierte. */
    private void configurarPanelPlanificador(View view) {
        searchBar = view.findViewById(R.id.search_bar);
        View panelOd = panelOrigenDestino = view.findViewById(R.id.panel_origen_destino);
        View expandido = view.findViewById(R.id.panel_od_expandido);
        View colapsado = view.findViewById(R.id.panel_od_colapsado);
        inOrigen = view.findViewById(R.id.input_origen);
        inDestino = view.findViewById(R.id.input_destino);
        progressTrazando = view.findViewById(R.id.progress_trazando);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callbackCerrarPlanificador);

        view.findViewById(R.id.btn_planificar).setOnClickListener(v -> {
            searchBar.setVisibility(View.GONE);
            panelOd.setVisibility(View.VISIBLE);
            expandido.setVisibility(View.VISIBLE);
            colapsado.setVisibility(View.GONE);
            callbackCerrarPlanificador.setEnabled(true);
            recentrarTrasPanel();
            ajustarPanelZoom();
        });
        view.findViewById(R.id.btn_cerrar_od).setOnClickListener(v -> cerrarPlanificador());

        view.findViewById(R.id.btn_location).setOnClickListener(v -> usarUbicacionOrigen());

        view.findViewById(R.id.btn_contraer_od).setOnClickListener(v -> {
            expandido.setVisibility(View.GONE);
            colapsado.setVisibility(View.VISIBLE);
            TextView txtColapsado = view.findViewById(R.id.txt_od_colapsado);
            String o = inOrigen.getText().toString().trim();
            String d = inDestino.getText().toString().trim();
            txtColapsado.setText((o.isEmpty() ? "?" : o) + " → " + (d.isEmpty() ? "?" : d));
            recentrarTrasPanel();
            ajustarPanelZoom();
        });
        colapsado.setOnClickListener(v -> {
            colapsado.setVisibility(View.GONE);
            expandido.setVisibility(View.VISIBLE);
            recentrarTrasPanel();
            ajustarPanelZoom();
        });

        view.findViewById(R.id.btn_intercambiar).setOnClickListener(v -> {
            String oc = origenCanon, dc = destinoCanon;
            CharSequence o = inOrigen.getText(), d = inDestino.getText();
            if (dc != null) setOrigenRuta(dc); else inOrigen.setText(d);
            if (oc != null) setDestinoRuta(oc); else inDestino.setText(o);
            if (o.length() > 0 && d.length() > 0) trazar();
        });

        view.findViewById(R.id.btn_trazar).setOnClickListener(v -> trazar());
        inOrigen.setOnEditorActionListener((v, id, e) -> { inDestino.requestFocus(); return true; });
        inDestino.setOnEditorActionListener((v, id, e) -> {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            trazar();
            return true;
        });

        panelResultado = view.findViewById(R.id.panel_resultado);
        panelResultadoDetalle = view.findViewById(R.id.panel_resultado_detalle);
        filaResultadoResumen = view.findViewById(R.id.fila_resultado_resumen);
        icContraerResultado = view.findViewById(R.id.ic_contraer_resultado);
        resResumen = view.findViewById(R.id.res_resumen);
        resPasosList = view.findViewById(R.id.res_pasos_list);
        resPasosScroll = view.findViewById(R.id.res_pasos_scroll);
        resAviso = view.findViewById(R.id.res_aviso);
        resEstado = view.findViewById(R.id.res_estado);
        txtVelocidad = view.findViewById(R.id.txt_velocidad);
        btnRecorrido = view.findViewById(R.id.btn_recorrido);
        swExpress = view.findViewById(R.id.sw_express);
        rvEstacionesRuta = view.findViewById(R.id.rv_estaciones_ruta);
        rvEstacionesRuta.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false));
        rvEstacionesRuta.setAdapter(sliderAdapterRuta);
        filaResultadoResumen.setOnClickListener(v -> alternarResultado());
        resAviso.setOnClickListener(v -> mostrarCartaAfectacionesRuta());
        btnRecorrido.setOnClickListener(v -> alternarRecorrido());
    }

    // ---- Planificador: calcular y dibujar la ruta ------------------------------------------

    private void setOrigenRuta(String canon) {
        origenCanon = canon; origenLinea = 0; origenSeleccionPos = null;
        inOrigen.setText(Planificador.sinMxb(canon));
    }

    private void setDestinoRuta(String canon) {
        destinoCanon = canon; destinoLinea = 0;
        inDestino.setText(Planificador.sinMxb(canon));
    }

    /** Botón de localización del panel: rellena el origen con la estación más cercana por GPS. */
    private void usarUbicacionOrigen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.recorrido_sin_permiso), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), getString(R.string.recorrido_ubicando), Toast.LENGTH_SHORT).show();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(l -> {
                    if (!isAdded() || l == null) return;
                    Estacion e = Planificador.masCercana(requireContext(),
                            new LatLng(l.getLatitude(), l.getLongitude()));
                    if (e == null) return;
                    resolverOrigenPorGpsRuta(e, () -> { if (inDestino.getText().length() > 0) trazar(); });
                });
    }

    /** Resuelve la estación más cercana por GPS por el mismo camino de candidatos/agrupación que el
     *  texto escrito a mano (ver desambiguarRuta): si hay más de una plataforma distinta muy cerca,
     *  pregunta en vez de quedarse en silencio con la geométricamente más cercana. */
    private void resolverOrigenPorGpsRuta(Estacion e, Runnable alTerminar) {
        inOrigen.setText(Planificador.sinMxb(e.nombre));
        origenCanon = null; origenLinea = 0; origenSeleccionPos = null;   // fuerza a NO tomar el atajo "sin cambios"
        desambiguarRuta(inOrigen, origenCanon, origenLinea, origenSeleccionPos, (om, or, lo, posO) -> {
            if (!isAdded()) return;
            origenCanon = or; origenLinea = lo; origenSeleccionPos = posO;
            if (om != null) inOrigen.setText(Planificador.sinMxb(om));
            if (alTerminar != null) alTerminar.run();
        });
    }

    private String resolverRuta(EditText campo, String canon) {
        String txt = campo.getText().toString().trim();
        if (canon != null && Planificador.norm(txt).equals(
                Planificador.norm(Planificador.sinMxb(Planificador.nombreCorto(canon)))))
            return canon;
        return Planificador.estacionParecida(requireContext(), txt);
    }

    private interface ResueltoRutaCb { void run(String mostrado, String ruta, int linea, LatLng pos); }

    /** Ejecuta Planificador.calcular en segundo plano (puede tardar >1 s) y entrega el resultado
     *  en el hilo principal, para no bloquear el hilo principal ni disparar un ANR. */
    private void calcularRutaAsync(java.util.concurrent.Callable<Planificador.Ruta> tarea,
                                    androidx.core.util.Consumer<Planificador.Ruta> alTerminar) {
        if (progressTrazando != null) progressTrazando.setVisibility(View.VISIBLE);
        calcExec.execute(() -> {
            Planificador.Ruta r;
            try { r = tarea.call(); } catch (Exception e) {
                r = null;
                GeoMBApplication app = GeoMBApplication.get();
                if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "MapFragment.calcularRutaAsync.tarea", String.valueOf(e.getMessage()));
            }
            final Planificador.Ruta res = r;
            if (!isAdded()) return;
            handler.post(() -> {
                if (progressTrazando != null) progressTrazando.setVisibility(View.GONE);
                try {
                    if (isAdded()) alTerminar.accept(res);
                } catch (Exception e) {
                    GeoMBApplication app = GeoMBApplication.get();
                    if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "MapFragment.calcularRutaAsync", String.valueOf(e.getMessage()));
                }
            });
        });
    }

    /** Abre el planificador embebido con el destino ya fijado (p. ej. "Cómo llegar" desde una
     *  estación): reemplaza la pantalla aparte que existía antes de fusionar el planificador al
     *  mapa general. Rellena el origen por GPS y traza sola, igual que antes. */
    private void abrirPlanificadorConDestino(String destino, int linea) {
        View root = getView();
        if (root == null || searchBar == null) return;
        searchBar.setVisibility(View.GONE);
        root.findViewById(R.id.panel_origen_destino).setVisibility(View.VISIBLE);
        root.findViewById(R.id.panel_od_expandido).setVisibility(View.VISIBLE);
        root.findViewById(R.id.panel_od_colapsado).setVisibility(View.GONE);
        callbackCerrarPlanificador.setEnabled(true);
        setDestinoRuta(destino);
        if (linea != 0) destinoLinea = linea;
        usarUbicacionOrigen();
    }

    /** Si hay un recorrido activo y el fragmento se recreó sin ninguna ruta dibujada todavía (p. ej.
     *  cambiaste a otra pestaña y volviste al mapa), re-traza la MISMA ruta (con su preferencia
     *  Ordinario/Exprés) sin volver a preguntar, para poder verla y reengancharse al seguimiento --
     *  dibujarRuta() ya se encarga de retomar recorrido/seguirCamara al ver RecorridoService.activo. */
    private void retrazarSiRecorridoRuta() {
        if (mapa == null || rutaActiva != null || ultDestinoRuta == null || !RecorridoService.activo) return;
        final String o = ultOrigenRuta, d = ultDestinoRuta;
        final int lo = ultLineaORuta, ld = ultLineaDRuta;
        final LatLng pos = ultOrigenPosRuta;
        final java.util.Map<Integer, Boolean> pref = ultPrefRuta != null ? ultPrefRuta : new java.util.HashMap<>();
        if (o != null) setOrigenRuta(o);
        setDestinoRuta(d);
        origenSeleccionPos = pos;
        calcularRutaAsync(() -> Planificador.calcular(requireContext(), o, d, lo, ld, pref, pos), r -> {
            if (r != null) dibujarRuta(r);
        });
    }

    private void trazar() {
        if (mapa == null) return;
        desambiguarRuta(inOrigen, origenCanon, origenLinea, origenSeleccionPos, (om, or, lo, posO) ->
                desambiguarRuta(inDestino, destinoCanon, destinoLinea, null, (dm, dr, ld, posD) ->
                        trazarResueltoRuta(om, or, lo, posO, dm, dr, ld)));
    }

    private void trazarResueltoRuta(String origenMostrado, String origen, int lineaO, LatLng posOrigen,
                                     String destinoMostrado, String destino, int lineaD) {
        if (destino == null) {
            Toast.makeText(requireContext(), getString(R.string.estacion_no_encontrada), Toast.LENGTH_SHORT).show();
            return;
        }
        if (origen == null) { origen = destino; origenMostrado = destinoMostrado; lineaO = lineaD; posOrigen = null; }

        final String fo = origen; final int flo = lineaO; final String fd = destino; final int fld = lineaD;
        final String fom = origenMostrado; final String fdm = destinoMostrado; final LatLng fpos = posOrigen;
        // Preferencia SUAVE sembrada desde el servicio con el que origen/destino ya quedaron fijados:
        // sin esto, el cálculo queda pineado a exactamente esa línea (102 u 122, p. ej.) sin poder
        // caer al servicio hermano de la MISMA base si justo ese cerró. La pregunta explícita
        // Ordinario/Exprés por cada troncal (si aplica) viene después de este primer cálculo.
        final java.util.Map<Integer, Boolean> prefInicial = new java.util.HashMap<>();
        sembrarPrefServicioRuta(prefInicial, flo);
        sembrarPrefServicioRuta(prefInicial, fld);
        forzarOrdinarioSiExpresBloqueadoRuta(prefInicial);
        calcularRutaAsync(() -> Planificador.calcular(requireContext(), fo, fd, flo, fld, prefInicial, fpos), r -> {
            if (r == null) {
                Toast.makeText(requireContext(), mensajeFalloRuta(fd), Toast.LENGTH_LONG).show();
                return;
            }
            origenCanon = fom; origenLinea = flo; origenSeleccionPos = fpos;
            inOrigen.setText(flo == 0 ? Planificador.sinMxb(fom) : Planificador.nombreMostrar(requireContext(), fom, flo));
            destinoCanon = fdm; destinoLinea = fld;
            inDestino.setText(fld == 0 ? Planificador.sinMxb(fdm) : Planificador.nombreMostrar(requireContext(), fdm, fld));
            ultOrigenRuta = fo; ultDestinoRuta = fd; ultLineaORuta = flo; ultLineaDRuta = fld;
            ultPrefRuta = new java.util.HashMap<>(); ultOrigenPosRuta = fpos;
            // Preferencia de servicio por CADA línea troncal Mexibús (10X) con exprés que use la
            // ruta: si no hay ninguna, se dibuja directo (caso de siempre, Metrobús puro o sin
            // troncales con exprés); si hay, se pregunta Ordinario/Exprés por cada una antes de
            // dibujar la versión final (ver preguntarServicioPorLineaRuta más abajo).
            java.util.List<Integer> bases = basesTroncalesConExpressRuta(r);
            final java.util.Map<Integer, Boolean> prefForzado = new java.util.HashMap<>();
            forzarOrdinarioSiExpresBloqueadoRuta(prefForzado);
            if (bases.isEmpty()) { dibujarRuta(r); return; }
            preguntarServicioPorLineaRuta(fo, flo, fd, fld, fpos, bases, prefForzado, 0);
        });
    }

    /** Bases troncales Mexibús (101..104) con servicio exprés que aparecen en la ruta, EXCLUYENDO las
     *  que no tienen exprés disponible ahora mismo (Manifestaciones.expresBloqueado): no tiene caso
     *  preguntar Ordinario/Exprés si no hay una elección real -- ver forzarOrdinarioSiExpresBloqueadoRuta. */
    private java.util.List<Integer> basesTroncalesConExpressRuta(Planificador.Ruta r) {
        java.util.LinkedHashSet<Integer> bases = new java.util.LinkedHashSet<>();
        for (Planificador.Parada p : r.secuencia) {
            if (p.linea >= 111 && p.linea <= 119) continue;
            int base = Servicios.base(p.linea);
            if (base >= 101 && base <= 104 && !Manifestaciones.expresBloqueado(base)) bases.add(base);
        }
        return new java.util.ArrayList<>(bases);
    }

    /** Fuerza preferencia Ordinario (soft, cae de todos modos si no hubiera otra vía) para cualquier
     *  base troncal cuyo exprés esté fuera de servicio ahora mismo, según el feed de afectaciones
     *  Mexibús (ver AfectMexibusFeed/Manifestaciones.setMexibusExpresBloqueado). */
    private static void forzarOrdinarioSiExpresBloqueadoRuta(java.util.Map<Integer, Boolean> pref) {
        for (int base = 101; base <= 104; base++)
            if (Manifestaciones.expresBloqueado(base)) pref.put(base, false);
    }

    /** Pregunta el servicio (Ordinario/Exprés) por cada base troncal, en secuencia; al terminar,
     *  recalcula con la preferencia global (con fallback donde el exprés no llegue) y dibuja. */
    private void preguntarServicioPorLineaRuta(String origen, int lineaO, String destino, int lineaD, LatLng posOrigen,
                                                java.util.List<Integer> bases,
                                                java.util.Map<Integer, Boolean> pref, int idx) {
        if (idx >= bases.size()) {
            final String fo = origen; final int flo = lineaO; final String fd = destino; final int fld = lineaD;
            final LatLng fpos = posOrigen;
            final java.util.Map<Integer, Boolean> fpref = pref;
            ultLineaORuta = flo; ultLineaDRuta = fld; ultPrefRuta = new java.util.HashMap<>(fpref);
            ultOrigenPosRuta = fpos;
            calcularRutaAsync(() -> Planificador.calcular(requireContext(), fo, fd, flo, fld, fpref, fpos), r -> {
                if (r == null) {
                    Toast.makeText(requireContext(), mensajeFalloRuta(fd), Toast.LENGTH_LONG).show();
                    return;
                }
                dibujarRuta(r);
            });
            return;
        }
        int base = bases.get(idx), exp = base + 20;
        String et = Planificador.etiquetaLineaCortaPub(base);
        int colorBase = CartaDesambiguacion.colorLinea(requireContext(), base);
        int colorExp = CartaDesambiguacion.colorLinea(requireContext(), exp);
        java.util.List<CartaDesambiguacion.Opcion> op = new java.util.ArrayList<>();
        op.add(new CartaDesambiguacion.Opcion(0, CartaDesambiguacion.badgeLinea(requireContext(), colorBase, et), getString(R.string.servicio_ordinario), () -> {
            pref.put(base, false);
            preguntarServicioPorLineaRuta(origen, lineaO, destino, lineaD, posOrigen, bases, pref, idx + 1);
        }).col(colorBase));
        op.add(new CartaDesambiguacion.Opcion(0, CartaDesambiguacion.badgeLinea(requireContext(), colorExp, et), getString(R.string.servicio_express), () -> {
            pref.put(base, true);
            preguntarServicioPorLineaRuta(origen, lineaO, destino, lineaD, posOrigen, bases, pref, idx + 1);
        }).col(colorExp));
        CartaDesambiguacion.mostrar(requireContext(), getString(R.string.servicio_titulo_linea, "L" + et), op);
    }

    private static void sembrarPrefServicioRuta(java.util.Map<Integer, Boolean> pref, int linea) {
        if (linea < 101 || linea > 124) return;
        if (linea >= 111 && linea <= 119) return;
        pref.put(Servicios.base(linea), linea >= 121 && linea <= 124);
    }

    private void desambiguarRuta(EditText campo, String canonRec, int lineaRec, LatLng posRec, ResueltoRutaCb cb) {
        String canon = resolverRuta(campo, canonRec);
        if (canon == null) { cb.run(null, null, 0, null); return; }
        if (canon.equals(canonRec)) { cb.run(canon, Planificador.nombreRutaDeAlias(canon), lineaRec, posRec); return; }

        java.util.List<Planificador.Match> cs = Planificador.candidatos(requireContext(), canon);
        boolean mxb = Modos.mostrarMexibus(requireContext());
        boolean mxc = Modos.mostrarMexicable(requireContext());
        if (!mxb || !mxc) {
            java.util.List<Planificador.Match> f = new java.util.ArrayList<>();
            for (Planificador.Match m : cs) {
                int sis = Sistemas.sistemaDe(m.linea);
                if (sis == Sistemas.METROBUS
                        || (sis == Sistemas.MEXIBUS && mxb)
                        || (sis == Sistemas.MEXICABLE && mxc)) f.add(m);
            }
            cs = f;
        }
        if (cs.isEmpty()) { cb.run(canon, canon, 0, null); return; }
        java.util.List<java.util.List<Planificador.Match>> grupos = Planificador.agruparPorEstacionFisica(cs);
        if (grupos.size() == 1) { fijarRuta(campo, grupos.get(0).get(0), cb); return; }
        CartaDesambiguacion.elegirEstacionFisica(requireContext(), getString(R.string.desamb_cual_estacion), grupos,
                g -> fijarRuta(campo, CartaDesambiguacion.repGrupo(g), cb));
    }

    private void fijarRuta(EditText campo, Planificador.Match m, ResueltoRutaCb cb) {
        String nombreReal = m.nombreRuta != null ? m.nombreRuta : m.nombre;
        int pin = m.linea < 100 ? 0 : m.linea;
        campo.setText(Planificador.nombreMostrar(requireContext(), m.nombre, m.linea));
        cb.run(m.nombre, nombreReal, pin, m.pos);
    }

    private String mensajeFalloRuta(String destino) {
        if (Planificador.motivoFallo == Planificador.MOTIVO_ESTACION_CERRADA) {
            String est = Planificador.estacionCerrada != null ? Planificador.estacionCerrada : destino;
            int cat = Manifestaciones.razonCierre(Planificador.norm(est));
            int motivo = cat == Manifestaciones.C_MANTENIMIENTO ? R.string.ruta_motivo_mantenimiento
                    : cat == Manifestaciones.C_ESTADO ? R.string.ruta_motivo_bloqueo
                    : R.string.ruta_motivo_afectacion;
            return getString(R.string.ruta_estacion_cerrada, est, getString(motivo));
        }
        if (Planificador.motivoFallo == Planificador.MOTIVO_HORARIO) {
            int linea = Planificador.lineaHorarioCerrado;
            return linea > 0
                    ? getString(R.string.ruta_fuera_servicio_linea, etiquetaHorarioRuta(linea))
                    : getString(R.string.ruta_fuera_servicio);
        }
        if (Planificador.motivoFallo == Planificador.MOTIVO_AFECTACION) {
            return getString(R.string.ruta_corte_bloqueo);
        }
        return getString(R.string.ruta_sin_ruta, destino);
    }

    private static String etiquetaHorarioRuta(int n) {
        if (n < 100) return "L" + n;
        if (n >= 121 && n <= 124) return "MXB L" + (n - 120) + " exprés";
        if (n < 200) return "MXB L" + (n >= 111 && n <= 113 ? (n - 110) + "A" : String.valueOf(n - 100));
        return "MXC L" + (n - 200);
    }

    private String infoHorarioRuta(Planificador.Ruta r) {
        java.util.LinkedHashSet<Integer> claves = new java.util.LinkedHashSet<>();
        for (Planificador.Parada p : r.secuencia) {
            int k = p.linea;
            if (k >= 121 && k <= 124 && !Horarios.tieneLinea(requireContext(), k)) k -= 20;
            claves.add(k);
        }
        if (claves.isEmpty()) return null;
        java.util.Calendar ahora = java.util.Calendar.getInstance();
        StringBuilder info = new StringBuilder(), fuera = new StringBuilder();
        for (int k : claves) {
            String v = Horarios.ventanaHoy(requireContext(), k, ahora);
            if (v == null) continue;
            String etq = etiquetaHorarioRuta(k);
            if (info.length() > 0) info.append(" · ");
            info.append(etq).append(" ").append(v);
            if (!Horarios.lineaCircula(requireContext(), k, ahora)) {
                if (fuera.length() > 0) fuera.append(", ");
                fuera.append(etq);
            }
        }
        if (info.length() == 0) return null;
        StringBuilder msg = new StringBuilder(getString(R.string.ruta_horario_hoy, info.toString()));
        if (fuera.length() > 0) msg.append("\n").append(getString(R.string.ruta_fuera_horario, fuera.toString()));
        return msg.toString();
    }

    private static boolean tocaLugarRutaPlan(String lugarNorm, java.util.Set<String> nombresRuta) {
        if (lugarNorm.isEmpty()) return false;
        for (String n : nombresRuta) {
            if (n.length() < 3) continue;
            if (lugarNorm.contains(n) || n.contains(lugarNorm)) return true;
        }
        return false;
    }

    private static boolean rutaTieneAfectacionPlan(Planificador.Ruta r) {
        if (r == null) return false;
        java.util.Set<Integer> lineasRuta = new java.util.HashSet<>();
        if (r.instrucciones != null)
            for (Planificador.Instruccion ins : r.instrucciones) lineasRuta.add(ins.linea);
        java.util.Set<String> nombresRuta = new java.util.HashSet<>();
        for (Planificador.Parada p : r.secuencia) nombresRuta.add(Planificador.norm(p.nombre));
        for (Manifestaciones.Afectacion a : Manifestaciones.lista()) {
            if (lineasRuta.contains(a.lineaNum) || tocaLugarRutaPlan(Planificador.norm(a.lugar), nombresRuta))
                return true;
        }
        return false;
    }

    /**
     * Dibuja la ruta calculada SOBRE EL MAPA GENERAL -- a propósito NUNCA llama mapa.clear(), que
     * borraría las unidades/estaciones que MapFragment ya tiene pintadas: lleva sus PROPIOS
     * overlays (trazoRuta/marcadoresRuta) y solo limpia esos antes de redibujar.
     */
    private void dibujarRuta(Planificador.Ruta r) {
        if (mapa == null) return;
        for (Polyline p : trazoRuta) p.remove();
        trazoRuta.clear();
        for (Marker m : marcadoresRuta) m.remove();
        marcadoresRuta.clear();
        if (progresoLine != null) { progresoLine.remove(); progresoLine = null; }
        rutaActiva = r;
        avisoUnidad = false;
        origenPos = r.pasos.isEmpty() ? null : r.pasos.get(0).puntos.get(0);

        // Ancho/zIndex por encima de las líneas base (lineasRed/mexibusLineas, width 7-9f) para que
        // el trazo resalte por sí mismo; además se atenúan esas líneas de fondo abajo.
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        rutaPuntos = new ArrayList<>();
        for (Planificador.Paso p : r.pasos) {
            trazoRuta.add(mapa.addPolyline(new PolylineOptions()
                    .addAll(p.puntos).color(p.color).width(22f).zIndex(5f)));
            rutaPuntos.addAll(p.puntos);
            for (LatLng pt : p.puntos) bounds.include(pt);
        }
        anclasZona = densificar(rutaPuntos, PASO_TRAZO_M);   // puntos finos para el progreso suave
        for (int k = 1; k < r.pasos.size(); k++) {
            List<LatLng> ant = r.pasos.get(k - 1).puntos, act = r.pasos.get(k).puntos;
            if (ant.isEmpty() || act.isEmpty()) continue;
            trazoRuta.add(mapa.addPolyline(new PolylineOptions()
                    .add(ant.get(ant.size() - 1)).add(act.get(0))
                    .color(r.pasos.get(k).color).width(14f).zIndex(4f)));
        }
        aplicarOpacidadRed(true);
        for (Planificador.Parada p : r.secuencia) {
            boolean esTroncalH72L2 = p.linea == 2 && Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm(p.nombre));
            boolean esPropioH72 = p.linea == 2 && Iconos.ESTACIONES_PROPIAS_H72.contains(Planificador.norm(p.nombre));
            BitmapDescriptor ic = (esTroncalH72L2 || esPropioH72)
                    ? bitmapEstacionTransbordoRuta(p.icono, colorDeRuta(esTroncalH72L2 ? 7 : 2), colorDeRuta(esTroncalH72L2 ? 2 : 7))
                    : bitmapEstacionRuta(p.icono, p.color);
            if (ic == null) continue;
            String titulo = Planificador.nombreMostrar(requireContext(), p.nombre, p.linea);
            marcadoresRuta.add(mapa.addMarker(new MarkerOptions().position(p.pos).title(titulo)
                    .icon(ic).anchor(0.5f, 0.5f).zIndex(6f)));
        }
        ultimosLimitesRuta = bounds.build();

        resResumen.setText(getString(R.string.ruta_resumen, r.paradas, r.transbordos, r.minutos));
        sliderAdapterRuta.set(r.secuencia, r.instrucciones, requireContext());
        pintarPasosRuta(r);
        StringBuilder aviso = new StringBuilder();
        if (rutaTieneAfectacionPlan(r)) aviso.append(getString(R.string.ruta_alterna));
        String hor = infoHorarioRuta(r);
        if (hor != null) { if (aviso.length() > 0) aviso.append("\n"); aviso.append(hor); }
        resAviso.setVisibility(aviso.length() > 0 ? View.VISIBLE : View.GONE);
        if (aviso.length() > 0) resAviso.setText(aviso.toString());
        panelResultado.setVisibility(View.VISIBLE);
        configurarSwitchServicio(r);

        boolean veniaRecorrido = recorrido;
        if (recorrido) detenerRecorrido();
        resEstado.setVisibility(View.GONE);
        btnRecorrido.setText(R.string.recorrido_iniciar);

        // Si ya había un recorrido en curso (el servicio sigue vivo aunque se recalculó la ruta),
        // reengancha el seguimiento en vez de dejarlo detenido.
        if (!veniaRecorrido && RecorridoService.activo) {
            recorrido = true;
            seguirCamara = true;
            btnRecorrido.setText(R.string.recorrido_detener);
            resEstado.setVisibility(View.VISIBLE);
            resEstado.setText(R.string.recorrido_ubicando);
            rvEstacionesRuta.setVisibility(View.GONE);
        }

        // Espera al siguiente layout: panel_resultado recién se hizo VISIBLE (arriba) y su alto real
        // (según cuántos pasos tenga) todavía no está medido -- encuadrar ANTES de eso da un padding
        // de solo 16dp en vez del alto real de la tarjeta, y el trazo queda tapado por ella.
        View root = getView();
        if (root != null) root.post(() -> { if (isAdded()) encuadrarRutaActiva(); });
        ajustarPanelZoom();
    }

    /** Encuadra TODO el trazo ya dibujado (ultimosLimitesRuta) dejando libre el espacio real que
     *  ocupa panel_resultado en el fondo, para que la tarjeta no tape parte de la ruta. A diferencia
     *  de centrarRecorrido() (sigue al usuario durante el recorrido en vivo), esta es la vista
     *  "panorámica" de toda la ruta: se usa al trazar y cada vez que el espacio disponible cambia
     *  (ver recentrarTrasPanel()) mientras NO hay un recorrido activo. */
    private void encuadrarRutaActiva() {
        if (rutaActiva == null || mapa == null || ultimosLimitesRuta == null) return;
        View root = getView();
        if (root == null) return;
        int m = (int) (16 * getResources().getDisplayMetrics().density);
        int abajo = panelResultado.getVisibility() == View.VISIBLE
                ? Math.max(0, root.getHeight() - panelResultado.getTop()) : 0;
        mapa.setPadding(m, m, m, abajo + m);
        mapa.animateCamera(CameraUpdateFactory.newLatLngBounds(ultimosLimitesRuta, 0));
    }

    private List<LatLng> densificar(List<LatLng> pts, double pasoM) {
        List<LatLng> out = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return out;
        out.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            LatLng a = pts.get(i - 1), b = pts.get(i);
            double dist = Linea.distancia(a, b);
            int n = (int) Math.floor(dist / pasoM);
            for (int j = 1; j <= n; j++) {
                double t = (j * pasoM) / dist;
                out.add(new LatLng(a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t));
            }
            out.add(b);
        }
        return out;
    }

    /** Opaca (con un overlay gris translúcido) la parte de la ruta ya recorrida hasta {@code pos}. */
    private void pintarProgreso(LatLng pos) {
        // Usa el trazo FINO (densificado) para que el gris avance suave aunque vayas entre 2 estaciones.
        List<LatLng> base = (anclasZona != null && anclasZona.size() >= 2) ? anclasZona : rutaPuntos;
        if (mapa == null || base == null || base.size() < 2 || pos == null) return;
        int k = 0; double best = Double.MAX_VALUE;
        for (int i = 0; i < base.size(); i++) {
            double dd = Linea.distancia(base.get(i), pos);
            if (dd < best) { best = dd; k = i; }
        }
        List<LatLng> hecho = new ArrayList<>(base.subList(0, Math.max(2, k + 1)));
        if (progresoLine != null) progresoLine.setPoints(hecho);
        else progresoLine = mapa.addPolyline(new PolylineOptions().addAll(hecho)
                .color(0xFF9E9E9E).width(20f).zIndex(7f));   // gris sólido encima = "ya recorrido" (estilo Maps)
    }

    /** Centra la cámara en el usuario durante el recorrido (respeta el padding de los paneles y el
     *  zoom actual). Solo re-centra si se movió apreciablemente, para no dar tirones cada segundo. */
    private void centrarRecorrido(LatLng pos) { centrarRecorrido(pos, false); }

    /** @param forzar ignora el "ya está centrado" de abajo y recentra aunque el target no se haya
     *  movido -- para cuando lo que cambió fue el PADDING (p. ej. al minimizar un panel): el target
     *  sigue igual, pero visualmente el encuadre quedó desfasado hacia el trazo completo ya dibujado
     *  en vez de la posición actual (ver {@link #recentrarTrasPanel()}). */
    private void centrarRecorrido(LatLng pos, boolean forzar) {
        if (!seguirCamara || pos == null || mapa == null) return;
        View root = getView();
        if (root == null) return;
        int m = (int) (16 * getResources().getDisplayMetrics().density);
        int abajoReal = panelResultado.getVisibility() == View.VISIBLE
                ? root.getHeight() - panelResultado.getTop() : 0;
        int abajo = Math.min(abajoReal, Math.round(root.getHeight() * 0.18f));
        mapa.setPadding(m, m, m, abajo + m);
        LatLng centro = mapa.getCameraPosition().target;
        if (!forzar && centro != null && Linea.distancia(centro, pos) < 25) return;   // ya está centrado
        float z = Math.max(16f, mapa.getCameraPosition().zoom);
        mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, z));
    }

    /** Al minimizar/expandir el panel de resultado o el de origen/destino, el espacio libre del mapa
     *  cambia pero la cámara no se ajusta sola: durante un recorrido activo el encuadre puede quedar
     *  mostrando el trazo COMPLETO ya dibujado (el de {@link #dibujarRuta}) en vez de la posición
     *  actual del usuario; sin recorrido, el trazo puede quedar tapado por la tarjeta en vez de
     *  encuadrado en el espacio que sí queda libre. Espera al siguiente layout (el panel recién
     *  cambió de tamaño) y corrige lo que aplique. No hace nada si el usuario ya movió el mapa a
     *  mano durante el recorrido (seguirCamara en false). */
    private void recentrarTrasPanel() {
        if (!recorrido && rutaActiva == null) return;
        View root = getView();
        if (root == null) return;
        root.post(() -> {
            if (!isAdded()) return;
            if (recorrido) centrarRecorrido(RecorridoService.ultimaPos, true);
            else encuadrarRutaActiva();
        });
    }

    /**
     * Muestra el switch Ordinario/Express en el panel SOLO si el viaje va por una sola línea Mexibús
     * que ofrece ambos servicios. Al cambiarlo, re-rutea (el Express salta estaciones) y redibuja.
     */
    private void configurarSwitchServicio(Planificador.Ruta r) {
        if (swExpress == null) return;
        int base = baseUnicaMexibus(r.secuencia);
        java.util.List<Servicios.Servicio> ops = base != 0
                ? Servicios.disponibles(requireContext(), base, origenCanon, destinoCanon, false)
                : new java.util.ArrayList<>();
        boolean hayOrd = false, hayExp = false;
        for (Servicios.Servicio s : ops) { if (s.linea == base) hayOrd = true; if (s.linea == base + 20) hayExp = true; }
        if (!(hayOrd && hayExp)) { swExpress.setVisibility(View.GONE); svcBase = 0; return; }

        svcBase = base;
        boolean esExpress = false;
        for (Planificador.Parada p : r.secuencia) if (p.linea == base + 20) { esExpress = true; break; }
        swExpress.setOnCheckedChangeListener(null);   // evita disparar el listener al fijar el estado
        swExpress.setChecked(esExpress);
        swExpress.setVisibility(View.VISIBLE);
        swExpress.setOnCheckedChangeListener((btn, isChecked) -> {
            int linea = isChecked ? svcBase + 20 : svcBase;
            final String o = origenCanon, d = destinoCanon;
            // El pin de línea (lineaO/lineaD) por sí solo NO sesga los tramos intermedios -- solo
            // fija qué andén es el origen/destino. Sin el mapa de preferencia, un corredor directo
            // sin transbordos podía recalcular con el mismo resultado (mismas paradas/minutos) pase
            // lo que pase el switch, porque no había nada que realmente empujara el A* hacia el otro
            // servicio. El mapa de preferencia es el mecanismo real (penalServicio/penalTramo).
            final java.util.Map<Integer, Boolean> pref = new java.util.HashMap<>();
            pref.put(svcBase, isChecked);
            calcularRutaAsync(() -> Planificador.calcular(requireContext(), o, d, linea, linea, pref), nr -> {
                if (nr != null && !nr.secuencia.isEmpty()) dibujarRuta(nr);
            });
        });
    }

    /** Base Mexibús única del viaje (101..104) si TODO el recorrido va por esa línea; 0 si no aplica. */
    private static int baseUnicaMexibus(java.util.List<Planificador.Parada> seq) {
        int base = 0;
        for (Planificador.Parada p : seq) {
            int b = Servicios.base(p.linea);
            if (b < 100 || b >= 200) return 0;         // hay tramo Metrobús/Mexicable
            if (base == 0) base = b; else if (base != b) return 0;   // más de una línea Mexibús
        }
        return base;
    }

    private void alternarRecorrido() {
        if (recorrido) detenerRecorrido();
        else iniciarRecorrido();
    }

    private void iniciarRecorrido() {
        if (rutaActiva == null || rutaActiva.secuencia.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.recorrido_sin_permiso), Toast.LENGTH_LONG).show();
            return;
        }
        // El servicio (Ordinario/Express) por línea ya se eligió en el trazado; la ruta activa lo refleja.
        arrancarRecorrido();
    }

    private void arrancarRecorrido() {
        recorrido = true;
        seguirCamara = true;   // la cámara empieza a seguir al usuario
        btnRecorrido.setText(R.string.recorrido_detener);
        resEstado.setVisibility(View.VISIBLE);
        resEstado.setText(R.string.recorrido_ubicando);
        // El carrusel completo ya cumplió su función al planear; durante el recorrido, el estado
        // en vivo (este mismo texto) dice lo mismo con menos espacio -- en pantallas chicas la
        // tarjeta quedaba demasiado alta mostrando ambos a la vez.
        rvEstacionesRuta.setVisibility(View.GONE);
        String destinoFinal = rutaActiva.secuencia.get(rutaActiva.secuencia.size() - 1).nombre;
        RecorridoService.servicioTexto = null;
        java.util.List<String> terms = new ArrayList<>();
        if (rutaActiva.instrucciones != null)
            for (Planificador.Instruccion ins : rutaActiva.instrucciones) terms.add(ins.terminal);
        RecorridoService.terminalesTramo = terms;
        RecorridoService.iniciar(requireContext(), rutaActiva.secuencia, destinoFinal);
    }

    private void detenerRecorrido() {
        recorrido = false;
        seguirCamara = false;
        btnRecorrido.setText(R.string.recorrido_iniciar);
        resEstado.setVisibility(View.GONE);
        txtVelocidad.setVisibility(View.GONE);
        rvEstacionesRuta.setVisibility(View.VISIBLE);
        sliderAdapterRuta.setActual(-1);
        RecorridoService.detener(requireContext());
    }

    /** Muestra la velocidad GPS actual (RecorridoService la calcula con cada fix). Se oculta si el
     *  proveedor todavía no la reporta (-1), y se pone en rojo al llegar al mismo umbral que dispara
     *  el aviso de exceso de velocidad del servicio, para que coincida con la notificación. */
    private void actualizarVelocimetro() {
        float kmh = RecorridoService.ultimaVelocidadKmh;
        if (kmh < 0) { txtVelocidad.setVisibility(View.GONE); return; }
        txtVelocidad.setVisibility(View.VISIBLE);
        txtVelocidad.setText(getString(R.string.velocidad_valor_kmh, Math.round(kmh)));
        txtVelocidad.setTextColor(kmh >= RecorridoService.UMBRAL_VELOCIDAD_KMH
                ? ContextCompat.getColor(requireContext(), R.color.mb_red)
                : com.google.android.material.color.MaterialColors.getColor(txtVelocidad, com.google.android.material.R.attr.colorOnSurface));
    }

    /** Lee del servicio en qué estación va el usuario y actualiza el estado + progreso + cámara. */
    private void refrescarRecorridoUI() {
        if (!recorrido || rutaActiva == null || !isAdded()) return;
        int idx = RecorridoService.actualIdx;
        List<Planificador.Parada> seq = rutaActiva.secuencia;
        if (idx < 0 || idx >= seq.size()) return;
        Planificador.Parada act = seq.get(idx);
        LatLng posReal = RecorridoService.ultimaPos != null ? RecorridoService.ultimaPos : act.pos;
        pintarProgreso(posReal);
        centrarRecorrido(RecorridoService.ultimaPos);
        sliderAdapterRuta.setActual(idx);
        centrarSliderRuta(idx);   // mantiene la estación actual CENTRADA en el deslizador horizontal
        actualizarVelocimetro();
        java.util.function.Function<Planificador.Parada, String> vis =
                p -> Planificador.nombreMostrar(requireContext(), p.nombre, p.linea);
        if (idx >= seq.size() - 1) {
            resEstado.setText(getString(R.string.recorrido_llegaste, vis.apply(act)));
        } else {
            Planificador.Parada sig = seq.get(idx + 1);
            if (sig.transbordo) resEstado.setText(getString(R.string.recorrido_transborda, vis.apply(sig), Planificador.etiquetaLineaCortaPub(sig.linea)));
            else resEstado.setText(getString(R.string.recorrido_vas, vis.apply(act), vis.apply(sig)));
        }
    }

    /** Desplaza el deslizador horizontal para dejar la estación {@code idx} CENTRADA (animado), de
     *  modo que la tarjeta se vaya moviendo conforme avanza el recorrido en vez de solo asomarla. */
    private void centrarSliderRuta(int idx) {
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rvEstacionesRuta.getLayoutManager();
        if (lm == null) return;
        int pos = sliderAdapterRuta.posDe(idx);
        if (pos < 0) return;
        androidx.recyclerview.widget.LinearSmoothScroller s =
                new androidx.recyclerview.widget.LinearSmoothScroller(requireContext()) {
                    @Override public int calculateDtToFit(int vs, int ve, int bs, int be, int sp) {
                        return (bs + (be - bs) / 2) - (vs + (ve - vs) / 2);   // centra el item en la vista
                    }
                    @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
                        return 60f / dm.densityDpi;   // desplazamiento suave
                    }
                };
        s.setTargetPosition(pos);
        lm.startSmoothScroll(s);
    }

    /** Avisa una vez cuando la unidad del primer tramo se acerca a tu estación de origen. */
    private void revisarUnidad(List<UnidadReal> unidades) {
        if (!isAdded() || rutaActiva == null || origenPos == null || avisoUnidad
                || rutaActiva.pasos.isEmpty() || unidades == null) return;
        Planificador.Paso p0 = rutaActiva.pasos.get(0);
        for (UnidadReal u : unidades) {
            boolean mismaLinea = u.linea != null && u.linea == p0.linea;
            boolean mismoSentido = u.destino == null || u.destino.isEmpty()
                    || Planificador.norm(u.destino).contains(Planificador.norm(p0.destino))
                    || Planificador.norm(p0.destino).contains(Planificador.norm(u.destino));
            if (mismaLinea && mismoSentido
                    && Linea.distancia(u.posicion, origenPos) <= Config.SEGUIR_CERCA_M) {
                avisoUnidad = true;
                Toast.makeText(requireContext(),
                        getString(R.string.ruta_unidad_cerca, p0.linea, p0.destino, p0.origen),
                        Toast.LENGTH_LONG).show();
                break;
            }
        }
    }

    private void mostrarCartaAfectacionesRuta() {
        if (rutaActiva == null || !isAdded()) return;
        java.util.Set<Integer> lineasRuta = new java.util.HashSet<>();
        if (rutaActiva.instrucciones != null)
            for (Planificador.Instruccion ins : rutaActiva.instrucciones) lineasRuta.add(ins.linea);
        java.util.Set<String> nombresRuta = new java.util.HashSet<>();
        for (Planificador.Parada p : rutaActiva.secuencia) nombresRuta.add(Planificador.norm(p.nombre));

        java.util.Map<String, Manifestaciones.Afectacion> porLineaCat = new java.util.LinkedHashMap<>();
        for (Manifestaciones.Afectacion a : Manifestaciones.lista()) {
            boolean tocaLinea = lineasRuta.contains(a.lineaNum);
            boolean tocaLugar = tocaLugarRutaPlan(Planificador.norm(a.lugar), nombresRuta);
            if (!tocaLinea && !tocaLugar) continue;
            String k = a.lineaNum + "|" + a.categoria;
            Manifestaciones.Afectacion actual = porLineaCat.get(k);
            int largoNuevo = a.estado.length() + a.lugar.length() + a.info.length();
            int largoActual = actual == null ? -1
                    : actual.estado.length() + actual.lugar.length() + actual.info.length();
            if (actual == null || largoNuevo > largoActual) porLineaCat.put(k, a);
        }
        List<Manifestaciones.Afectacion> relevantes = new ArrayList<>(porLineaCat.values());

        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.afectaciones_carta_titulo)
                .setPositiveButton(android.R.string.ok, null);
        if (relevantes.isEmpty()) {
            b.setMessage(R.string.afectaciones_carta_vacio);
        } else {
            androidx.recyclerview.widget.RecyclerView rv = new androidx.recyclerview.widget.RecyclerView(requireContext());
            rv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int pad = Math.round(8 * getResources().getDisplayMetrics().density);
            rv.setPadding(pad, pad, pad, pad);
            rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(requireContext()));
            AfectacionesAdapter ad = new AfectacionesAdapter();
            ad.set(relevantes);
            rv.setAdapter(ad);
            b.setView(rv);
        }
        b.show();
    }

    private void alternarResultado() {
        if (panelResultadoDetalle == null) return;
        if (panelResultadoDetalle.getVisibility() == View.VISIBLE) {
            panelResultadoDetalle.setVisibility(View.GONE);
            icContraerResultado.setRotation(90);
        } else {
            panelResultadoDetalle.setVisibility(View.VISIBLE);
            icContraerResultado.setRotation(-90);
        }
        recentrarTrasPanel();
        ajustarPanelZoom();
    }

    private void pintarPasosRuta(Planificador.Ruta r) {
        resPasosList.removeAllViews();
        java.util.List<Planificador.Paso> pasos = r.pasos;
        java.util.List<Planificador.Instruccion> ins = r.instrucciones;
        int n = Math.min(pasos == null ? 0 : pasos.size(), ins == null ? 0 : ins.size());
        for (int t = 0; t < n; t++) {
            Planificador.Paso paso = pasos.get(t);
            if (t > 0 && (paso.pasajeExtra || !mismoTrazoRuta(pasos.get(t - 1).linea, paso.linea))) {
                Planificador.Paso prev = pasos.get(t - 1);
                String texto; boolean camina;
                if (paso.pasajeExtra) {
                    camina = true;
                    texto = getString(R.string.ruta_pasaje_extra,
                            Planificador.nombreMostrar(requireContext(), paso.origen, paso.linea));
                } else {
                    boolean mismaEstacion = prev.destino != null && paso.origen != null
                            && (Planificador.norm(Planificador.sinMxb(prev.destino))
                                    .equals(Planificador.norm(Planificador.sinMxb(paso.origen)))
                                || Planificador.nucleoCoincide(prev.destino, paso.origen));
                    camina = prev.destino != null && paso.origen != null && !mismaEstacion;
                    if (camina) {
                        texto = getString(R.string.ruta_camina,
                                Planificador.nombreMostrar(requireContext(), paso.origen, paso.linea));
                    } else {
                        String verbo = getString(verboTransferenciaRuta(prev.linea, paso.linea));
                        if (!verbo.isEmpty()) verbo = Character.toUpperCase(verbo.charAt(0)) + verbo.substring(1);
                        texto = verbo + " · L" + Planificador.etiquetaLineaCortaPub(paso.linea);
                    }
                }
                resPasosList.addView(filaTransferRuta(camina, texto));
            }
            resPasosList.addView(filaIndicacionRuta(ins.get(t)));
        }
    }

    private static boolean mismoTrazoRuta(int a, int b) {
        int ca = (a >= 121 && a <= 124) ? a - 20 : a;
        int cb = (b >= 121 && b <= 124) ? b - 20 : b;
        return ca == cb;
    }

    private static int verboTransferenciaRuta(int lineaAnt, int lineaAct) {
        boolean antMetro = lineaAnt < 100;
        boolean actMetro = lineaAct < 100;
        if (antMetro && actMetro) return R.string.transf_transbordo;
        if (!antMetro && !actMetro) return R.string.transf_correspondencia;
        return R.string.transf_conexion;
    }

    private View filaIndicacionRuta(Planificador.Instruccion ins) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout fila = new android.widget.LinearLayout(requireContext());
        fila.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fila.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fila.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        View barra = new View(requireContext());
        android.widget.LinearLayout.LayoutParams lpB =
                new android.widget.LinearLayout.LayoutParams(Math.round(3 * d), Math.round(15 * d));
        lpB.rightMargin = Math.round(7 * d);
        barra.setLayoutParams(lpB);
        barra.setBackgroundColor(ins.color);
        fila.addView(barra);

        String linea = (ins.ruta != null && !ins.ruta.isEmpty()) ? ins.ruta
                : getString(R.string.manifest_linea_fmt, Planificador.etiquetaLineaCortaPub(ins.linea));
        StringBuilder txt = new StringBuilder(linea);
        if (ins.terminal != null && !ins.terminal.isEmpty()) txt.append(" → ").append(ins.terminal);
        txt.append(" · ").append(ins.paradas).append(ins.paradas == 1 ? " estación" : " estaciones");

        TextView t = new TextView(requireContext());
        t.setText(txt.toString());
        t.setTextSize(13f);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                fila, com.google.android.material.R.attr.colorOnSurface));
        fila.addView(t);
        return fila;
    }

    private View filaTransferRuta(boolean camina, String texto) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout fila = new android.widget.LinearLayout(requireContext());
        fila.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fila.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fila.setPadding(Math.round(4 * d), 0, 0, 0);

        android.widget.ImageView ic = new android.widget.ImageView(requireContext());
        int is = Math.round(15 * d);
        android.widget.LinearLayout.LayoutParams lpI = new android.widget.LinearLayout.LayoutParams(is, is);
        lpI.leftMargin = Math.round(2 * d);
        lpI.rightMargin = Math.round(9 * d);
        ic.setLayoutParams(lpI);
        ic.setImageResource(camina ? R.drawable.ic_walk : R.drawable.ic_swap);
        ic.setColorFilter(0xFF757575);
        fila.addView(ic);

        TextView t = new TextView(requireContext());
        t.setText(texto);
        t.setTextSize(12f);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(0xFF757575);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.ITALIC);
        fila.addView(t);
        return fila;
    }

    private BitmapDescriptor bitmapEstacionRuta(String icono, int color) {
        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Iconos.pictograma(requireContext(), icono, px);
        if (bmp != null) return BitmapDescriptorFactory.fromBitmap(bmp);
        Bitmap dot = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dot);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF); c.drawCircle(px / 2f, px / 2f, px * 0.30f, p);
        p.setColor(color);      c.drawCircle(px / 2f, px / 2f, px * 0.22f, p);
        return BitmapDescriptorFactory.fromBitmap(dot);
    }

    private BitmapDescriptor bitmapEstacionTransbordoRuta(String icono, int colorArriba, int colorAbajo) {
        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Iconos.pictograma(requireContext(), icono, px);
        if (bmp != null)
            return BitmapDescriptorFactory.fromBitmap(Iconos.recoloreaFondoDiagonal(bmp, colorArriba, colorAbajo));
        return bitmapEstacionRuta(icono, colorArriba);
    }

    private int colorDeRuta(int lineaNum) {
        Linea l = GtfsRepository.porNumero(requireContext(), lineaNum);
        return l != null ? l.color : 0xFFFF9A03;
    }

    /** Atenúa (o restaura) las líneas base de TODA la red (las que ya están dibujadas desde que
     *  arranca la app, ver dibujarRed()/dibujarMixtaShape()/dibujarMexibus()) para que el trazo de
     *  una ruta activa resalte más -- sin tocar su color, solo el canal alfa. */
    private void aplicarOpacidadRed(boolean atenuada) {
        int alpha = atenuada ? 70 : 255;
        for (Polyline p : lineasRed) p.setColor(conAlpha(p.getColor(), alpha));
        for (Polyline p : mexibusLineas) p.setColor(conAlpha(p.getColor(), alpha));
    }

    private static int conAlpha(int colorArgb, int alpha) {
        return (alpha << 24) | (colorArgb & 0x00FFFFFF);
    }

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
     * intermedia; si el nombre coincide pero son estaciones FÍSICAS distintas (p. ej. "Las Américas"
     * en Mexibús L1 y L2, a ~1067 m -- ver Planificador.agruparPorEstacionFisica), antes se promediaban
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
        if (citi && lineaCiti != 0) RealtimeRepository.filtro.linea = null;
        lineaCiti = 0;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tickRecorrido);
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
