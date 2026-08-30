# Login con Google + registro en Firebase (paquete para activar)

Esto agrega **inicio de sesión con Google** y un **registro de usuarios** en
Firebase (Firestore). Lo dejé aparte porque **no compila hasta que crees tu
proyecto de Firebase** y agregues `google-services.json`. Sigue estos pasos y en
~15 min queda.

El flujo final al abrir la app será:
**Splash (1 vez por reinicio) → Login con Google → aviso ético (1 vez por
dispositivo) → app.** El aviso ético ya funciona hoy; el login se inserta antes.

---

## 1. Crear el proyecto de Firebase

1. Entra a <https://console.firebase.google.com> → **Agregar proyecto**.
2. Dentro del proyecto: **Compilación ▸ Authentication ▸ Comenzar ▸** pestaña
   **Sign-in method ▸** habilita **Google**.
3. **Compilación ▸ Firestore Database ▸ Crear base de datos** (modo de prueba
   por ahora; luego endureces las reglas — ver §7).
4. Agrega una app Android: ícono de Android → **Nombre del paquete**
   `com.memegrados.GeoMB`.

## 2. SHA-1 (necesario para el login con Google)

En Android Studio, pestaña **Gradle** (derecha) → `app ▸ Tasks ▸ android ▸
signingReport`, o en terminal:

```
./gradlew signingReport
```

Copia el **SHA1** de la variante `debug` y pégalo en Firebase:
**Configuración del proyecto ▸ Tus apps ▸ Android ▸ Agregar huella digital**.
(Cuando publiques, agrega también el SHA-1 de tu keystore de release.)

## 3. Descargar google-services.json

En **Configuración del proyecto ▸ Tus apps ▸ Android**, descarga
`google-services.json` y colócalo en la carpeta **`app/`** del proyecto (junto a
`build.gradle` del módulo app).

## 4. Gradle

**build.gradle del proyecto (raíz)** — agrega el classpath del plugin (si usas
el bloque `plugins {}` moderno, usa la línea equivalente que ahí se indica):

```gradle
buildscript {
    dependencies {
        classpath 'com.google.gms:google-services:4.4.2'
    }
}
```

**build.gradle del módulo `app`** — arriba, aplica el plugin:

```gradle
plugins {
    id 'com.android.application'
    id 'com.google.gms.google-services'   // <-- agrega esta línea
}
```

Y en `dependencies { ... }`:

```gradle
implementation platform('com.google.firebase:firebase-bom:33.5.1')
implementation 'com.google.firebase:firebase-auth'
implementation 'com.google.firebase:firebase-firestore'
implementation 'com.google.android.gms:play-services-auth:21.2.0'
```

Sincroniza Gradle (Sync Now).

## 5. Archivos nuevos

### `app/src/main/java/com/memegrados/GeoMB/LoginActivity.java`

```java
package com.memegrados.GeoMB;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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

/** Login con Google + registro del usuario en Firestore. */
public class LoginActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 9001;
    private GoogleSignInClient googleClient;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        auth = FirebaseAuth.getInstance();

        // Si ya hay sesión, entra directo.
        if (auth.getCurrentUser() != null) { irAMain(); return; }

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))  // lo genera google-services.json
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
                Toast.makeText(this, "No se pudo iniciar sesión", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Fallo de autenticación", Toast.LENGTH_LONG).show();
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
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
```

### `app/src/main/res/layout/activity_login.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/mb_red"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="28dp">

    <ImageView
        android:layout_width="90dp"
        android:layout_height="90dp"
        android:src="@drawable/ic_bus"
        app:tint="@color/white"
        android:contentDescription="@string/app_name" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="14dp"
        android:text="@string/app_name"
        android:textColor="@color/white"
        android:textSize="30sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:layout_marginBottom="28dp"
        android:gravity="center"
        android:text="@string/login_sub"
        android:textColor="@color/white"
        android:textSize="14sp" />

    <com.google.android.gms.common.SignInButton
        android:id="@+id/btn_google"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />

</LinearLayout>
```

### Strings (agrega a `res/values/strings.xml`)

```xml
<string name="login_sub">Inicia sesión con Google para continuar</string>
```

(No necesitas declarar `default_web_client_id`: lo genera el plugin de
google-services a partir de `google-services.json`.)

## 6. Enganchar el login en el arranque

En **`AndroidManifest.xml`**, declara la actividad (dentro de `<application>`):

```xml
<activity android:name=".LoginActivity" android:exported="false" />
```

En **`SplashActivity.java`**, cambia el método `irAMain()` para pasar por el
login (el aviso ético se sigue mostrando en el Splash antes de esto):

```java
private void irAMain() {
    startActivity(new Intent(this, LoginActivity.class));  // en vez de MainActivity
    finish();
}
```

`LoginActivity` manda a `MainActivity` cuando ya hay sesión. Así el orden queda:
Splash → aviso ético → Login → app.

## 7. Reglas de Firestore (recomendado)

En Firestore ▸ Reglas, para que solo usuarios autenticados escriban su propio
registro:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    match /usuarios/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

## 8. Ver el registro

En Firebase Console ▸ Firestore ▸ colección **`usuarios`** verás un documento
por persona (email, nombre, último acceso, si aceptó el aviso).

---

Cuando termines los pasos 1–4 y agregues los archivos del paso 5–6, avísame y lo
revisamos juntos. Si algo truena en el build, pégame el error.
