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
// y va intercambiando los FRAGMENTS (Mapa, Líneas, Ruta, Servicio [Llegadas +
// Reportar], Configuración) según lo que toque el usuario.
//
// PUNTOS CLAVE:
//   - bottomNav (BottomNavigationView) : barra inferior, ítems en res/menu/bottom_nav_menu.xml.
//   - EXTRA_ABRIR_RUTA : extra para abrir directo el planificador (p. ej. al
//     tocar la notificación de recorrido).
//   - EdgeToEdge : dibuja detrás de las barras del sistema (pantalla completa).
//
// Una "Activity" es una pantalla de Android; los "Fragments" son piezas
// intercambiables dentro de ella.
// ============================================================
public class MainActivity extends AppCompatActivity {

    /** Extra: abrir directamente el planificador (p.ej. al tocar la notificación de recorrido). */
    public static final String EXTRA_ABRIR_RUTA = "abrir_ruta";

    private int seleccionadoId = -1;
    private BottomNavigationView bottomNav;
    private AdView banner;
    private ConsentInformation consentInfo;
    private final AtomicBoolean adsInicializado = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Carga el catálogo de marca/modelo (assets/modelos.csv + Sheet) una vez.
        Modelos.init(getApplicationContext());
        // Carga el catálogo de rutas (route_id → línea, origen, destino).
        RutasRepository.init();
        // Reanuda la sincronización en segundo plano si el usuario la dejó activa.
        // Android 12+ puede negar el arranque del foreground service (p. ej. justo tras un
        // timeout del FGS anterior); sin este try-catch, ForegroundServiceStartNotAllowedException
        // tumbaba la app en CADA apertura (MainActivity.onCreate corre siempre al abrir).
        try {
            if (Modos.sincronizacionFondo(this)) SincronizacionService.iniciar(this);
        } catch (Exception ignore) {}
        // Vigila afectaciones del servicio (manifestaciones) cada minuto.
        try { ManifestacionesService.iniciar(this); } catch (Exception ignore) {}
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
        // NUNCA intentar ajustar el layout de esta Activity cuando aparece el teclado (ni con
        // android:windowSoftInputMode="adjustResize" ni con padding manual del inset ime()): se
        // probaron AMBAS variantes por separado y las DOS rompen el redibujado del SurfaceView de
        // Google Maps -- el mapa (que vive de fondo, a pantalla completa, detrás de las tarjetas
        // flotantes de Mapa/Planificador) se queda "congelado" en negro/en blanco cada vez que se
        // reduce el tamaño de su contenedor, sea por resize físico de ventana o por un simple
        // cambio de padding en el layout. Confirmado con capturas reales en ambos casos. Por eso
        // aquí solo se aplican las barras del sistema (edge-to-edge); el teclado puede tapar parte
        // del mapa mientras se escribe, y es preferible a que la pantalla se rompa.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
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
            int inicial = (getIntent() != null && getIntent().getBooleanExtra(EXTRA_ABRIR_RUTA, false))
                    ? R.id.nav_ruta : R.id.nav_mapa;
            // setSelectedItemId() no dispara el listener si ese ítem ya está seleccionado por
            // defecto (el primero del menú): seleccionar() de todos modos es idempotente.
            bottomNav.setSelectedItemId(inicial);
            seleccionar(inicial);
        }
        gestionarConsentimientoAds();
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
        if (intent != null && intent.getBooleanExtra(EXTRA_ABRIR_RUTA, false)) {
            bottomNav.setSelectedItemId(R.id.nav_ruta);
            seleccionar(R.id.nav_ruta);
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

    private Fragment fragmentDe(int id) {
        if (id == R.id.nav_lineas) return new LinesFragment();
        if (id == R.id.nav_ruta) return new PlanificadorFragment();
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

    /** Abre el planificador de ruta hacia una estación (con botón atrás), sin línea fija: si el
     *  nombre existe en varias líneas, el propio planificador pregunta a cuál te refieres. */
    public void mostrarPlanificador(String destino) { mostrarPlanificador(destino, 0); }

    /** Igual, pero con la línea YA conocida (p. ej. se tocó un marcador concreto en el mapa): no
     *  pregunta a cuál estación te refieres aunque el nombre exista en otra línea también. */
    public void mostrarPlanificador(String destino, int linea) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, PlanificadorFragment.nuevo(destino, linea))
                .addToBackStack("planificador")
                .commitAllowingStateLoss();
    }

    @Override
    protected void onDestroy() {
        if (banner != null) banner.destroy();   // libera los recursos del SDK de AdMob
        super.onDestroy();
    }
}
