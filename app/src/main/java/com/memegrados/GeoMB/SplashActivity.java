package com.memegrados.GeoMB;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Pantalla de carga con los créditos de la app, UNA VEZ POR REINICIO DEL SISTEMA (no en cada
 * apertura): detecta el arranque comparando la hora aproximada de boot. Después valida el aviso
 * ético (una vez por dispositivo) antes de entrar a la app.
 */
// ============================================================
// CLASE    : SplashActivity   (extends AppCompatActivity)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Pantalla de CARGA (sustituye al video de antes): créditos como al inicio
// de una película, cada parte aparece y desaparece con un fundido:
//   1) la aplicación (logo de GeoMB);
//   2) colaboradores/patrocinadores con imagen (logo de ABT);
//   3) los nombres en puro texto, de uno en uno (creditos_texto_roles /
//      creditos_texto_nombres, en strings.xml).
// Tocar la pantalla la salta. Solo sale en el primer arranque tras
// reiniciar el teléfono; guarda su estado en SharedPreferences. Los mismos
// créditos, con sus ligas, están en Configuración → Acerca.
// ============================================================
public class SplashActivity extends AppCompatActivity {

    private static final String PREFS = "geomb";
    private static final String KEY_BOOT = "ultimo_boot";
    private static final String KEY_AVISO = "aviso_aceptado";
    private static final long TOLERANCIA_MS = 4000;
    // Tiempos de los créditos (ms): fundido de entrada/salida y cuánto se queda cada parte.
    private static final long FUNDIDO_MS = 450;
    private static final long QUEDA_APP_MS = 1200, QUEDA_LOGOS_MS = 1400, QUEDA_TEXTO_MS = 1000;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);   // borde a borde (Android 15+)
        setContentView(R.layout.activity_splash);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (esNuevoArranque()) {
            marcarArranque();
            reproducirCarga();     // créditos de carga, luego continuar()
        } else {
            continuar();           // ya se mostró en este arranque del sistema
        }
    }

    // ---- detección de reinicio del sistema ----

    private long horaDeBoot() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private boolean esNuevoArranque() {
        long boot = horaDeBoot();
        long ultimo = prefs.getLong(KEY_BOOT, 0);
        return Math.abs(boot - ultimo) > TOLERANCIA_MS;
    }

    private void marcarArranque() {
        prefs.edit().putLong(KEY_BOOT, horaDeBoot()).apply();
    }

    // ---- créditos de carga ----

    private final Handler handler = new Handler(Looper.getMainLooper());

    private void reproducirCarga() {
        findViewById(R.id.splash_root).setOnClickListener(x -> continuar());   // tocar = saltar
        View app = findViewById(R.id.splash_app), logos = findViewById(R.id.splash_logos),
                texto = findViewById(R.id.splash_texto);
        TextView rol = findViewById(R.id.splash_texto_rol), nombres = findViewById(R.id.splash_texto_nombres);
        String[] roles = getResources().getStringArray(R.array.creditos_texto_roles);
        String[] personas = getResources().getStringArray(R.array.creditos_texto_nombres);
        java.util.List<Runnable> pasos = new java.util.ArrayList<>();
        java.util.List<Long> quedas = new java.util.ArrayList<>();
        pasos.add(() -> fundido(app, QUEDA_APP_MS)); quedas.add(QUEDA_APP_MS);
        pasos.add(() -> fundido(logos, QUEDA_LOGOS_MS)); quedas.add(QUEDA_LOGOS_MS);
        for (int k = 0; k < Math.min(roles.length, personas.length); k++) {
            final int i = k;
            pasos.add(() -> {
                rol.setText(roles[i]);
                nombres.setText(personas[i]);
                fundido(texto, QUEDA_TEXTO_MS);
            });
            quedas.add(QUEDA_TEXTO_MS);
        }
        long t = 150;
        for (int k = 0; k < pasos.size(); k++) {
            handler.postDelayed(pasos.get(k), t);
            t += 2 * FUNDIDO_MS + quedas.get(k);
        }
        handler.postDelayed(this::continuar, t);
    }

    /** Aparece (fundido), se queda {@code queda} ms y desaparece. */
    private void fundido(View v, long queda) {
        if (isFinishing() || isDestroyed() || continuado) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(FUNDIDO_MS).withEndAction(() ->
                v.animate().alpha(0f).setStartDelay(queda).setDuration(FUNDIDO_MS).withEndAction(() ->
                        v.animate().setStartDelay(0)).start()).start();
    }

    // ---- aviso ético (gate) y entrada ----

    private boolean continuado = false;

    private void continuar() {
        // Los postDelayed de reproducirCarga() son
        // asíncronos: si la Activity ya terminó/se destruyó antes de que disparen, mostrarAviso()
        // no debe intentar dlg.show() sobre ella.
        if (isFinishing() || isDestroyed()) return;
        if (continuado) return;   // evita doble disparo (toque + fin de los créditos)
        continuado = true;
        handler.removeCallbacksAndMessages(null);

        if (prefs.getBoolean(KEY_AVISO, false)) {
            irAMain();
        } else {
            mostrarAviso();
        }
    }

    private void mostrarAviso() {
        android.view.View v = getLayoutInflater().inflate(R.layout.dialog_aviso, null, false);
        final android.widget.CheckBox cb = v.findViewById(R.id.cb_acepto);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.aviso_titulo)
                .setView(v)
                .setCancelable(false)
                .setPositiveButton(R.string.aviso_aceptar, (d, w) -> {
                    prefs.edit().putBoolean(KEY_AVISO, true).apply();
                    irAMain();
                })
                .setNegativeButton(R.string.aviso_rechazar, (d, w) -> finishAffinity())
                .create();
        dlg.show();
        // "Acepto" solo se habilita al marcar el consentimiento.
        final android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(false);
        cb.setOnCheckedChangeListener((btn, marcado) -> ok.setEnabled(marcado));
    }

    private void irAMain() {
        // Pasa por el login con Google; LoginActivity entra a MainActivity al validar.
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
