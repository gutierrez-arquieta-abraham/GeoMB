package com.memegrados.GeoMB;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pantalla de chat con el asistente conversacional (Gemini + Function Calling, backend Python
 * en integrations/gemini/, ver {@link Asistente} y {@link Config#ASISTENTE_CHAT_URL}). Sin
 * MapView de por medio, así que a diferencia de MainActivity (ver su comentario "NUNCA") aquí
 * SÍ es seguro usar adjustResize para el teclado (ver AndroidManifest).
 */
public class ChatAsistenteActivity extends AppCompatActivity {

    /** Lista blanca de acciones que esta pantalla sabe ejecutar: "detenerRecorrido" (Fase 3B) y,
     *  desde esta fase, "seguirUnidad"/"dejarDeSeguirUnidad" (seguimiento de proximidad, ver
     *  SeguimientoService). Cualquier otro nombre que mande el backend se ignora SIN mostrar
     *  diálogo (ver {@link #onAccionPendiente}): Android decide si una acción es conocida y
     *  permitida, nunca confía ciegamente en lo que propone Gemini. */
    private static final Set<String> ACCIONES_PERMITIDAS = new HashSet<>(Arrays.asList(
            "detenerRecorrido", "seguirUnidad", "dejarDeSeguirUnidad"));

    private EditText inMensaje;
    private MaterialButton btnEnviar;
    private ProgressBar progreso;
    private TextView txtAviso;
    private MensajeChatAdapter adapter;
    private RecyclerView rv;
    private boolean cuotaAgotada = false;

    /** Acción propuesta por Gemini pendiente de que el usuario la confirme con el botón del
     *  AlertDialog -- vive SOLO en memoria de esta Activity (nunca en SharedPreferences/Room),
     *  ver {@link AccionPendiente}. Un mensaje de chat como "sí" NUNCA la ejecuta: la ejecución
     *  real solo puede salir de {@link #confirmarAccion()}. */
    private AccionPendiente accionActual;

    /** Económico pendiente mientras se resuelve el flujo de permisos de "seguirUnidad" -- igual
     *  criterio que {@link #accionActual}: vive solo en memoria de esta instancia, nunca se
     *  persiste. Null cuando no hay ningún "seguirUnidad" en curso esperando un permiso. */
    private String economicoPendienteSeguimiento;

    /** Mismo flujo que MapFragment.permisoUbicacionCarta: sin ACCESS_FINE_LOCATION,
     *  SeguimientoService no puede medir distancias (ver su tienePermisoUbicacion()), así que
     *  aquí SÍ se bloquea la acción si el usuario la niega -- nunca se arranca el seguimiento
     *  sin este permiso. */
    private final ActivityResultLauncher<String> permisoUbicacionAsistente =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) {
                    continuarSeguirUnidadTrasPermisoUbicacion();
                } else {
                    economicoPendienteSeguimiento = null;
                    Toast.makeText(this, R.string.seguir_permiso_ubicacion, Toast.LENGTH_LONG).show();
                }
            });

    /** Mismo criterio que MapFragment.permisoNotifCarta: las notificaciones son de mejor
     *  esfuerzo (solo afectan las alertas de proximidad, ver SeguimientoService.lanzarAlerta()),
     *  así que el seguimiento arranca con el resultado que sea -- nunca se reinventa aquí una
     *  política de permisos más estricta que la que ya usa el resto de la app. */
    private final ActivityResultLauncher<String> permisoNotifAsistente =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    ok -> arrancarSeguimientoAsistente());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat_asistente);

        Tipografia.aplicar((TextView) findViewById(R.id.txt_asistente_titulo));
        findViewById(R.id.btn_asistente_atras).setOnClickListener(v -> finish());

        rv = findViewById(R.id.rv_asistente_mensajes);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MensajeChatAdapter();
        rv.setAdapter(adapter);

        inMensaje = findViewById(R.id.in_asistente_mensaje);
        btnEnviar = findViewById(R.id.btn_asistente_enviar);
        progreso = findViewById(R.id.prog_asistente);
        txtAviso = findViewById(R.id.txt_asistente_aviso);

        btnEnviar.setOnClickListener(v -> enviarDesdeInput());
        inMensaje.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                enviarDesdeInput();
                return true;
            }
            return false;
        });

        adapter.agregar(getString(R.string.asistente_saludo), false);
        aplicarInsets();
    }

    private void aplicarInsets() {
        View raiz = findViewById(R.id.asistente_root);
        View header = findViewById(R.id.asistente_header);
        int headerPadTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            header.setPadding(header.getPaddingLeft(), headerPadTop + sb.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            v.setPadding(sb.left, 0, sb.right, Math.max(sb.bottom, ime.bottom));
            return insets;
        });
    }

    private void enviarDesdeInput() {
        String mensaje = inMensaje.getText().toString().trim();
        if (TextUtils.isEmpty(mensaje) || cuotaAgotada) return;

        adapter.agregar(mensaje, true);
        rv.scrollToPosition(adapter.cantidad() - 1);
        inMensaje.setText("");
        fijarCargando(true);
        txtAviso.setVisibility(View.GONE);

        // Contexto de tracking (recorrido/línea/estación/unidades) para que el asistente pueda
        // responder preguntas como "¿cuál es mi estación actual?" -- reusa DiagnosticoReporte, que
        // ya sabe leer RecorridoService/SeguimientoService/RealtimeRepository sin coordenadas ni
        // identificadores de dispositivo (ver su javadoc). No se duplica esa lógica aquí.
        Asistente.enviar(this, mensaje, false, DiagnosticoReporte.contextoTracking(this), new Asistente.Callback() {
            @Override
            public void onRespuesta(String texto) {
                fijarCargando(false);
                adapter.agregar(TextUtils.isEmpty(texto)
                        ? getString(R.string.asistente_error_red) : texto, false);
                rv.scrollToPosition(adapter.cantidad() - 1);
            }

            @Override
            public void onAccionPendiente(JSONObject accionPendiente) {
                String accion = accionPendiente.optString("accion", null);
                if (accion == null || !ACCIONES_PERMITIDAS.contains(accion)) return;   // desconocida: SIN diálogo
                String resumen = accionPendiente.optString("resumen", accion);
                mostrarConfirmacion(new AccionPendiente(accion, accionPendiente.optJSONObject("parametros"), resumen));
            }

            @Override
            public void onCuotaAgotada(String mensaje) {
                fijarCargando(false);
                cuotaAgotada = true;
                txtAviso.setText(TextUtils.isEmpty(mensaje)
                        ? getString(R.string.asistente_cuota_agotada) : mensaje);
                txtAviso.setVisibility(View.VISIBLE);
                inMensaje.setEnabled(false);
                btnEnviar.setEnabled(false);
            }

            @Override
            public void onError(String mensaje) {
                fijarCargando(false);
                Toast.makeText(ChatAsistenteActivity.this,
                        R.string.asistente_error_red, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fijarCargando(boolean cargando) {
        progreso.setVisibility(cargando ? View.VISIBLE : View.GONE);
        btnEnviar.setEnabled(!cargando);
        inMensaje.setEnabled(!cargando);
    }

    // ---- confirmación de acciones (ver ACCIONES_PERMITIDAS) ----

    /** ¿Esta instancia de la Activity sigue en un estado válido para mostrar un diálogo? La
     *  respuesta de Asistente es asíncrona (hasta 30 s de espera, ciclo de Function Calling con
     *  varias vueltas) y puede llegar después de que el usuario haya rotado la pantalla, salido de
     *  esta pantalla, bloqueado el dispositivo, o la Activity ya se haya destruido/esté
     *  finalizando -- el callback sigue referenciando ESTA instancia concreta (puede ser una
     *  instancia ya vieja tras una rotación), nunca a una nueva. Comprobarlo ANTES de construir el
     *  AlertDialog evita que ocurra WindowManager.BadTokenException en vez de solo atraparla
     *  después de que ya fue un problema. minSdk 24, así que isDestroyed() siempre existe (API 17+). */
    private boolean puedeMostrarDialogo() {
        if (isFinishing() || isDestroyed()) return false;
        android.view.Window w = getWindow();
        android.view.View decor = w != null ? w.getDecorView() : null;
        return decor != null && decor.isAttachedToWindow();
    }

    /** Registra (sin token, sin deviceId, sin contenido de la conversación -- solo el nombre de la
     *  acción, que ya es un literal conocido como "detenerRecorrido", y el estado de la Activity)
     *  que una propuesta de acción se descartó por el estado de la Activity. Reutiliza Telemetria
     *  tal cual existe hoy (sin nuevo nivel de log): es información de diagnóstico sobre un fallo
     *  real -- antes quedaba invisible, atrapada en el catch genérico de Asistente.java. */
    private void registrarDescartePorLifecycle(String accion) {
        Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ChatAsistenteActivity.mostrarConfirmacion",
                "accion=" + accion + " descartada: finishing=" + isFinishing() + " destroyed=" + isDestroyed());
    }

    /** Punto de entrada desde {@link #onAccionPendiente}. Lifecycle-safe: si esta instancia ya no
     *  puede mostrar UI, la propuesta se descarta de forma segura -- NUNCA se ejecuta
     *  automáticamente y NUNCA se intenta abrir un diálogo sobre una ventana inválida. La
     *  arquitectura actual no persiste {@code accionActual} (ver {@link AccionPendiente}: vive solo
     *  en memoria de esta instancia, por diseño), así que no hay forma de que una instancia nueva
     *  la recupere -- perderla aquí es el comportamiento correcto, no un bug a "arreglar" guardando
     *  estado en otro lado. */
    private void mostrarConfirmacion(AccionPendiente a) {
        if (!puedeMostrarDialogo()) {
            registrarDescartePorLifecycle(a.accion);
            return;   // SIN ejecutar nada, SIN mostrar nada: descarte seguro
        }
        accionActual = a;
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.asistente_accion_titulo)
                    .setMessage(TextUtils.isEmpty(a.resumen) ? a.accion : a.resumen)
                    .setPositiveButton(R.string.asistente_accion_confirmar, (d, w) -> confirmarAccion())
                    .setNegativeButton(R.string.asistente_accion_cancelar, (d, w) -> cancelarAccion())
                    .setOnCancelListener(d -> cancelarAccion())   // back / tocar fuera del diálogo = cancelar
                    .show();
        } catch (Exception e) {
            // Defensa adicional: el estado de la ventana puede cambiar justo entre la comprobación
            // de arriba y show() (carrera real, aunque rara). No se oculta con un catch vacío: se
            // registra el motivo real y la propuesta se descarta -- nunca se ejecuta una acción sin
            // que el diálogo de verdad se haya llegado a mostrar.
            accionActual = null;
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "ChatAsistenteActivity.mostrarConfirmacion",
                    "accion=" + a.accion + " fallo al mostrar: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** Único lugar de todo el flujo que puede llamar a un método real de GeoMB (RecorridoService,
     *  SeguimientoService) -- nunca se llama desde el texto del chat, solo desde el botón
     *  "Confirmar" de arriba. */
    private void confirmarAccion() {
        AccionPendiente propuesta = accionActual;
        accionActual = null;   // PRIMERA operación: evita una segunda ejecución aunque este
                                // método se disparara dos veces (doble toque, etc.)
        if (propuesta == null) return;
        if (propuesta.expirada()) {
            Toast.makeText(this, R.string.asistente_accion_expirada, Toast.LENGTH_SHORT).show();
            return;
        }
        switch (propuesta.accion) {
            case "detenerRecorrido":
                RecorridoService.detener(this);
                Toast.makeText(this, R.string.asistente_recorrido_detenido, Toast.LENGTH_SHORT).show();
                break;
            case "seguirUnidad":
                iniciarSeguirUnidad(propuesta.parametros.optString("economico", ""));
                break;
            case "dejarDeSeguirUnidad":
                detenerSeguirUnidad(propuesta.parametros.optString("economico", ""));
                break;
            default:
                break;   // ACCIONES_PERMITIDAS ya filtró en onAccionPendiente: no debería pasar
        }
    }

    private void cancelarAccion() {
        accionActual = null;
    }

    // ---- "seguirUnidad" / "dejarDeSeguirUnidad" confirmados (seguimiento de proximidad) ----

    /** Punto de entrada de "seguirUnidad" ya confirmado por el usuario: primero pide (si falta)
     *  ACCESS_FINE_LOCATION -- bloqueante, sin él SeguimientoService no puede arrancar de forma
     *  útil -- y de ahí sigue a {@link #continuarSeguirUnidadTrasPermisoUbicacion()}. Mismo
     *  criterio exacto que MapFragment.intentarSeguirCarta(): esta pantalla no inventa una
     *  política de permisos propia. */
    private void iniciarSeguirUnidad(String economico) {
        if (TextUtils.isEmpty(economico)) return;
        economicoPendienteSeguimiento = economico;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacionAsistente.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        continuarSeguirUnidadTrasPermisoUbicacion();
    }

    private void continuarSeguirUnidadTrasPermisoUbicacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permisoNotifAsistente.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarSeguimientoAsistente();
    }

    /** Ya se resolvió el permiso de ubicación (el de notificaciones es de mejor esfuerzo, ver el
     *  comentario de {@link #permisoNotifAsistente}): ejecuta de verdad SeguimientoService.iniciar(). */
    private void arrancarSeguimientoAsistente() {
        String eco = economicoPendienteSeguimiento;
        economicoPendienteSeguimiento = null;
        if (TextUtils.isEmpty(eco)) return;
        SeguimientoService.iniciar(this, eco);
        Toast.makeText(this, getString(R.string.seguir_activado, eco), Toast.LENGTH_LONG).show();
    }

    /** "dejarDeSeguirUnidad" confirmado: nunca necesita permisos (detener no depende de GPS), así
     *  que se ejecuta directo. Económico vacío = todas las unidades en seguimiento. */
    private void detenerSeguirUnidad(String economico) {
        String eco = TextUtils.isEmpty(economico) ? null : economico;
        SeguimientoService.detener(this, eco);
        Toast.makeText(this, eco == null
                ? getString(R.string.asistente_seguimiento_detenido_todas)
                : getString(R.string.asistente_seguimiento_detenido_uno, eco), Toast.LENGTH_SHORT).show();
    }
}
