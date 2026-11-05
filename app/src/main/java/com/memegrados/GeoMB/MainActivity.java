package com.memegrados.GeoMB;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

// ============================================================
// CLASE    : MainActivity   (extends AppCompatActivity)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// La pantalla PRINCIPAL de la app: hospeda la barra de navegación inferior
// y va intercambiando los FRAGMENTS (Mapa [con el planificador fusionado],
// Líneas, Servicio [Llegadas + Reportar], Configuración) según lo que toque
// el usuario.
//
// PUNTOS CLAVE:
//   - bottomNav (BottomNavigationView) : barra inferior, ítems en res/menu/bottom_nav_menu.xml.
//   - EXTRA_ABRIR_RUTA : extra para abrir directo el mapa (p. ej. al tocar la
//     notificación de recorrido) -- el planificador ya vive ahí, fusionado.
//   - EdgeToEdge : dibuja detrás de las barras del sistema (pantalla completa).
//
// Una "Activity" es una pantalla de Android; los "Fragments" son piezas
// intercambiables dentro de ella.
// ============================================================
public class MainActivity extends AppCompatActivity {

    /** Extra: abrir directamente el mapa, donde vive el planificador fusionado (p.ej. al tocar la
     *  notificación de recorrido). */
    public static final String EXTRA_ABRIR_RUTA = "abrir_ruta";

    /** Extra: enfocar esta unidad en el mapa (p. ej. al tocar la notificación FCM de
     *  "unidad_cerca", ver MensajesService) -- mismo mecanismo que ya usa "Mis unidades" para
     *  centrar el mapa en una unidad guardada (RealtimeRepository.unidadSeleccionada +
     *  MapFragment.aplicarSeleccionUnidad()), solo que disparado desde un Intent en vez de un
     *  toque en la lista. PENDIENTE (fuera de alcance de esta etapa, ver su informe): esa misma
     *  lógica existente nunca inventa una posición si la unidad ya no está en el feed, pero
     *  tampoco le avisa nada al usuario -- simplemente sigue esperando el próximo refresco en
     *  silencio. Un mensaje explícito de "unidad ya no disponible" requeriría tocar
     *  MapFragment.aplicarSeleccionUnidad(), que esta etapa decidió no ampliar. */
    public static final String EXTRA_ECONOMICO_FOCO = "economico_foco";

    /** Extras del aviso de exceso de velocidad durante un recorrido (ver RecorridoService.
     *  avisarExcesoVelocidad): al tocar la notificación o su acción "Reportar", se abre el cliente
     *  de correo prellenado vía ReporteIrregularidad -- el usuario revisa y decide si lo manda. */
    public static final String EXTRA_REPORTAR_VELOCIDAD = "reportar_velocidad";
    public static final String EXTRA_VEL_LINEA = "vel_linea";
    public static final String EXTRA_VEL_TERMINAL = "vel_terminal";
    public static final String EXTRA_VEL_LAT = "vel_lat";
    public static final String EXTRA_VEL_LON = "vel_lon";
    public static final String EXTRA_VEL_KMH = "vel_kmh";

    private int seleccionadoId = -1;
    private BottomNavigationView bottomNav;
    private AdView banner;
    private ConsentInformation consentInfo;
    private final AtomicBoolean adsInicializado = new AtomicBoolean(false);
    private static boolean avisoPausaMostrado = false;
    private androidx.appcompat.app.AlertDialog dialogActualizacion;   // ver mostrarBloqueoActualizacion()

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Interruptor remoto de "Servicio en pausa": primero el último estado guardado (para que
        // lo de abajo ya no arranque el sondeo si el servidor está apagado) y luego la consulta real.
        EstadoServicio.cargar(this);
        // Con el estilo CITI activo esta actividad cede la pantalla enseguida: el aviso lo da CitiActivity.
        if (EstadoServicio.enPausa() && !Modos.estiloCiti(this)) mostrarAvisoPausa();
        EstadoServicio.refrescar(this, () -> {
            if (EstadoServicio.enPausa() && !isFinishing()) {
                mostrarAvisoPausa();
                SincronizacionService.detener(this);
            }
        });
        // Actualización obligatoria (versión mínima fijada en /admin/version del backend): mismo
        // patrón que EstadoServicio/"pausa" de arriba, pero esta pantalla NO se puede cerrar.
        // Backend.alRequerirActualizacion cubre además el caso de que CUALQUIER llamada reciba
        // 426 entre un refrescar() y el siguiente (ver Backend.get()).
        ActualizacionObligatoria.cargar(this);
        if (ActualizacionObligatoria.requerida()) mostrarBloqueoActualizacion();
        ActualizacionObligatoria.refrescar(this, this::mostrarBloqueoActualizacion);
        Backend.alRequerirActualizacion = this::mostrarBloqueoActualizacion;
        AvisoDonativo.mostrarSiToca(this);

        // Carga el catálogo de marca/modelo (assets/modelos.csv + Sheet) una vez.
        Modelos.init(getApplicationContext());
        // Carga el catálogo de rutas (route_id → línea, origen, destino).
        RutasRepository.init();
        // Reanuda la sincronización en segundo plano si el usuario la dejó activa. iniciar() ya
        // envuelve su propio arranque del foreground service (Android 12+ puede negarlo).
        if (Modos.sincronizacionFondo(this)) SincronizacionService.iniciar(this);
        // Vigila afectaciones del servicio (manifestaciones) cada minuto. iniciar() ya se protege igual.
        ManifestacionesService.iniciar(this);
        // Suscribe a los temas de push (FCM) para recibir afectaciones y avisos de actualización.
        try {
            com.google.firebase.messaging.FirebaseMessaging fm =
                    com.google.firebase.messaging.FirebaseMessaging.getInstance();
            // Respeta lo que el usuario dejó en Acerca (switches de notificaciones).
            if (Modos.notifAfectaciones(this)) fm.subscribeToTopic("afectaciones");
            else fm.unsubscribeFromTopic("afectaciones");
            // Avisos de actualización: SIEMPRE activos (sin control en Acerca). Se fuerza para que
            // todos los reciban en la próxima versión, aunque antes los hubieran apagado.
            Modos.setNotifActualizaciones(this, true);
            fm.subscribeToTopic("actualizaciones");
            // Elevadores: solo si el perfil tiene movilidad reducida (tema aparte).
            if (Perfil.movilidadReducida(this)) fm.subscribeToTopic("elevadores");
            else fm.unsubscribeFromTopic("elevadores");
        } catch (Exception ignore) {}
        // Estilo CITI activo: lo de arriba (catálogos, servicios, avisos push) ya quedó listo, así que
        // CitiActivity hereda todo y aquí solo se le cede la pantalla.
        if (Modos.estiloCiti(this)) {
            startActivity(new Intent(this, CitiActivity.class));
            finish();
            return;
        }
        // NUNCA intentar ajustar el layout de esta Activity cuando aparece el teclado (ni con
        // android:windowSoftInputMode="adjustResize" ni con padding manual del inset ime()): se
        // probaron AMBAS variantes por separado y las DOS rompen el redibujado del SurfaceView de
        // Google Maps -- el mapa (que vive de fondo, a pantalla completa, detrás de las tarjetas
        // flotantes de Mapa/Planificador) se queda "congelado" en negro/en blanco cada vez que se
        // reduce el tamaño de su contenedor, sea por resize físico de ventana o por un simple
        // cambio de padding en el layout. Confirmado con capturas reales en ambos casos. Por eso
        // aquí solo se aplican las barras del sistema (edge-to-edge); el teclado puede tapar parte
        // del mapa mientras se escribe, y es preferible a que la pantalla se rompa.
        // El inset INFERIOR (barra de navegación/gestos) NO va aquí como padding del contenedor
        // raíz -- eso dejaba un hueco vacío debajo de bottom_nav (su fondo terminaba antes del
        // borde real de la pantalla, aunque ya combinara de color con windowBackground). Se mueve
        // a padding DENTRO de bottom_nav: su fondo (colorSurface) llega hasta el borde real, y el
        // contenido (íconos/etiquetas) queda despejado del área del sistema. fragment_container
        // (el mapa) está anclado a ad_banner, no al padre, así que no le afecta ninguno de los dos
        // cambios -- sigue sin tocarse su tamaño (ver el comentario de arriba sobre el teclado).
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNavInsets =
                findViewById(R.id.bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomNavInsets.setPadding(bottomNavInsets.getPaddingLeft(), bottomNavInsets.getPaddingTop(),
                    bottomNavInsets.getPaddingRight(), systemBars.bottom);
            return insets;
        });

        // Tipografía Tipo Metro en textos cortos de TODOS los módulos (fragments).
        // Los textos largos/descripciones se excluyen con android:tag="largo".
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@androidx.annotation.NonNull androidx.fragment.app.FragmentManager fm,
                                                      @androidx.annotation.NonNull Fragment f,
                                                      @androidx.annotation.NonNull android.view.View v,
                                                      android.os.Bundle s) {
                        Tipografia.aplicarArbol(v);
                        Traductor.traducirArbol(v);   // traducción automática si hay idioma objetivo
                    }
                }, true);
        Tipografia.aplicarArbol(findViewById(R.id.bottom_nav));   // etiquetas de pestañas
        Traductor.traducirArbol(findViewById(R.id.bottom_nav));

        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> { seleccionar(item.getItemId()); return true; });
        if (savedInstanceState == null) {
            manejarReporteVelocidad(getIntent());
            String ecoFoco = getIntent() != null ? getIntent().getStringExtra(EXTRA_ECONOMICO_FOCO) : null;
            int inicial;
            if (ecoFoco != null && !ecoFoco.isEmpty()) {
                RealtimeRepository.unidadSeleccionada = ecoFoco;
                inicial = R.id.nav_mapa;
            } else {
                // El planificador ya vive fusionado en el mapa general (ver MapFragment); no hay
                // pestaña aparte a la que mandar este extra.
                inicial = R.id.nav_mapa;
            }
            // setSelectedItemId() no dispara el listener si ese ítem ya está seleccionado por
            // defecto (el primero del menú): seleccionar() de todos modos es idempotente.
            bottomNav.setSelectedItemId(inicial);
            seleccionar(inicial);
        }
        gestionarConsentimientoAds();
    }

    /** Aviso de "Servicio en pausa", UNA vez por arranque de la app (no en cada rotación ni cada
     *  vez que se vuelve a esta pantalla). */
    private void mostrarAvisoPausa() {
        if (avisoPausaMostrado || isFinishing() || isDestroyed()) return;
        avisoPausaMostrado = true;
        try {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(EstadoServicio.titulo(this))
                    .setMessage(EstadoServicio.mensaje(this))
                    .setPositiveButton(R.string.pausa_entendido, null)
                    .show();
        } catch (Exception ignore) {}
    }

    /** Pantalla de "actualización obligatoria": a diferencia de mostrarAvisoPausa(), ESTA no se
     *  cierra sola. No cancelable (ni tocando fuera ni con el botón atrás del sistema) y su único
     *  botón NO cierra el diálogo -- solo abre la ficha en la tienda; si el usuario se regresa sin
     *  actualizar, sigue viendo este bloqueo. Se re-muestra en onResume() por si el diálogo se
     *  perdió al volver de la tienda (la Activity se recreó/el proceso se liberó en segundo plano). */
    private void mostrarBloqueoActualizacion() {
        if (isFinishing() || isDestroyed() || !ActualizacionObligatoria.requerida()) return;
        if (dialogActualizacion != null && dialogActualizacion.isShowing()) return;
        try {
            dialogActualizacion = new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.actualizacion_oblig_titulo)
                    .setMessage(R.string.actualizacion_oblig_mensaje)
                    .setCancelable(false)
                    .setPositiveButton(R.string.actualizacion_oblig_boton, null)
                    .show();
            // null arriba a propósito: el listener normal de setPositiveButton SIEMPRE cierra el
            // diálogo al volver; sobreescribiendo el botón YA mostrado se evita ese cierre.
            dialogActualizacion.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener(v -> abrirFichaTienda());
        } catch (Exception ignore) {}
    }

    /** Abre la ficha de GeoMB en Play Store (market://, con respaldo a la URL web si no hay
     *  tienda instalada) -- mismo intent que MensajesService.notificarActualizacion(). */
    private void abrirFichaTienda() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }

    /**
     * Antes de pedir anuncios, hay que saber si este usuario necesita dar su consentimiento
     * (GDPR en la UE/Reino Unido, o las leyes de privacidad de EE. UU.) -- lo resuelve el SDK de
     * UMP (User Messaging Platform) de Google, mostrando el mensaje correspondiente SOLO si aplica
     * y SOLO si hay uno configurado en AdMob (Privacidad y mensajes). Si el usuario no está en una
     * región regulada, o ya dio/negó su consentimiento en una sesión anterior, no muestra nada.
     */
    private void gestionarConsentimientoAds() {
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consentInfo = UserMessagingPlatform.getConsentInformation(this);
        // Los 3 callbacks de UMP corren asíncronos (fuera de cualquier try/catch de este método);
        // inicializarAds() ya está blindada, pero canRequestAds()/loadAndShowConsentFormIfRequired
        // en sí también podrían tronar, así que cada callback se protege por su cuenta.
        consentInfo.requestConsentInfoUpdate(this, params,
                () -> {
                    try {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(MainActivity.this, formError -> {
                            // formError != null: no se pudo cargar/mostrar el formulario (sin red, etc.);
                            // igual se checa canRequestAds() por si ya había consentimiento de antes.
                            try { if (consentInfo.canRequestAds()) inicializarAds(); } catch (Exception ignore) {}
                        });
                    } catch (Exception ignore) {}
                },
                requestError -> { try { if (consentInfo.canRequestAds()) inicializarAds(); } catch (Exception ignore) {} });
        // Mientras se actualiza la info de consentimiento (llamada async de arriba), si YA se puede
        // pedir anuncios (consentimiento obtenido en una sesión previa) no hace falta esperar:
        // se inicializa en paralelo. inicializarAds() está protegido contra doble ejecución.
        if (consentInfo.canRequestAds()) inicializarAds();
    }

    /** Inicializa el SDK de anuncios y carga el banner. Protegido con AtomicBoolean porque
     *  gestionarConsentimientoAds() puede llamarlo desde dos caminos (el chequeo inmediato y el
     *  callback async) y esto NUNCA debe correr dos veces.
     *
     *  El try-catch de cargarBanner() va DENTRO del callback, no envolviendo initialize(): el SDK
     *  entrega ese callback en un Handler.post() posterior (confirmado en el stack trace de los
     *  crashes anteriores), así que un try-catch de afuera ya no está "activo" para cuando corre.
     *  Esto blinda contra cualquier comportamiento inesperado el día que AdMob apruebe la cuenta y
     *  empiece a servir anuncios reales de producción, no solo los errores ya conocidos. */
    private void inicializarAds() {
        if (!adsInicializado.compareAndSet(false, true)) return;
        try {
            MobileAds.initialize(this, initStatus -> {
                try { cargarBanner(); } catch (Exception ignore) {}
            });
        } catch (Exception ignore) {}
    }

    /** Banner de AdMob no invasivo, fijo abajo en todas las pantallas (ver Config.AD_BANNER_UNIT_ID):
     *  ayuda a cubrir el costo de rss.app. Arranca oculto (activity_main.xml) y solo se muestra si
     *  el anuncio realmente carga, para nunca dejar un hueco en blanco sin red o sin relleno. */
    private void cargarBanner() {
        banner = findViewById(R.id.ad_banner);
        // adSize/adUnitId van en el XML (activity_main.xml): fijarlos aquí también revienta con
        // "The ad size/ad unit ID can only be set once on AdView" (AdMob no permite fijarlos 2 veces).
        banner.setAdListener(new AdListener() {
            @Override public void onAdLoaded() { banner.setVisibility(View.VISIBLE); }
            @Override public void onAdFailedToLoad(@androidx.annotation.NonNull LoadAdError error) {
                banner.setVisibility(View.GONE);
            }
        });
        try { banner.loadAd(new AdRequest.Builder().build()); } catch (Exception ignore) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_REPORTAR_VELOCIDAD, false)) {
            manejarReporteVelocidad(intent);
            bottomNav.setSelectedItemId(R.id.nav_mapa);
            seleccionar(R.id.nav_mapa);
            return;
        }
        String ecoFoco = intent.getStringExtra(EXTRA_ECONOMICO_FOCO);
        if (ecoFoco != null && !ecoFoco.isEmpty()) {
            RealtimeRepository.unidadSeleccionada = ecoFoco;
            bottomNav.setSelectedItemId(R.id.nav_mapa);
            seleccionar(R.id.nav_mapa);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_ABRIR_RUTA, false)) {
            // El planificador ya vive fusionado en el mapa general (ver MapFragment); no hay
            // pestaña aparte a la que mandar este extra.
            bottomNav.setSelectedItemId(R.id.nav_mapa);
            seleccionar(R.id.nav_mapa);
        }
    }

    /** Carga el fragmento de la pestaña. El resaltado del ítem activo/inactivo ya lo maneja
     *  BottomNavigationView solo (app:itemIconTint/itemTextColor con bottom_nav_color).
     *  commitAllowingStateLoss(): un listener o notificación puede llamar a esto justo cuando la
     *  Activity ya guardó su estado (p. ej. al volver de segundo plano); commit() normal lanza
     *  IllegalStateException en ese caso. Perder esta transacción puntual es inofensivo. */
    private void seleccionar(int id) {
        if (id == seleccionadoId) return;
        seleccionadoId = id;
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragmentDe(id))
                .commitAllowingStateLoss();
    }

    /** Si el Intent trae el aviso de exceso de velocidad (ver RecorridoService.
     *  avisarExcesoVelocidad), abre el cliente de correo prellenado con ReporteIrregularidad -- el
     *  usuario revisa y decide si lo manda, igual que cualquier otro reporte de la app. */
    private void manejarReporteVelocidad(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_REPORTAR_VELOCIDAD, false)) return;
        int linea = intent.getIntExtra(EXTRA_VEL_LINEA, 0);
        String terminal = intent.getStringExtra(EXTRA_VEL_TERMINAL);
        float kmh = intent.getFloatExtra(EXTRA_VEL_KMH, 0f);
        com.google.android.gms.maps.model.LatLng pos = intent.hasExtra(EXTRA_VEL_LAT)
                ? new com.google.android.gms.maps.model.LatLng(
                        intent.getDoubleExtra(EXTRA_VEL_LAT, 0), intent.getDoubleExtra(EXTRA_VEL_LON, 0))
                : null;
        ReporteIrregularidad.enviar(this, 0L, linea, null, pos, null, terminal,
                getString(R.string.velocidad_tipo_reporte),
                getString(R.string.velocidad_descripcion_reporte, Math.round(kmh)),
                null, null, null, null, null, null);
    }

    private Fragment fragmentDe(int id) {
        if (id == R.id.nav_lineas) return new LinesFragment();
        if (id == R.id.nav_llegadas) return new ServicioFragment();
        if (id == R.id.nav_acerca) return new ConfiguracionFragment();
        return new MapFragment();
    }

    /** Cambia de pestaña desde otros fragments. */
    public void navegarA(int itemId) {
        bottomNav.setSelectedItemId(itemId);
        seleccionar(itemId);   // por si ese ítem ya estaba seleccionado (el listener no dispara)
    }

    /** Abre la pantalla de rutas por código (con botón atrás). */
    public void mostrarRutas() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new RutasFragment())
                .addToBackStack("rutas")
                .commitAllowingStateLoss();
    }

    /** Abre el listado de estaciones de una línea (con botón atrás). */
    public void mostrarEstaciones(int linea) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, EstacionesLineaFragment.nueva(linea))
                .addToBackStack("estaciones")
                .commitAllowingStateLoss();
    }

    /** Abre el listado de unidades de una ruta concreta (con botón atrás). */
    public void mostrarUnidadesRuta(int linea, int codigo, String recorrido) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, UnidadesFragment.nuevaRuta(linea, codigo, recorrido))
                .addToBackStack("unidades_ruta")
                .commitAllowingStateLoss();
    }

    /** Abre el listado de unidades de una línea (con botón atrás). */
    public void mostrarUnidades(int linea) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, UnidadesFragment.nueva(linea))
                .addToBackStack("unidades")
                .commitAllowingStateLoss();
    }

    /** Abre "Mis unidades" (unidades guardadas + alertas de proximidad, con botón atrás). */
    public void mostrarUnidadesGuardadas() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new UnidadesGuardadasFragment())
                .addToBackStack("unidades_guardadas")
                .commitAllowingStateLoss();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Por si el usuario se regresó de la tienda sin actualizar y el diálogo se perdió (la
        // Activity se recreó, o Android liberó el proceso en segundo plano): se vuelve a mostrar.
        if (ActualizacionObligatoria.requerida()) mostrarBloqueoActualizacion();
    }

    @Override
    protected void onDestroy() {
        if (banner != null) banner.destroy();   // libera los recursos del SDK de AdMob
        Backend.alRequerirActualizacion = null;   // no dejar colgada esta referencia a la Activity
        super.onDestroy();
    }
}
