package com.memegrados.GeoMB;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Pantalla de carga. Muestra una animación/video UNA VEZ POR REINICIO DEL
 * SISTEMA (no en cada apertura de la app): detecta el arranque comparando la
 * hora aproximada de boot. Después valida el aviso ético (una vez por
 * dispositivo) antes de entrar a la app.
 *
 * Para poner tu propio video: coloca el archivo en res/raw/splash.mp4
 * (o .webm). Si no existe, se muestra una animación de carga simple.
 */
public class SplashActivity extends AppCompatActivity {

    private static final String PREFS = "geomb";
    private static final String KEY_BOOT = "ultimo_boot";
    private static final String KEY_AVISO = "aviso_aceptado";
    private static final long TOLERANCIA_MS = 4000;
    private static final long CARGA_MIN_MS = 2500;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);   // borde a borde (Android 15+)
        setContentView(R.layout.activity_splash);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (esNuevoArranque()) {
            marcarArranque();
            reproducirCarga();     // muestra video o animación, luego continuar()
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

    // ---- animación / video de carga ----

    private void reproducirCarga() {
        VideoView video = findViewById(R.id.video_splash);
        View fallback = findViewById(R.id.fallback_carga);

        int resId = getResources().getIdentifier("splash", "raw", getPackageName());
        if (resId != 0) {
            fallback.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + resId));
            video.setOnCompletionListener(mp -> continuar());
            video.setOnErrorListener((mp, what, extra) -> { animacionSimple(); return true; });
            video.start();
        } else {
            animacionSimple();
        }
    }

    /** Sin video: muestra la animación de carga un momento y continúa. */
    private void animacionSimple() {
        VideoView video = findViewById(R.id.video_splash);
        View fallback = findViewById(R.id.fallback_carga);
        video.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(this::continuar, CARGA_MIN_MS);
    }

    // ---- aviso ético (gate) y entrada ----

    private boolean continuado = false;

    private void continuar() {
        if (continuado) return;   // evita doble disparo (video + timeout)
        continuado = true;

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
