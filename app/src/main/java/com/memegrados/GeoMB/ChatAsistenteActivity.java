package com.memegrados.GeoMB;

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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

/**
 * Pantalla de chat con el asistente conversacional (Gemini + Function Calling, backend Python
 * en integrations/gemini/, ver {@link Asistente} y {@link Config#ASISTENTE_CHAT_URL}). Sin
 * MapView de por medio, así que a diferencia de MainActivity (ver su comentario "NUNCA") aquí
 * SÍ es seguro usar adjustResize para el teclado (ver AndroidManifest).
 */
public class ChatAsistenteActivity extends AppCompatActivity {

    private EditText inMensaje;
    private MaterialButton btnEnviar;
    private ProgressBar progreso;
    private TextView txtAviso;
    private MensajeChatAdapter adapter;
    private RecyclerView rv;
    private boolean cuotaAgotada = false;

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
            header.setPadding(header.getPaddingLeft(), headerPadTop + sb.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            v.setPadding(sb.left, 0, sb.right, sb.bottom);
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

        Asistente.enviar(this, mensaje, false, new Asistente.Callback() {
            @Override
            public void onRespuesta(String texto) {
                fijarCargando(false);
                adapter.agregar(TextUtils.isEmpty(texto)
                        ? getString(R.string.asistente_error_red) : texto, false);
                rv.scrollToPosition(adapter.cantidad() - 1);
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
}
