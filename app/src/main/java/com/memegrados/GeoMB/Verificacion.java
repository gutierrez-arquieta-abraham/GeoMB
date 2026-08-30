package com.memegrados.GeoMB;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

/**
 * Verificación de identidad (KYC) para habilitar el envío de reportes.
 *
 * Política de datos: SOLO se guarda el NOMBRE y una variable booleana "verificado" ligada al
 * dispositivo (SharedPreferences). La identificación oficial y la foto facial NO se guardan aquí;
 * en la integración real las procesa el proveedor KYC y esta app solo recibe el resultado
 * (verificado sí/no) + el nombre.
 *
 * ESTADO: andamiaje. Falta conectar un proveedor KYC real (Incode / MetaMap / Truora / Veridas…):
 * su SDK captura ID + rostro en vivo, verifica, y al terminar se llama a
 * {@link #marcarVerificado(Context, String)} con el nombre validado. Mientras tanto, {@link #iniciar}
 * ofrece un "modo pruebas" para poder validar el resto del flujo (correo/evidencia).
 */
public final class Verificacion {

    private static final String PREFS = "geomb";
    private static final String K_VERIF = "kyc_verificado";
    private static final String K_NOMBRE = "kyc_nombre";

    private Verificacion() {}

    /** ¿El dispositivo ya pasó la verificación de identidad? */
    public static boolean verificado(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_VERIF, false);
    }

    /** Nombre guardado tras verificar (único dato personal persistido). */
    public static String nombre(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_NOMBRE, "");
    }

    /** Marca el dispositivo como verificado y guarda SOLO el nombre. Lo llama el proveedor KYC al ok. */
    public static void marcarVerificado(Context c, String nombre) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_VERIF, true)
                .putString(K_NOMBRE, nombre != null ? nombre.trim() : "")
                .apply();
    }

    /**
     * Lanza el flujo de verificación. PRIMERO pide consentimiento (datos biométricos), y luego:
     * si el proveedor KYC (Didit) está configurado, abre su sesión de verificación (ID + rostro en
     * vivo); si no, ofrece un "modo pruebas" para continuar validando el resto del flujo.
     */
    public static void iniciar(Fragment f) {
        Context c = f.getContext();
        if (c == null) return;
        final android.widget.CheckBox cb = new android.widget.CheckBox(c);
        cb.setText(R.string.verif_consent_check);
        int pad = Math.round(20 * c.getResources().getDisplayMetrics().density);
        cb.setPadding(pad, pad / 2, pad, 0);
        AlertDialog dlg = new AlertDialog.Builder(c)
                .setTitle(R.string.verif_titulo)
                .setMessage(R.string.verif_consent_texto)
                .setView(cb)
                .setPositiveButton(R.string.verif_continuar, null)   // se controla abajo
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();
        final android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(false);
        cb.setOnCheckedChangeListener((b, marcado) -> ok.setEnabled(marcado));
        ok.setOnClickListener(v -> {
            dlg.dismiss();
            if (DiditKYC.configurado()) DiditKYC.abrir(c, uid());
            else Toast.makeText(c, R.string.verif_error, Toast.LENGTH_LONG).show();
        });
    }

    private static String uid() {
        try {
            com.google.firebase.auth.FirebaseUser u =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
