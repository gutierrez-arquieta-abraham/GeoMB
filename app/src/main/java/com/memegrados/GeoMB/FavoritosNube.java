package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Favoritos guardados en la cuenta: las unidades guardadas ("Mis unidades") y las estaciones
 * favoritas del estilo CITI viajan con el usuario de un teléfono a otro.
 */
// ============================================================
// CLASE    : FavoritosNube
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Se guardan como campos del MISMO documento del usuario, usuarios/{uid}
// (el que ya escribe LoginActivity y que las reglas de Firestore permiten
// al propio usuario -- LOGIN_FIREBASE_SETUP.md §7):
//   favUnidades   : { "<económico>": { f: fechaGuardado, l: línea, r: radio } }
//   favEstaciones : [ "línea|nombre|lat,lon", ... ]   (claves de EstiloCiti)
//   favTs         : última vez que cambiaron (ms)
//
// Regla de fusión (sincronizar(), al abrir la app):
//   · Primera vez que este teléfono sincroniza: unión de lo local y lo de la
//     nube (teléfono nuevo: se recupera todo sin perder lo que ya hubiera).
//   · Entró OTRA cuenta en el teléfono: lo de la nube reemplaza lo local.
//   · Después: si la nube cambió después de lo último que este teléfono
//     subió (favTs > K_TS_LOCAL), la nube manda (se borró o agregó en otro
//     teléfono); si no, se sube lo local.
// Cada cambio local (cambioLocal()) marca la hora y sube.
//
// La alerta "avisarme cuando esté cerca" NO se restaura activa en otro
// teléfono: arrancar AlertasUnidadesService requiere una acción explícita del
// usuario (ver su javadoc). Se conserva el radio que tenía.
// ============================================================
public final class FavoritosNube {

    private FavoritosNube() {}

    private static final String PREFS = "favoritos_nube";
    private static final String K_UID = "uid";          // cuenta con la que ya se fusionó este teléfono
    private static final String K_TS_LOCAL = "ts";      // favTs de lo último subido/bajado aquí
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile boolean sincronizado = false;   // una vez por proceso (MainActivity y CitiActivity)

    /** Al abrir la app con sesión: fusiona lo de la nube con lo local (ver la regla arriba). */
    public static void sincronizar(Context c) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || sincronizado) return;
        sincronizado = true;
        Context app = c.getApplicationContext();
        String uid = u.getUid();
        DocumentReference doc = FirebaseFirestore.getInstance().collection("usuarios").document(uid);
        doc.get().addOnSuccessListener(IO, snap -> {
            try { fusionar(app, uid, doc, snap); }
            catch (RuntimeException e) {
                Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "FavoritosNube.fusionar", String.valueOf(e));
            }
        }).addOnFailureListener(e -> sincronizado = false);   // sin red: se reintenta la próxima vez
    }

    /** Un favorito cambió en este teléfono: sube el estado completo. */
    public static void cambioLocal(Context c) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        Context app = c.getApplicationContext();
        String uid = u.getUid();
        IO.execute(() -> {
            try {
                // Aún sin fusionar con la nube en este teléfono/cuenta: no pisar lo de la nube; la fusión
                // pendiente (sincronizar) ya incluirá este cambio.
                if (!uid.equals(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_UID, null))) return;
                try { subir(app, FirebaseFirestore.getInstance().collection("usuarios").document(uid), System.currentTimeMillis()); }
                catch (RuntimeException e) {
                    Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "FavoritosNube.subir", String.valueOf(e));
                }
            } catch (Throwable t) {   // un error en este hilo cerraría la app
                android.util.Log.w("GeoMB", "FavoritosNube: fallo en segundo plano", t);
            }
        });
    }

    private static void fusionar(Context app, String uid, DocumentReference doc, DocumentSnapshot snap) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uidPrevio = p.getString(K_UID, null);
        boolean primeraVez = uidPrevio == null;
        // Otra cuenta en este teléfono: lo local es de la cuenta anterior, la nube manda.
        long tsLocal = uid.equals(uidPrevio) ? p.getLong(K_TS_LOCAL, 0) : -1;
        if (!primeraVez && !uid.equals(uidPrevio)) p.edit().putString(K_UID, uid).apply();
        Map<String, Map<String, Object>> nubeU = unidadesDe(snap);
        List<String> nubeE = estacionesDe(snap);
        Long tsNube = snap.getLong("favTs");
        if (tsLocal < 0) tsNube = tsNube != null ? tsNube : 0L;   // cambio de cuenta: siempre baja
        EconomicoFavoritoDao dao = AppDatabase.get(app).economicoFavoritoDao();

        if (primeraVez) {
            for (Map.Entry<String, Map<String, Object>> e : nubeU.entrySet())
                if (!dao.existe(e.getKey())) dao.insertar(entidad(e.getKey(), e.getValue()));
            Set<String> est = new HashSet<>(EstiloCiti.clavesFavoritas(app));
            est.addAll(nubeE);
            EstiloCiti.reemplazarFavoritas(app, est);
            p.edit().putString(K_UID, uid).apply();
            subir(app, doc, System.currentTimeMillis());
            return;
        }
        if (tsNube != null && (tsNube > tsLocal || tsLocal < 0)) {   // cambió en otro teléfono: la nube manda
            Set<String> quedan = new HashSet<>(nubeU.keySet());
            for (EconomicoFavoritoEntity f : dao.listar())
                if (!quedan.contains(f.economico)) {
                    dao.borrar(f.economico);
                    AlertasBackend.eliminarAlerta(app, f.economico);
                }
            for (Map.Entry<String, Map<String, Object>> e : nubeU.entrySet())
                if (!dao.existe(e.getKey())) dao.insertar(entidad(e.getKey(), e.getValue()));
            EstiloCiti.reemplazarFavoritas(app, new HashSet<>(nubeE));
            p.edit().putLong(K_TS_LOCAL, tsNube).apply();
        } else if (tsNube == null || tsNube < tsLocal) {
            subir(app, doc, tsLocal > 0 ? tsLocal : System.currentTimeMillis());
        }
    }

    /** Sube el estado local completo (update reemplaza los campos: así viajan también las bajas). */
    private static void subir(Context app, DocumentReference doc, long ts) {
        Map<String, Object> unidades = new HashMap<>();
        for (EconomicoFavoritoEntity f : AppDatabase.get(app).economicoFavoritoDao().listar()) {
            Map<String, Object> m = new HashMap<>();
            m.put("f", f.fechaGuardado);
            m.put("l", f.linea);
            m.put("r", f.radioAlertaM);
            unidades.put(f.economico, m);
        }
        Map<String, Object> campos = new HashMap<>();
        campos.put("favUnidades", unidades);
        campos.put("favEstaciones", new ArrayList<>(EstiloCiti.clavesFavoritas(app)));
        campos.put("favTs", ts);
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(K_TS_LOCAL, ts).apply();
        doc.update(campos).addOnFailureListener(IO, e -> doc.set(campos, SetOptions.merge()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> unidadesDe(DocumentSnapshot snap) {
        Map<String, Map<String, Object>> res = new HashMap<>();
        Object o = snap.get("favUnidades");
        if (!(o instanceof Map)) return res;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            res.put(e.getKey(), e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : new HashMap<>());
        }
        return res;
    }

    private static List<String> estacionesDe(DocumentSnapshot snap) {
        List<String> res = new ArrayList<>();
        Object o = snap.get("favEstaciones");
        if (o instanceof List) for (Object x : (List<?>) o) if (x instanceof String) res.add((String) x);
        return res;
    }

    private static EconomicoFavoritoEntity entidad(String eco, Map<String, Object> m) {
        EconomicoFavoritoEntity f = new EconomicoFavoritoEntity();
        f.economico = eco;
        f.fechaGuardado = num(m.get("f"), System.currentTimeMillis());
        f.linea = (int) num(m.get("l"), 0);
        f.radioAlertaM = (int) num(m.get("r"), 500);
        f.alertaActiva = false;
        f.sincronizado = false;
        return f;
    }

    private static long num(Object o, long def) {
        return o instanceof Number ? ((Number) o).longValue() : def;
    }
}
