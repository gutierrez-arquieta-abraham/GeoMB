package com.memegrados.GeoMB;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Asistente conversacional (Gemini + Function Calling) como ventana flotante SOBRE el mapa, en
 * vez de ChatAsistenteActivity de pantalla completa -- mismo backend ({@link Asistente}), misma
 * whitelist de acciones, mismo flujo Gemini → accionPendiente → confirmación → usuario acepta →
 * acción Android (ver {@link #confirmarAccion}); lo único que cambia es la PRESENTACIÓN: la
 * confirmación se ve integrada en la propia conversación (ver MensajeChatAdapter.agregarConfirmacion)
 * en vez de un AlertDialog flotante, y el modal se puede cerrar con × para volver al mapa, que
 * sigue montado y visible detrás (se abre con getChildFragmentManager() desde MapFragment, igual
 * que FiltrosSheet).
 *
 * ChatAsistenteActivity sigue existiendo sin cambios (se abre desde Configuración); esta clase NO
 * la reemplaza, es una presentación alternativa del mismo backend para cuando el mapa debe seguir
 * visible. La conversación de este modal vive solo en esta instancia de Fragment -- cerrarlo
 * (dismiss) la descarta, igual que antes se perdía al salir de la Activity; no se agregó memoria
 * persistente nueva.
 *
 * Por qué BottomSheetDialogFragment y no un overlay dentro del propio layout de MapFragment:
 * MainActivity.java documenta (con pruebas reales) que ajustar su ventana por teclado -- aunque
 * sea solo el padding del inset ime() -- rompe el SurfaceView de Google Maps. Un
 * BottomSheetDialogFragment vive en su PROPIA Window (la del Dialog), así que fijar aquí
 * adjustResize (ver onStart()) nunca toca la ventana de MainActivity ni su mapa.
 */
public class AsistenteOverlayFragment extends BottomSheetDialogFragment {

    /** Idéntica a ChatAsistenteActivity.ACCIONES_PERMITIDAS: Android decide qué acción es
     *  conocida y permitida: nunca confía en lo que propone Gemini a ciegas. */
    private static final Set<String> ACCIONES_PERMITIDAS = new HashSet<>(Arrays.asList(
            "detenerRecorrido", "seguirUnidad", "dejarDeSeguirUnidad"));

    /** Fracción de la altura de pantalla para el estado NORMAL (predeterminado al abrir). */
    private static final float ALTURA_NORMAL_FRACCION = 0.78f;

    private EditText inMensaje;
    private MaterialButton btnEnviar;
    private ProgressBar progreso;
    private TextView txtAviso;
    private MensajeChatAdapter adapter;
    private RecyclerView rv;
    private boolean cuotaAgotada = false;

    /** Mismo criterio que ChatAsistenteActivity.accionActual: vive solo en memoria de esta
     *  instancia, nunca se persiste. */
    private AccionPendiente accionActual;
    private String economicoPendienteSeguimiento;

    private final ActivityResultLauncher<String> permisoUbicacionAsistente =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) {
                    continuarSeguirUnidadTrasPermisoUbicacion();
                } else {
                    economicoPendienteSeguimiento = null;
                    Toast.makeText(requireContext(), R.string.seguir_permiso_ubicacion, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> permisoNotifAsistente =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    ok -> arrancarSeguimientoAsistente());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_asistente_overlay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Tipografia.aplicar((TextView) v.findViewById(R.id.txt_asistente_overlay_titulo));
        v.findViewById(R.id.btn_asistente_overlay_cerrar).setOnClickListener(x -> dismiss());

        rv = v.findViewById(R.id.rv_asistente_overlay_mensajes);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new MensajeChatAdapter();
        adapter.setListenerConfirmacion(new MensajeChatAdapter.ListenerConfirmacion() {
            @Override public void onConfirmar(AccionPendiente a) { confirmarAccion(a); }
            @Override public void onCancelar(AccionPendiente a) { cancelarAccion(a); }
        });
        rv.setAdapter(adapter);

        inMensaje = v.findViewById(R.id.in_asistente_overlay_mensaje);
        btnEnviar = v.findViewById(R.id.btn_asistente_overlay_enviar);
        progreso = v.findViewById(R.id.prog_asistente_overlay);
        txtAviso = v.findViewById(R.id.txt_asistente_overlay_aviso);

        btnEnviar.setOnClickListener(x -> enviarDesdeInput());
        inMensaje.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                enviarDesdeInput();
                return true;
            }
            return false;
        });

        adapter.agregar(getString(R.string.asistente_saludo), false);
    }

    /** Contenedor real del BottomSheetDialog (el que BottomSheetBehavior mueve/mide) -- se le
     *  fija la altura dinámicamente en {@link #onStart} y en cada cambio de insets, NUNCA una
     *  vez y listo (ver esa nota ahí: una altura fija que no se recalcula puede exceder lo que
     *  la ventana ya redujo por el teclado, dejando el campo de entrada fuera de los límites
     *  visibles en vez de solo tapado). */
    private FrameLayout hojaInferior;
    private int alturaNormalPx;

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;

        // Ventana PROPIA de este diálogo (nunca la de MainActivity): adjustResize es seguro aquí
        // exactamente por la misma razón que ya lo es en ChatAsistenteActivity (ver su javadoc),
        // solo que aislado en una Window separada en vez de depender de no tener MapView.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        hojaInferior = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        View raiz = getView();
        if (hojaInferior == null || raiz == null) return;

        alturaNormalPx = Math.round(getResources().getDisplayMetrics().heightPixels * ALTURA_NORMAL_FRACCION);

        // Estado NORMAL (prioridad del diseño): ~78% de la altura de pantalla al abrir. MINI queda
        // disponible arrastrando hacia abajo (peekHeight calculado para header + input). Un estado
        // EXPANDIDO propio no se construyó aparte: el recálculo de altura de abajo, al aparecer el
        // teclado, ya cubre el caso "la conversación necesita más espacio" sin una máquina de
        // estados adicional (ver javadoc de la clase / informe de la fase).
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(hojaInferior);
        behavior.setPeekHeight(Math.round(160 * getResources().getDisplayMetrics().density));   // header + input (MINI)
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        fijarAlturaHoja(alturaNormalPx);   // altura inicial, sin teclado

        // En cada cambio de insets (aparece/desaparece teclado, o barra de navegación) se vuelve
        // a calcular cuánto cabe ARRIBA de lo que esté tapando la parte baja de la pantalla, y la
        // hoja nunca pide más que eso -- así el renglón de entrada (al fondo de la columna, altura
        // wrap_content) siempre queda justo encima, nunca tapado ni fuera de los límites visibles.
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (vv, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int ocupadoAbajo = Math.max(ime.bottom, sb.bottom);
            int disponible = getResources().getDisplayMetrics().heightPixels - sb.top - ocupadoAbajo;
            fijarAlturaHoja(Math.min(alturaNormalPx, disponible));
            return insets;
        });
    }

    private void fijarAlturaHoja(int alturaPx) {
        if (hojaInferior == null || alturaPx <= 0) return;
        ViewGroup.LayoutParams lp = hojaInferior.getLayoutParams();
        if (lp.height == alturaPx) return;
        lp.height = alturaPx;
        hojaInferior.setLayoutParams(lp);
    }

    private void enviarDesdeInput() {
        String mensaje = inMensaje.getText().toString().trim();
        if (TextUtils.isEmpty(mensaje) || cuotaAgotada) return;

        adapter.agregar(mensaje, true);
        rv.scrollToPosition(adapter.cantidad() - 1);
        inMensaje.setText("");
        fijarCargando(true);
        txtAviso.setVisibility(View.GONE);

        // Mismo contexto de tracking que ChatAsistenteActivity, sin duplicar esa lógica.
        Asistente.enviar(requireContext(), mensaje, false, DiagnosticoReporte.contextoTracking(requireContext()), new Asistente.Callback() {
            @Override
            public void onRespuesta(String texto) {
                if (!puedeActualizarUi()) return;
                fijarCargando(false);
                adapter.agregar(TextUtils.isEmpty(texto)
                        ? getString(R.string.asistente_error_red) : texto, false);
                rv.scrollToPosition(adapter.cantidad() - 1);
            }

            @Override
            public void onAccionPendiente(JSONObject accionPendiente) {
                if (!puedeActualizarUi()) return;
                String accion = accionPendiente.optString("accion", null);
                if (accion == null || !ACCIONES_PERMITIDAS.contains(accion)) return;   // desconocida: SIN burbuja
                String resumen = accionPendiente.optString("resumen", accion);
                mostrarConfirmacion(new AccionPendiente(accion, accionPendiente.optJSONObject("parametros"), resumen));
            }

            @Override
            public void onCuotaAgotada(String mensaje) {
                if (!puedeActualizarUi()) return;
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
                if (!puedeActualizarUi()) return;
                fijarCargando(false);
                Toast.makeText(requireContext(), R.string.asistente_error_red, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fijarCargando(boolean cargando) {
        progreso.setVisibility(cargando ? View.VISIBLE : View.GONE);
        btnEnviar.setEnabled(!cargando);
        inMensaje.setEnabled(!cargando);
    }

    // ---- confirmación de acciones (ver ACCIONES_PERMITIDAS) ----

    /** Mismo propósito que ChatAsistenteActivity.puedeMostrarDialogo(): la respuesta de Asistente
     *  es asíncrona y puede llegar después de que el usuario ya cerró este modal o rotó la
     *  pantalla -- equivalente de Fragment a isFinishing()/isDestroyed() de Activity. */
    private boolean puedeActualizarUi() {
        return isAdded() && !isStateSaved() && getView() != null;
    }

    /** Punto de entrada desde onAccionPendiente: agrega la burbuja de confirmación en vez de un
     *  AlertDialog -- el resto del flujo (whitelist, token, expiración, ejecución única desde
     *  confirmarAccion()) es idéntico a ChatAsistenteActivity. */
    private void mostrarConfirmacion(AccionPendiente a) {
        accionActual = a;
        adapter.agregarConfirmacion(a);
        rv.scrollToPosition(adapter.cantidad() - 1);
    }

    /** Único lugar de todo el flujo que puede llamar a un método real de GeoMB (RecorridoService,
     *  SeguimientoService) -- nunca desde el texto del chat, solo desde el botón Confirmar de la
     *  burbuja. Compara contra accionActual (en vez de limpiarlo incondicionalmente) para que una
     *  burbuja vieja/duplicada nunca pueda disparar una ejecución fuera de turno. */
    private void confirmarAccion(AccionPendiente a) {
        if (a != accionActual) return;
        accionActual = null;   // PRIMERA operación: evita una segunda ejecución
        if (a.expirada()) {
            Toast.makeText(requireContext(), R.string.asistente_accion_expirada, Toast.LENGTH_SHORT).show();
            return;
        }
        switch (a.accion) {
            case "detenerRecorrido":
                RecorridoService.detener(requireContext());
                Toast.makeText(requireContext(), R.string.asistente_recorrido_detenido, Toast.LENGTH_SHORT).show();
                break;
            case "seguirUnidad":
                iniciarSeguirUnidad(a.parametros.optString("economico", ""));
                break;
            case "dejarDeSeguirUnidad":
                detenerSeguirUnidad(a.parametros.optString("economico", ""));
                break;
            default:
                break;   // ACCIONES_PERMITIDAS ya filtró en onAccionPendiente: no debería pasar
        }
    }

    private void cancelarAccion(AccionPendiente a) {
        if (a == accionActual) accionActual = null;
    }

    // ---- "seguirUnidad" / "dejarDeSeguirUnidad" confirmados (seguimiento de proximidad) ----

    private void iniciarSeguirUnidad(String economico) {
        if (TextUtils.isEmpty(economico)) return;
        economicoPendienteSeguimiento = economico;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacionAsistente.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        continuarSeguirUnidadTrasPermisoUbicacion();
    }

    private void continuarSeguirUnidadTrasPermisoUbicacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permisoNotifAsistente.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarSeguimientoAsistente();
    }

    private void arrancarSeguimientoAsistente() {
        String eco = economicoPendienteSeguimiento;
        economicoPendienteSeguimiento = null;
        if (TextUtils.isEmpty(eco)) return;
        SeguimientoService.iniciar(requireContext(), eco);
        Toast.makeText(requireContext(), getString(R.string.seguir_activado, eco), Toast.LENGTH_LONG).show();
    }

    private void detenerSeguirUnidad(String economico) {
        String eco = TextUtils.isEmpty(economico) ? null : economico;
        SeguimientoService.detener(requireContext(), eco);
        Toast.makeText(requireContext(), eco == null
                ? getString(R.string.asistente_seguimiento_detenido_todas)
                : getString(R.string.asistente_seguimiento_detenido_uno, eco), Toast.LENGTH_SHORT).show();
    }
}
