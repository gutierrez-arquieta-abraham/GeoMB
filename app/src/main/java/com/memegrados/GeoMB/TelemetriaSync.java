package com.memegrados.GeoMB;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sube a Firestore ({@code usuarios/{uid}/...}, la misma colección que ya usa {@link LoginActivity}
 * para el perfil) lo que {@link Telemetria} haya ido guardando en Room, y lo marca sincronizado.
 * Se llama de forma OPORTUNISTA (al terminar un recorrido, guardar un favorito, o al abrir la app)
 * en vez de tener un Service propio: los datos ya están seguros en Room, así que no hay apuro ni
 * cola de reintentos que mantener; el próximo disparo los recoge igual. Sin sesión iniciada no hace
 * nada — los datos se quedan en Room hasta que el usuario inicie sesión.
 *
 * NOTA: el {@code id} autogenerado de Room se usa tal cual como ID del documento en Firestore. Tras
 * una reinstalación (Room se borra y el contador vuelve a 1) podría coincidir con un id ya subido en
 * una sesión anterior del MISMO usuario y sobrescribirlo; dado que esto es solo histórico/analítica
 * (no datos que el usuario edite), se acepta como limitación conocida en esta primera versión.
 */
public final class TelemetriaSync {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long RETENCION_LOCAL_MS = 30L * 24 * 60 * 60 * 1000;   // 30 días

    private TelemetriaSync() {}

    public static void sincronizar(Context c) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;   // sin sesión: se sincroniza la próxima vez que haya
        Context app = c.getApplicationContext();
        String uid = u.getUid();
        IO.execute(() -> {
            try { subir(app, uid); } catch (Exception ignore) {}   // sin conexión: Firestore encola y reintenta solo
        });
    }

    private static void subir(Context app, String uid) {
        AppDatabase db = AppDatabase.get(app);
        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        com.google.firebase.firestore.CollectionReference base =
                fs.collection("usuarios").document(uid).collection("telemetria");

        for (RecorridoEntity r : db.recorridoDao().pendientesSync()) {
            Map<String, Object> m = new HashMap<>();
            m.put("origen", r.origen);
            m.put("destino", r.destino);
            m.put("totalEstaciones", r.totalEstaciones);
            m.put("inicioTs", r.inicioTs);
            m.put("finTs", r.finTs);
            m.put("errores", db.errorEventoDao().contarDeRecorrido(r.id));
            base.document("recorridos").collection("items").document(String.valueOf(r.id)).set(m);
            db.recorridoDao().marcarSincronizado(r.id);
        }
        for (EventoEstacionEntity e : db.eventoEstacionDao().pendientesSync()) {
            Map<String, Object> m = new HashMap<>();
            m.put("recorridoId", e.recorridoId);
            m.put("estacion", e.estacion);
            m.put("linea", e.linea);
            m.put("tipo", e.tipo);
            m.put("ts", e.ts);
            base.document("eventos_estacion").collection("items").document(String.valueOf(e.id)).set(m);
            db.eventoEstacionDao().marcarSincronizado(e.id);
        }
        for (ErrorEventoEntity e : db.errorEventoDao().pendientesSync()) {
            Map<String, Object> m = new HashMap<>();
            m.put("recorridoId", e.recorridoId);
            m.put("tipo", e.tipo);
            m.put("contexto", e.contexto);
            m.put("mensaje", e.mensaje);
            m.put("ts", e.ts);
            base.document("errores").collection("items").document(String.valueOf(e.id)).set(m);
            db.errorEventoDao().marcarSincronizado(e.id);
        }
        for (BusquedaUnidadEntity b : db.busquedaUnidadDao().pendientesSync()) {
            Map<String, Object> m = new HashMap<>();
            m.put("economico", b.economico);
            m.put("ts", b.ts);
            base.document("busquedas").collection("items").document(String.valueOf(b.id)).set(m);
            db.busquedaUnidadDao().marcarSincronizado(b.id);
        }
        for (EconomicoFavoritoEntity f : db.economicoFavoritoDao().pendientesSync()) {
            Map<String, Object> m = new HashMap<>();
            m.put("fechaGuardado", f.fechaGuardado);
            base.document("economicos_favoritos").collection("items").document(f.economico).set(m);
            db.economicoFavoritoDao().marcarSincronizado(f.economico);
        }

        long limite = System.currentTimeMillis() - RETENCION_LOCAL_MS;
        db.recorridoDao().purgar(limite);
        db.eventoEstacionDao().purgar(limite);
        db.errorEventoDao().purgar(limite);
        db.busquedaUnidadDao().purgar(limite);
    }
}
