package com.memegrados.GeoMB;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

// ============================================================
// CLASE    : MainActivity   (extends AppCompatActivity)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// La pantalla PRINCIPAL de la app: hospeda la barra de navegación inferior
// y va intercambiando los FRAGMENTS (Mapa, Buscar, Líneas, Ruta, Llegadas,
// Reporte, Acerca de) según lo que toque el usuario.
//
// PUNTOS CLAVE:
//   - NAV_IDS : los ítems del menú inferior.
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

    private static final int[] NAV_IDS = {
            R.id.nav_mapa, R.id.nav_buscar, R.id.nav_lineas,
            R.id.nav_ruta, R.id.nav_llegadas, R.id.nav_reporte, R.id.nav_acerca
    };
    private int seleccionadoId = -1;

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

        for (int navId : NAV_IDS) {
            findViewById(navId).setOnClickListener(v -> seleccionar(v.getId()));
        }
        // Personalización por perfil: el buscador de unidades históricas se oculta
        // para usuarios Normal y Aficionado (el resto de pestañas queda igual).
        findViewById(R.id.nav_buscar).setVisibility(
                Perfil.muestraBuscador(this) ? android.view.View.VISIBLE : android.view.View.GONE);
        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_ABRIR_RUTA, false)) {
                seleccionar(R.id.nav_ruta);
            } else {
                seleccionar(R.id.nav_mapa);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_ABRIR_RUTA, false)) {
            seleccionar(R.id.nav_ruta);
        }
    }

    /** Carga el fragmento de la pestaña y actualiza el resaltado de la barra.
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
        pintarBarra(id);
    }

    private Fragment fragmentDe(int id) {
        if (id == R.id.nav_buscar) return new SearchFragment();
        if (id == R.id.nav_lineas) return new LinesFragment();
        if (id == R.id.nav_ruta) return new PlanificadorFragment();
        if (id == R.id.nav_llegadas) return new LlegadasFragment();
        if (id == R.id.nav_reporte) return new ReporteFragment();
        if (id == R.id.nav_acerca) return new AcercaFragment();
        return new MapFragment();
    }

    /** Resalta el ítem activo (rojo) y apaga los demás (gris). */
    private void pintarBarra(int id) {
        int activo = ContextCompat.getColor(this, R.color.mb_red);
        int inactivo = ContextCompat.getColor(this, R.color.mb_gray);
        for (int navId : NAV_IDS) {
            android.view.ViewGroup item = findViewById(navId);
            int color = (navId == id) ? activo : inactivo;
            ((android.widget.ImageView) item.getChildAt(0)).setColorFilter(color);
            ((android.widget.TextView) item.getChildAt(1)).setTextColor(color);
        }
    }

    /** Cambia de pestaña desde otros fragments. */
    public void navegarA(int itemId) {
        seleccionar(itemId);
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

    /** Abre el planificador de ruta hacia una estación (con botón atrás). */
    public void mostrarPlanificador(String destino) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, PlanificadorFragment.nuevo(destino))
                .addToBackStack("planificador")
                .commitAllowingStateLoss();
    }
}
