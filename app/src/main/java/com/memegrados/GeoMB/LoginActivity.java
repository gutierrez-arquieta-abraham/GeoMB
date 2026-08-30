package com.memegrados.GeoMB;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Inicio de sesión con Google + registro del usuario en Firestore.
 * Si ya hay sesión activa, entra directo a la app.
 */
public class LoginActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 9001;
    private GoogleSignInClient googleClient;
    private FirebaseAuth auth;

    /** Tras el formulario se piden permisos; al terminar (con o sin concesión) se entra a la app. */
    private final ActivityResultLauncher<String[]> permisos =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    r -> abrirMain());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);   // borde a borde (Android 15+)
        setContentView(R.layout.activity_login);
        // El fondo rojo va a pantalla completa; el contenido se inserta bajo las barras.
        View raiz = findViewById(R.id.login_root);
        int base = Math.round(28 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(base + sb.left, base + sb.top, base + sb.right, base + sb.bottom);
            return insets;
        });
        auth = FirebaseAuth.getInstance();

        // Selector de idioma disponible desde el inicio (para quien no habla español).
        findViewById(R.id.btn_idioma_login).setOnClickListener(v -> Idiomas.mostrarSelector(this));
        Traductor.traducirArbol(raiz);   // traduce el texto del inicio si hay idioma objetivo

        // Si ya inició sesión antes, no vuelve a pedirlo.
        if (auth.getCurrentUser() != null) { irAMain(); return; }

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleClient = GoogleSignIn.getClient(this, gso);

        findViewById(R.id.btn_google).setOnClickListener(v ->
                startActivityForResult(googleClient.getSignInIntent(), RC_SIGN_IN));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            try {
                GoogleSignInAccount acc = GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(ApiException.class);
                autenticarFirebase(acc);
            } catch (ApiException e) {
                // Código 10 = DEVELOPER_ERROR (falta SHA-1 o config OAuth).
                Log.e("LoginActivity", "GoogleSignIn falló, código=" + e.getStatusCode(), e);
                Toast.makeText(this, getString(R.string.login_error) + " (" + e.getStatusCode() + ")",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void autenticarFirebase(GoogleSignInAccount acc) {
        AuthCredential cred = GoogleAuthProvider.getCredential(acc.getIdToken(), null);
        auth.signInWithCredential(cred).addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                registrar(auth.getCurrentUser());
                irAMain();
            } else {
                Log.e("LoginActivity", "Firebase signInWithCredential falló", task.getException());
                Toast.makeText(this, getString(R.string.login_error), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Guarda / actualiza al usuario en la colección "usuarios". */
    private void registrar(FirebaseUser u) {
        if (u == null) return;
        Map<String, Object> datos = new HashMap<>();
        datos.put("email", u.getEmail());
        datos.put("nombre", u.getDisplayName());
        datos.put("uid", u.getUid());
        datos.put("ultimoAcceso", FieldValue.serverTimestamp());
        datos.put("aceptoAviso",
                getSharedPreferences("geomb", MODE_PRIVATE).getBoolean("aviso_aceptado", false));
        FirebaseFirestore.getInstance().collection("usuarios")
                .document(u.getUid()).set(datos, SetOptions.merge());
    }

    private void irAMain() {
        if (!Perfil.configurado(this)) { pedirPerfil(); return; }
        abrirMain();
    }

    private void abrirMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Pide permisos de notificaciones (API 33+) y ubicación; luego entra a la app. */
    private void solicitarPermisos() {
        List<String> faltan = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (faltan.isEmpty()) { abrirMain(); return; }
        permisos.launch(faltan.toArray(new String[0]));
    }

    /** Mini-formulario: tipo de usuario + movilidad reducida. Personaliza la app. */
    private void pedirPerfil() {
        View v = getLayoutInflater().inflate(R.layout.dialog_perfil, null, false);
        RadioGroup rg = v.findViewById(R.id.rg_tipo);
        RadioGroup rgGen = v.findViewById(R.id.rg_genero);
        CheckBox cb = v.findViewById(R.id.cb_movilidad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.perfil_titulo)
                .setView(v)
                .setCancelable(false)
                .setPositiveButton(R.string.perfil_continuar, (d, w) -> {
                    int tipo = rg.getCheckedRadioButtonId() == R.id.rb_aficionado
                            ? Perfil.AFICIONADO : Perfil.NORMAL;
                    int genero = rgGen.getCheckedRadioButtonId() == R.id.rb_mujer
                            ? Perfil.MUJER : Perfil.HOMBRE;
                    boolean movilidad = cb.isChecked();
                    Perfil.guardar(this, tipo, movilidad, genero);
                    guardarPerfilRemoto(tipo, movilidad, genero);
                    solicitarPermisos();   // notificaciones + ubicación, luego entra a la app
                })
                .show();
    }

    /** Guarda el perfil también en Firestore (best-effort). */
    private void guardarPerfilRemoto(int tipo, boolean movilidad, int genero) {
        FirebaseUser u = auth.getCurrentUser();
        if (u == null) return;
        Map<String, Object> datos = new HashMap<>();
        datos.put("tipo", tipo == Perfil.AFICIONADO ? "aficionado" : "normal");
        datos.put("movilidadReducida", movilidad);
        datos.put("genero", genero == Perfil.MUJER ? "mujer" : "hombre");
        try {
            FirebaseFirestore.getInstance().collection("usuarios")
                    .document(u.getUid()).set(datos, SetOptions.merge());
        } catch (Exception ignore) {}
    }
}
