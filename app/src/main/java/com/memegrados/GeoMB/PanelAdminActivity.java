package com.memegrados.GeoMB;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Panel admin de afectaciones (aviso manual): formulario NATIVO que arma el MISMO POST que el
 * panel web del backend (POST /admin/afectacion en :8443, protegido con mTLS -- ver
 * admin_afect.py y docs/SESION_2026-09_backend.md §7) y lo manda con el certificado cliente
 * (.p12), en vez de cargar el formulario del servidor en un WebView. Solo alcanzable desde el
 * modo personalizado (ReporteFragment, 5 toques al logo en "Acerca de").
 *
 * <p>El certificado NUNCA se empaqueta en la app ni al repo (regla de CLAUDE.md): el usuario lo
 * importa desde su propio almacenamiento (Storage Access Framework) y solo se persiste el URI
 * elegido (un simple puntero, no un secreto). La CONTRASEÑA del .p12 nunca se guarda: se pide
 * cada vez que se abre el panel, y con ella se arma en memoria (solo para esa sesión) el
 * {@link KeyManagerFactory} que presenta el certificado durante el handshake TLS.
 *
 * <p>IMPORTANTE: los nombres de los campos del POST (linea/estado/lugar/info/circuito/duracion_h)
 * deben calzar EXACTO con lo que lee admin_afect.py (request.form.get(...)); no son arbitrarios.
 */
public class PanelAdminActivity extends AppCompatActivity {

    /** Códigos y etiquetas que acepta admin_afect.py (LINEAS), en el mismo orden que el panel web. */
    private static final String[][] LINEAS = {
            {"1", "Metrobús L1"}, {"2", "Metrobús L2"}, {"3", "Metrobús L3"},
            {"4", "Metrobús L4"}, {"5", "Metrobús L5"}, {"6", "Metrobús L6"}, {"7", "Metrobús L7"},
            {"101", "Mexibús L1"}, {"102", "Mexibús L2"}, {"103", "Mexibús L3"}, {"104", "Mexibús L4"},
            {"111", "Mexibús L1A (AIFA)"}, {"112", "Mexibús L2A (Serv. Eléctrico)"}, {"113", "Mexibús L3A"},
            {"201", "Mexicable L1"}, {"202", "Mexicable L2"},
    };

    /** Catálogo de estados que ofrece el panel web (ESTADOS); el server no valida contra esta
     *  lista (acepta cualquier texto), pero usarla evita errores de dedo y mantiene el mismo
     *  vocabulario que ya reconoce el resto de la app (Manifestaciones). */
    private static final String[] ESTADOS = {
            "Sin servicio", "Servicio parcial", "Retraso en el servicio",
            "Obstrucción de carril", "Manifestación",
            "Paso de largo", "Estación cerrada", "Estación en mantenimiento",
            "Afectación en el servicio", "Servicio restablecido",
    };

    private View scrollForm, txtVacio;
    private ProgressBar prog;
    private Spinner spLinea, spEstado, spEstacion;
    private EditText inOtrasEstaciones, inInfo, inDuracion, inCircuito;

    // Identidad mTLS ya resuelta (KeyStore + contraseña, en memoria SOLO para esta sesión):
    // KeyManagerFactory.init() los necesita juntos para armar el SSLContext del POST.
    private KeyStore keyStore;
    private char[] password;

    private final ActivityResultLauncher<String[]> elegirCert =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) { if (keyStore == null) finish(); return; }
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
        EdgeToEdge.enable(this);   // borde a borde (Android 15+)
        setContentView(R.layout.activity_panel_admin);

        Tipografia.aplicar((TextView) findViewById(R.id.txt_panel_admin_titulo));
        scrollForm = findViewById(R.id.scroll_panel_admin_form);
        aplicarInsets();
        prog = findViewById(R.id.prog_panel_admin);
        txtVacio = findViewById(R.id.txt_panel_admin_vacio);
        spLinea = findViewById(R.id.sp_panel_linea);
        spEstado = findViewById(R.id.sp_panel_estado);
        spEstacion = findViewById(R.id.sp_panel_estacion);
        inOtrasEstaciones = findViewById(R.id.in_panel_otras_estaciones);
        inInfo = findViewById(R.id.in_panel_info);
        inDuracion = findViewById(R.id.in_panel_duracion);
        inCircuito = findViewById(R.id.in_panel_circuito);

        findViewById(R.id.btn_cerrar_panel_admin).setOnClickListener(v -> finish());
        findViewById(R.id.btn_cambiar_cert_panel_admin).setOnClickListener(v -> lanzarSelector());

        armarFormulario();
        findViewById(R.id.btn_panel_enviar).setOnClickListener(v -> confirmarEnvio());

        String guardado = Modos.certAdminUri(this);
        if (guardado == null) lanzarSelector();
        else pedirPassword(Uri.parse(guardado));
    }

    /** Inserta el encabezado (fondo colorPrimary) bajo la barra de estado y deja espacio bajo el
     *  formulario para la barra de navegación, para que el borde a borde (EdgeToEdge) no tape
     *  contenido en Android 15+. */
    private void aplicarInsets() {
        View raiz = findViewById(R.id.panel_admin_root);
        View header = findViewById(R.id.panel_admin_header);
        int headerPadTop = header.getPaddingTop();
        int headerPadStart = header.getPaddingStart();
        int headerPadEnd = header.getPaddingEnd();
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPaddingRelative(headerPadStart + sb.left, headerPadTop + sb.top,
                    headerPadEnd + sb.right, header.getPaddingBottom());
            scrollForm.setPadding(scrollForm.getPaddingLeft() + sb.left, scrollForm.getPaddingTop(),
                    scrollForm.getPaddingRight() + sb.right, sb.bottom);
            return insets;
        });
    }

    private void armarFormulario() {
        List<String> nombresLinea = new ArrayList<>();
        for (String[] par : LINEAS) nombresLinea.add(par[1]);
        spLinea.setAdapter(adaptador(nombresLinea));
        spLinea.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { poblarEstaciones(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        spEstado.setAdapter(adaptador(Arrays.asList(ESTADOS)));
        poblarEstaciones();
    }

    /** Repuebla el spinner de estaciones con las de la línea elegida (datos ya cargados por la
     *  propia app vía GtfsRepository: no depende de ningún catálogo aparte en el backend). */
    private void poblarEstaciones() {
        int idx = spLinea.getSelectedItemPosition();
        List<String> nombres = new ArrayList<>();
        nombres.add(getString(R.string.panel_admin_estacion_ninguna));
        if (idx >= 0) {
            for (Estacion e : estacionesDeLinea(LINEAS[idx][0])) nombres.add(e.nombre);
        }
        spEstacion.setAdapter(adaptador(nombres));
    }

    private List<Estacion> estacionesDeLinea(String codigo) {
        int n;
        try { n = Integer.parseInt(codigo); } catch (Exception e) { return java.util.Collections.emptyList(); }
        List<Linea> todas = new ArrayList<>(GtfsRepository.getLineas(this));
        todas.addAll(GtfsRepository.getMexibus(this));
        for (Linea l : todas) if (l.numero == n) return l.estaciones;
        return java.util.Collections.emptyList();
    }

    private ArrayAdapter<String> adaptador(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void lanzarSelector() {
        try {
            Toast.makeText(this, R.string.panel_admin_elegir_cert, Toast.LENGTH_SHORT).show();
            elegirCert.launch(new String[]{"*/*"});
        } catch (Exception e) {
            Toast.makeText(this, R.string.panel_admin_sin_lector, Toast.LENGTH_LONG).show();
        }
    }

    /** Pide la contraseña del .p12 (NUNCA se guarda) y, con ella, valida el certificado. */
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
                    if (keyStore == null) finish();
                })
                .show();
    }

    /** Carga el .p12 en un hilo aparte (E/S + criptografía, no debe bloquear el hilo principal)
     *  para confirmar que la contraseña es correcta ANTES de mostrar el formulario. */
    private void resolverIdentidad(Uri certUri, String pass) {
        prog.setVisibility(View.VISIBLE);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            KeyStore ks = null;
            try (InputStream in = getContentResolver().openInputStream(certUri)) {
                ks = KeyStore.getInstance("PKCS12");
                ks.load(in, pass.toCharArray());
                if (!ks.aliases().hasMoreElements()) ks = null;   // .p12 vacío: no sirve
            } catch (Exception ignore) {
                ks = null;   // contraseña incorrecta, archivo inválido o permiso perdido
            }
            final KeyStore ksf = ks;
            main.post(() -> {
                prog.setVisibility(View.GONE);
                if (ksf == null) {
                    Toast.makeText(this, R.string.panel_admin_cert_error, Toast.LENGTH_LONG).show();
                    txtVacio.setVisibility(View.VISIBLE);
                    scrollForm.setVisibility(View.GONE);
                    return;
                }
                keyStore = ksf;
                password = pass.toCharArray();
                txtVacio.setVisibility(View.GONE);
                scrollForm.setVisibility(View.VISIBLE);
            });
        }, "panel-admin-cert").start();
    }

    /** Confirma antes de mandar: esto empuja una notificación push real a TODOS los usuarios y
     *  queda escrito en el panel de afectaciones, así que no se dispara con un solo toque. */
    private void confirmarEnvio() {
        if (spLinea.getSelectedItem() == null || spEstado.getSelectedItem() == null) {
            Toast.makeText(this, R.string.panel_admin_faltan_datos, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.panel_admin_confirmar_titulo)
                .setMessage(R.string.panel_admin_confirmar_msg)
                .setPositiveButton(R.string.panel_admin_confirmar_enviar, (d, w) -> enviar())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void enviar() {
        String codigoLinea = LINEAS[spLinea.getSelectedItemPosition()][0];
        String estado = (String) spEstado.getSelectedItem();

        String estacionElegida = spEstacion.getSelectedItemPosition() > 0
                ? (String) spEstacion.getSelectedItem() : "";
        String otras = inOtrasEstaciones.getText().toString().trim();
        String lugar = estacionElegida.isEmpty() ? otras
                : otras.isEmpty() ? estacionElegida : estacionElegida + " y " + otras;

        String info = inInfo.getText().toString().trim();
        String duracion = inDuracion.getText().toString().trim();
        String circuito = inCircuito.getText().toString().trim();

        prog.setVisibility(View.VISIBLE);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok; String cuerpo;
            HttpsURLConnection conn = null;
            try {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, password);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), null, null);   // trustManagers=null -> confía en el sistema

                StringBuilder body = new StringBuilder();
                agregarParam(body, "linea", codigoLinea);
                agregarParam(body, "estado", estado);
                agregarParam(body, "lugar", lugar);
                agregarParam(body, "info", info);
                agregarParam(body, "circuito", circuito);
                agregarParam(body, "duracion_h", duracion);
                byte[] datos = body.toString().getBytes("UTF-8");

                conn = (HttpsURLConnection) new URL(Config.PANEL_ADMIN_URL).openConnection();
                conn.setSSLSocketFactory(ctx.getSocketFactory());
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) { os.write(datos); }

                int code = conn.getResponseCode();
                ok = code >= 200 && code < 300;
                cuerpo = leer(ok ? conn.getInputStream() : conn.getErrorStream());
            } catch (Exception e) {
                ok = false;
                cuerpo = String.valueOf(e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String cuerpof = cuerpo;
            main.post(() -> {
                prog.setVisibility(View.GONE);
                mostrarResultado(cuerpof);
            });
        }, "panel-admin-post").start();
    }

    private static void agregarParam(StringBuilder body, String nombre, String valor) throws Exception {
        if (valor == null) valor = "";
        if (body.length() > 0) body.append('&');
        body.append(nombre).append('=').append(URLEncoder.encode(valor, "UTF-8"));
    }

    private static String leer(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return sb.toString();
    }

    private void mostrarResultado(String cuerpo) {
        try {
            JSONObject j = new JSONObject(cuerpo);
            if (j.optBoolean("ok", false)) {
                Toast.makeText(this, getString(R.string.panel_admin_enviado,
                        j.optString("estado"), j.optString("linea")), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, getString(R.string.panel_admin_error_envio,
                        j.optString("error", "?")), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            String msg = cuerpo == null || cuerpo.trim().isEmpty() ? "?" : cuerpo;
            Toast.makeText(this, getString(R.string.panel_admin_error_envio, msg), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        keyStore = null;
        password = null;
        super.onDestroy();
    }
}
