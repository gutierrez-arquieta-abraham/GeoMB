package com.memegrados.GeoMB;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Estilo CITI: réplica de la app oficial del Metrobús de Indra (2018) que reutiliza todo GeoMB
 * (datos, tiempo real, mapa, afectaciones, voz). Ver docs/MODO_CITI_referencia.md.
 */
// ============================================================
// CLASE    : CitiActivity
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Como ActivityMain de la app original: franja de logos fija arriba y,
// debajo, una pila de pantallas (fragments) que empieza en el menú de
// inicio de 8 botones. "Menú" en cualquier pantalla regresa al inicio.
//
// Encima de las pantallas hay una capa para los menús (estación / ruta)
// y aquí viven también los diálogos del original (aviso con "Aceptar",
// círculo de descarga, "Mi trayecto").
//
// Hereda las funciones de GeoMB: MainActivity hace toda la inicialización
// (servicios, FCM, catálogos) y después redirige aquí mientras el modo esté
// activo; el mapa es el MapFragment de siempre, dibujado en estilo CITI.
// ============================================================
public class CitiActivity extends AppCompatActivity {

    /** Una opción de menú: texto + acción. */
    public static final class Opcion {
        final String texto; final Runnable accion;
        public Opcion(String texto, Runnable accion) { this.texto = texto; this.accion = accion; }
    }

    // Lo que el mapa debe hacer al abrirse desde otra pantalla (lo consume MapFragment una vez).
    static int rutaPendiente = 0;          // "Ver en mapa" de una ruta
    static String busPendiente = null;     // "Seguir mi autobús": económico a seguir
    static boolean trayectoPendiente = false;   // "Mi trayecto" → "Ver en mapa"
    /** Último trayecto trazado en "Mi trayecto" (planificador de GeoMB); sobrevive al cambiar de pantalla. */
    static Planificador.Ruta trayecto = null;

    private LinearLayout capa;
    private boolean capaCentrada, cerrandoCapa;
    private Dialog descargando;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_citi);
        capa = findViewById(R.id.citi_capa);
        // "Servicio en pausa" (ver EstadoServicio): el aviso que daría MainActivity, con el diálogo CITI.
        EstadoServicio.cargar(this);
        if (savedInstanceState == null && EstadoServicio.enPausa()) aviso(EstadoServicio.mensaje(this));
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.citi_pantallas, new CitiInicioFragment())
                    .commit();
        }
        // Aviso de exceso de velocidad del recorrido (llega reenviado desde MainActivity).
        ReporteIrregularidad.desdeAvisoVelocidad(this, getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (capaVisible()) { cerrarMenu(); return; }
                FragmentManager fm = getSupportFragmentManager();
                if (fm.getBackStackEntryCount() > 0) fm.popBackStack();
                else finish();   // en el inicio, "atrás" sale de la app como en el original
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ReporteIrregularidad.desdeAvisoVelocidad(this, intent);
    }

    @Override
    protected void onDestroy() {
        mostrarDescargando(false);
        super.onDestroy();
    }

    // --- Navegación ---

    /** Abre una pantalla encima de la actual (con "atrás"). */
    public void abrir(Fragment f) {
        cerrarMenuSinAnimar();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.citi_pantallas, f)
                .addToBackStack(null)
                .commitAllowingStateLoss();
    }

    /** "Menú": vuelve al inicio (muestraMenu() del original, que vaciaba la pila). */
    public void irInicio() {
        cerrarMenuSinAnimar();
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** Abre el mapa; si ya está debajo en la pila, regresa a él en lugar de crear otro. */
    public void abrirMapa() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment actual = fm.findFragmentById(R.id.citi_pantallas);
        if (actual instanceof MapFragment) { ((MapFragment) actual).aplicarPendientesCiti(); return; }
        irInicio();
        fm.executePendingTransactions();
        abrir(new MapFragment());
    }

    /** Muestra en el mapa el trayecto trazado en "Mi trayecto" (y lo sigue si hay recorrido). */
    public void verTrayectoEnMapa() {
        if (trayecto == null) return;
        trayectoPendiente = true;
        abrirMapa();
    }

    public void verRutaEnMapa(int linea) {
        rutaPendiente = linea;
        abrirMapa();
    }

    public void verEstacionEnMapa(CitiDatos.Est e) {
        RealtimeRepository.estacionSeleccionadaPos = e.pos;
        RealtimeRepository.estacionSeleccionadaLinea = e.linea;
        abrirMapa();
    }

    /** Sale del estilo CITI y vuelve a la app normal. */
    public void salirDelEstilo() {
        Modos.setEstiloCiti(this, false);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // --- Menús de estación y de ruta ---

    /** Menú de estación tocada en el mapa (fragment_menu_parada_mapa.xml): abajo, sin oscurecer y
     *  deslizándose. */
    public void menuEstacionMapa(CitiDatos.Est e) {
        List<Opcion> op = new ArrayList<>();
        op.add(new Opcion(getString(R.string.citi_ver_tiempos), () -> { cerrarMenuSinAnimar(); abrir(CitiTiemposFragment.de(e)); }));
        op.add(new Opcion(getString(R.string.citi_ver_rutas), () -> { cerrarMenuSinAnimar(); abrirRutasDe(e); }));
        // Planificador de GeoMB: trayecto del usuario hasta esta estación.
        op.add(new Opcion(getString(R.string.citi_como_llegar), () -> { cerrarMenuSinAnimar(); abrir(CitiTrayectoFragment.hacia(e)); }));
        if (!EstiloCiti.esFavorita(this, e.linea, e.nombre, e.pos))
            op.add(new Opcion(getString(R.string.citi_anadir_favoritos), () -> { agregarFavorita(e); cerrarMenu(); }));
        abrirMenu(op, false);
    }

    /** Menú de estación elegida en una lista (fragment_menu_parada.xml): centrado y oscurecido. */
    public void menuEstacionLista(CitiDatos.Est e) {
        List<Opcion> op = new ArrayList<>();
        op.add(new Opcion(getString(R.string.citi_ver_tiempos), () -> { cerrarMenuSinAnimar(); abrir(CitiTiemposFragment.de(e)); }));
        op.add(new Opcion(getString(R.string.citi_ver_rutas), () -> { cerrarMenuSinAnimar(); abrirRutasDe(e); }));
        op.add(new Opcion(getString(R.string.citi_ver_en_mapa), () -> { cerrarMenuSinAnimar(); verEstacionEnMapa(e); }));
        op.add(new Opcion(getString(R.string.citi_como_llegar), () -> { cerrarMenuSinAnimar(); abrir(CitiTrayectoFragment.hacia(e)); }));
        abrirMenu(op, true);
    }

    /** Menú de ruta (fragment_menu_linea.xml, sin "Horario"): centrado y oscurecido. */
    public void menuRuta(int linea) {
        List<Opcion> op = new ArrayList<>();
        op.add(new Opcion(getString(R.string.citi_ver_en_mapa), () -> { cerrarMenuSinAnimar(); verRutaEnMapa(linea); }));
        abrirMenu(op, true);
    }

    /** "Ver rutas" de una estación: la lista "Seleccione ruta" con las rutas que paran ahí. */
    public void abrirRutasDe(CitiDatos.Est e) {
        abrir(CitiListaFragment.rutas(new ArrayList<>(CitiDatos.lineasDe(this, e).keySet())));
    }

    public void agregarFavorita(CitiDatos.Est e) {
        EstiloCiti.agregarFavorita(this, e.linea, e.nombre, e.pos);
        android.widget.Toast.makeText(this, getString(R.string.citi_favorito_agregado, e.nombre),
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Llena la capa con {@code opciones} + "Cancelar". {@code centrado}: menú en medio sobre
     *  #88000000 y sin animación; si no, abajo, transparente y subiendo desde la mitad
     *  (slide_in_bottom, config_mediumAnimTime). Tocar fuera de los botones cancela. */
    public void abrirMenu(List<Opcion> opciones, boolean centrado) {
        capa.removeAllViews();
        List<Opcion> op = new ArrayList<>(opciones);
        op.add(new Opcion(getString(R.string.citi_cancelar), this::cerrarMenu));
        for (Opcion o : op) {
            TextView b = (TextView) getLayoutInflater().inflate(R.layout.boton_citi_menu, capa, false);
            b.setText(o.texto);
            b.setOnClickListener(v -> o.accion.run());
            capa.addView(b);
        }
        capa.setOnClickListener(v -> cerrarMenu());
        capaCentrada = centrado;
        cerrandoCapa = false;
        capa.setClickable(true);
        capa.setGravity(centrado ? Gravity.CENTER : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        capa.setBackgroundColor(centrado ? ContextCompat.getColor(this, R.color.citi_capa_oscura) : Color.TRANSPARENT);
        capa.animate().cancel();
        capa.setVisibility(View.VISIBLE);
        if (centrado) {
            capa.setTranslationY(0f);
        } else {
            capa.setTranslationY(distanciaMenu());
            capa.animate().translationY(0f).setDuration(duracion()).setListener(null).start();
        }
    }

    public boolean capaVisible() {
        return capa.getVisibility() == View.VISIBLE && !cerrandoCapa;
    }

    /** Cierra el menú (slide_out_bottom si era el de abajo; el centrado se quitaba sin animar). */
    public void cerrarMenu() {
        if (capa.getVisibility() != View.VISIBLE || cerrandoCapa) return;
        if (capaCentrada) { cerrarMenuSinAnimar(); return; }
        cerrandoCapa = true;
        capa.setClickable(false);
        capa.animate().translationY(distanciaMenu()).setDuration(duracion())
                .withEndAction(this::cerrarMenuSinAnimar).start();
    }

    private void cerrarMenuSinAnimar() {
        capa.animate().cancel();
        capa.setVisibility(View.GONE);
        capa.setTranslationY(0f);
        cerrandoCapa = false;
    }

    private float distanciaMenu() {
        View contenido = findViewById(R.id.citi_contenido);
        return contenido.getHeight() * 0.5f;
    }

    private long duracion() {
        return getResources().getInteger(android.R.integer.config_mediumAnimTime);
    }

    // --- Diálogos del original ---

    /** DialogInfo (showError): texto 16sp y "Aceptar" sobre caja blanca. */
    public void aviso(String texto) {
        aviso(null, texto);
    }

    /** Con {@code titulo} (p. ej. "Aviso" para las afectaciones), en negrita arriba del texto. */
    public void aviso(String titulo, String texto) {
        if (isFinishing()) return;
        Dialog d = nuevoDialogo();
        View v = getLayoutInflater().inflate(R.layout.dialogo_citi, null, false);
        if (titulo != null) {
            TextView t = v.findViewById(R.id.citi_dialogo_titulo);
            t.setText(titulo);
            t.setVisibility(View.VISIBLE);
        }
        ((TextView) v.findViewById(R.id.citi_dialogo_texto)).setText(texto);
        v.findViewById(R.id.citi_dialogo_aceptar).setOnClickListener(x -> d.dismiss());
        d.setContentView(v);
        try { d.show(); } catch (Exception ignore) {}
    }

    /** dialogDownloading: el círculo de progreso de 60dp, sin fondo y sin poder cancelarse. */
    public void mostrarDescargando(boolean mostrar) {
        if (!mostrar) {
            if (descargando != null) { try { descargando.dismiss(); } catch (Exception ignore) {} descargando = null; }
            return;
        }
        if (descargando != null || isFinishing()) return;
        Dialog d = nuevoDialogo();
        int px = (int) (60 * getResources().getDisplayMetrics().density);
        d.setContentView(new ProgressBar(this), new ViewGroup.LayoutParams(px, px));
        d.setCancelable(false);
        descargando = d;
        try { d.show(); } catch (Exception ignore) { descargando = null; }
    }

    /** "Mi trayecto" (DialogSelecBus): pide el número económico y sigue esa unidad en el mapa. */
    public void dialogoMiTrayecto() {
        Dialog d = nuevoDialogo();
        View v = getLayoutInflater().inflate(R.layout.dialogo_bus_citi, null, false);
        EditText codigo = v.findViewById(R.id.citi_bus_codigo);
        v.findViewById(R.id.citi_bus_cancelar).setOnClickListener(x -> d.dismiss());
        v.findViewById(R.id.citi_bus_aceptar).setOnClickListener(x -> {
            String eco = codigo.getText().toString().trim();
            d.dismiss();
            if (!eco.isEmpty()) buscarBus(eco);
        });
        d.setContentView(v);
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        try { d.show(); } catch (Exception ignore) {}
    }

    private void buscarBus(String eco) {
        mostrarDescargando(true);
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> unidades) {
                mostrarDescargando(false);
                UnidadReal u = RealtimeRepository.get().buscar(eco);
                if (u == null) { aviso(getString(R.string.citi_bus_no_encontrado)); return; }
                busPendiente = u.numero;
                abrirMapa();
            }
            @Override public void onError(String mensaje) {
                mostrarDescargando(false);
                aviso(getString(R.string.citi_error_conexion));
            }
        });
    }

    private Dialog nuevoDialogo() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        return d;
    }
}
