package com.memegrados.GeoMB;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.webkit.ClientCertRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * Panel admin de afectaciones (aviso manual): abre el formulario REAL del backend
 * ({@link Config#PANEL_ADMIN_URL}) dentro de un WebView, presentando el certificado
 * cliente (mTLS, .p12) cuando el servidor lo pide durante el handshake TLS — ver
 * docs/SESION_2026-09_backend.md §7. Solo alcanzable desde el modo personalizado
 * (ReporteFragment, 5 toques al logo en "Acerca de").
 *
 * <p>El certificado NUNCA se empaqueta en la app ni al repo (regla de CLAUDE.md): el usuario
 * lo importa desde su propio almacenamiento (Storage Access Framework) y solo se persiste el
 * URI elegido (un simple puntero al archivo, no un secreto). La CONTRASEÑA del .p12 nunca se
 * guarda: se pide cada vez que se abre el panel, y con ella se arma en memoria la identidad
 * (clave privada + cadena de certificados) que se entrega al servidor.
 */
public class PanelAdminActivity extends AppCompatActivity {

    private WebView web;
    private ProgressBar prog;
    private TextView txtVacio;

    // Identidad mTLS ya resuelta ANTES de cargar la URL: onReceivedClientCertRequest() puede
    // llegar en un hilo que no es el principal y debe responder de inmediato (proceed/cancel),
    // así que no se puede abrir un diálogo de contraseña a mitad del handshake TLS.
    private PrivateKey clavePrivada;
    private X509Certificate[] cadena;

    private final ActivityResultLauncher<String[]> elegirCert =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) { if (clavePrivada == null) finish(); return; }
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignore) {
                    // Algunos proveedores de documentos no soportan permiso persistente; sin él,
                    // simplemente habrá que volver a elegir el archivo la próxima vez.
                }
                Modos.setCertAdminUri(this, uri.toString());
                pedirPassword(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel_admin);

        Tipografia.aplicar((TextView) findViewById(R.id.txt_panel_admin_titulo));
        web = findViewById(R.id.web_panel_admin);
        prog = findViewById(R.id.prog_panel_admin);
        txtVacio = findViewById(R.id.txt_panel_admin_vacio);

        findViewById(R.id.btn_cerrar_panel_admin).setOnClickListener(v -> finish());
        findViewById(R.id.btn_cambiar_cert_panel_admin).setOnClickListener(v -> lanzarSelector());

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
                if (clavePrivada != null && cadena != null) request.proceed(clavePrivada, cadena);
                else request.cancel();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                prog.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                prog.setVisibility(View.GONE);
            }
        });

        String guardado = Modos.certAdminUri(this);
        if (guardado == null) lanzarSelector();
        else pedirPassword(Uri.parse(guardado));
    }

    private void lanzarSelector() {
        try {
            Toast.makeText(this, R.string.panel_admin_elegir_cert, Toast.LENGTH_SHORT).show();
            elegirCert.launch(new String[]{"*/*"});
        } catch (Exception e) {
            Toast.makeText(this, R.string.panel_admin_sin_lector, Toast.LENGTH_LONG).show();
        }
    }

    /** Pide la contraseña del .p12 (NUNCA se guarda) y, con ella, arma la identidad mTLS. */
    private void pedirPassword(Uri certUri) {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setHint(getString(R.string.panel_admin_pass_hint));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.panel_admin_pass_titulo)
                .setMessage(R.string.panel_admin_pass_msg)
                .setView(in)
                .setCancelable(false)
                .setPositiveButton(R.string.panel_admin_pass_entrar, (d, w) ->
                        resolverIdentidad(certUri, in.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (clavePrivada == null) finish();
                })
                .show();
    }

    /** Arma la identidad mTLS (clave privada + cadena) en un hilo aparte: leer y descifrar el
     *  .p12 es E/S + criptografía, no debe bloquear el hilo principal. */
    private void resolverIdentidad(Uri certUri, String password) {
        prog.setVisibility(View.VISIBLE);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            PrivateKey pk = null;
            X509Certificate[] chain = null;
            try (InputStream in = getContentResolver().openInputStream(certUri)) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(in, password.toCharArray());
                String alias = null;
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    if (ks.isKeyEntry(a)) { alias = a; break; }
                }
                if (alias != null) {
                    pk = (PrivateKey) ks.getKey(alias, password.toCharArray());
                    Certificate[] c = ks.getCertificateChain(alias);
                    if (c != null) {
                        chain = new X509Certificate[c.length];
                        for (int i = 0; i < c.length; i++) chain[i] = (X509Certificate) c[i];
                    }
                }
            } catch (Exception ignore) {
                // Contraseña incorrecta, archivo inválido o permiso perdido: pk/chain se quedan
                // null y el bloque de abajo lo reporta sin tronar la app.
            }
            final PrivateKey pkf = pk;
            final X509Certificate[] chainf = chain;
            main.post(() -> {
                prog.setVisibility(View.GONE);
                if (pkf == null || chainf == null) {
                    Toast.makeText(this, R.string.panel_admin_cert_error, Toast.LENGTH_LONG).show();
                    txtVacio.setVisibility(View.VISIBLE);
                    return;
                }
                clavePrivada = pkf;
                cadena = chainf;
                txtVacio.setVisibility(View.GONE);
                web.loadUrl(Config.PANEL_ADMIN_URL);
            });
        }, "panel-admin-cert").start();
    }

    @Override
    protected void onDestroy() {
        clavePrivada = null;
        cadena = null;
        super.onDestroy();
    }
}
