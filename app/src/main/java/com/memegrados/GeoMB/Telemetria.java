package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Punto único para registrar el histórico local (Room): recorridos realizados, tiempos de llegada
 * y salida por estación, errores (reanclajes de GPS, fallas de red, excepciones) y unidades
 * buscadas; además de los económicos guardados como favoritos. Todo se escribe primero en LOCAL
 * (funciona sin conexión); {@link TelemetriaSync} sube lo pendiente a Firestore cuando hay sesión
 * y red, de forma oportunista (no hay un Service propio para esto).
 */
public final class Telemetria {

    public static final int ERR_REANCLAJE = 0;    // corrección de estación/línea por GPS en un recorrido
    public static final int ERR_RED = 1;          // fallo al pedir el feed de unidades al backend
    public static final int ERR_EXCEPCION = 2;    // excepción no manejada (crash o atrapada en un catch)

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Recorrido en curso (memoria, como el resto del estado de {@link RecorridoService}); -1 = ninguno. */
    private static volatile long recorridoActivoId = -1;

    private Telemetria() {}

    private static AppDatabase db(Context c) { return AppDatabase.get(c); }

    /** Ejecuta 'r' en el hilo de E/S, atajando cualquier excepción (Room/SQLite, IO, etc.): esta
     *  clase es el mecanismo de RESPALDO para errores de toda la app (ver registrarError), así que
     *  ella misma nunca debe poder tumbarla -- si algo aquí falla, se descarta con un Log.e y ya
     *  (no se llama a registrarError desde aquí para no arriesgar una recursión si eso también falla). */
    private static void ioSeguro(Runnable r) {
        IO.execute(() -> {
            try { r.run(); } catch (Throwable t) { android.util.Log.e("Telemetria", "fallo interno", t); }
        });
    }

    // ---------------------------------------------------------------- recorridos

    public static void iniciarRecorrido(Context c, String origen, String destino, int totalEstaciones) {
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            RecorridoEntity r = new RecorridoEntity();
            r.origen = origen != null ? origen : "";
            r.destino = destino != null ? destino : "";
            r.totalEstaciones = totalEstaciones;
            r.inicioTs = System.currentTimeMillis();
            recorridoActivoId = db(app).recorridoDao().insertar(r);
        });
    }

    public static void registrarEventoEstacion(Context c, String estacion, int linea, boolean llegada) {
        long rid = recorridoActivoId;
        if (rid < 0 || estacion == null) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            EventoEstacionEntity e = new EventoEstacionEntity();
            e.recorridoId = rid;
            e.estacion = estacion;
            e.linea = linea;
            e.tipo = llegada ? "llegada" : "salida";
            e.ts = System.currentTimeMillis();
            db(app).eventoEstacionDao().insertar(e);
        });
    }

    public static void finalizarRecorrido(Context c) {
        long rid = recorridoActivoId;
        if (rid < 0) return;
        recorridoActivoId = -1;
        Context app = c.getApplicationContext();
        long finTs = System.currentTimeMillis();
        ioSeguro(() -> {
            db(app).recorridoDao().finalizar(rid, finTs);
            TelemetriaSync.sincronizar(app);
        });
    }

    // ---------------------------------------------------------------- errores

    public static void registrarError(Context c, int tipo, String contexto, String mensaje) {
        Context app = c.getApplicationContext();
        Long rid = recorridoActivoId >= 0 ? recorridoActivoId : null;
        ioSeguro(() -> {
            ErrorEventoEntity e = new ErrorEventoEntity();
            e.recorridoId = rid;
            e.tipo = tipo;
            e.contexto = contexto != null ? contexto : "";
            e.mensaje = mensaje != null ? mensaje : "";
            e.ts = System.currentTimeMillis();
            db(app).errorEventoDao().insertar(e);
        });
    }

    /** Igual que {@link #registrarError}, pero además avisa al usuario con un Toast breve y sin
     *  jerga técnica: para los pocos catch que evitan una caída real de la app (servicios en
     *  primer plano, notificaciones push, etc.), donde quedarse callado se sentiría como que la
     *  función simplemente dejó de funcionar sin explicación. */
    public static void avisarError(Context c, int tipo, String contexto, String mensaje) {
        registrarError(c, tipo, contexto, mensaje);
        Context app = c.getApplicationContext();
        MAIN.post(() -> android.widget.Toast.makeText(app, R.string.aviso_error_generico, android.widget.Toast.LENGTH_SHORT).show());
    }

    private static final String PREFS_CRASH = "geomb_crashes_pendientes";

    /**
     * Instala un manejador global de excepciones no atrapadas. Durante un crash NO es seguro
     * escribir en Room (el proceso está muriendo), así que el crash se guarda en SharedPreferences
     * (escritura simple y rápida) y se migra a Room la próxima vez que la app arranca normal.
     */
    public static void instalarCapturaErrores(Context c) {
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler previo = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((hilo, error) -> {
            try { guardarCrashPendiente(app, hilo.getName(), String.valueOf(error)); } catch (Exception ignore) {}
            if (previo != null) previo.uncaughtException(hilo, error);
        });
        migrarCrashesPendientes(app);
    }

    private static void guardarCrashPendiente(Context app, String hilo, String error) {
        SharedPreferences p = app.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE);
        JSONArray arr;
        try { arr = new JSONArray(p.getString("lista", "[]")); } catch (Exception e) { arr = new JSONArray(); }
        try {
            JSONObject o = new JSONObject();
            o.put("hilo", hilo);
            o.put("error", error);
            o.put("ts", System.currentTimeMillis());
            arr.put(o);
        } catch (Exception ignore) {}
        p.edit().putString("lista", arr.toString()).apply();
    }

    private static void migrarCrashesPendientes(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE);
        String s = p.getString("lista", "");
        if (s.isEmpty()) return;
        p.edit().remove("lista").apply();
        ioSeguro(() -> {
            try {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    ErrorEventoEntity e = new ErrorEventoEntity();
                    e.tipo = ERR_EXCEPCION;
                    e.contexto = "crash:" + o.optString("hilo", "");
                    e.mensaje = o.optString("error", "");
                    e.ts = o.optLong("ts", System.currentTimeMillis());
                    db(app).errorEventoDao().insertar(e);
                }
            } catch (Exception ignore) {}
            TelemetriaSync.sincronizar(app);
        });
    }

    // ---------------------------------------------------------------- búsquedas

    public static void registrarBusqueda(Context c, String economico) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            BusquedaUnidadEntity b = new BusquedaUnidadEntity();
            b.economico = economico;
            b.ts = System.currentTimeMillis();
            db(app).busquedaUnidadDao().insertar(b);
        });
    }

    // ---------------------------------------------------------------- favoritos (económicos guardados)

    public interface OnFavoritos { void listo(List<EconomicoFavoritoEntity> favoritos); }

    public static void guardarFavorito(Context c, String economico) { guardarFavorito(c, economico, 0); }

    /**
     * Igual, pero cacheando también {@code linea} (si se conoce AHORA, p. ej. porque se guarda
     * desde la carta con la unidad en vivo a la mano) -- puramente para presentación en "Mis
     * unidades" (ver EconomicoFavoritoEntity.linea), nunca fuente de verdad. 0 = desconocida.
     * OJO: usa INSERT-OR-REPLACE, así que solo debe llamarse cuando el económico NO está ya
     * guardado (los llamadores ya comprueban {@link #esFavorito} antes) -- si se llamara sobre
     * una fila existente, REPLACE la reescribiría completa y perdería alertaActiva/radioAlertaM
     * que el usuario ya hubiera configurado.
     */
    public static void guardarFavorito(Context c, String economico, int linea) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            EconomicoFavoritoEntity f = new EconomicoFavoritoEntity();
            f.economico = economico;
            f.fechaGuardado = System.currentTimeMillis();
            f.sincronizado = false;
            f.linea = linea;
            db(app).economicoFavoritoDao().insertar(f);
            TelemetriaSync.sincronizar(app);
        });
    }

    public static void quitarFavorito(Context c, String economico) {
        if (economico == null) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> db(app).economicoFavoritoDao().borrar(economico));
    }

    public static void listaFavoritos(Context c, OnFavoritos cb) {
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            List<EconomicoFavoritoEntity> lista = db(app).economicoFavoritoDao().listar();
            // cb.listo corre en el post() al hilo principal, ya fuera del try/catch de ioSeguro.
            MAIN.post(() -> { try { cb.listo(lista); } catch (Exception ignore) {} });
        });
    }

    public static void esFavorito(Context c, String economico, Consumer<Boolean> cb) {
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            boolean si = economico != null && db(app).economicoFavoritoDao().existe(economico);
            MAIN.post(() -> { try { cb.accept(si); } catch (Exception ignore) {} });
        });
    }

    /** La fila guardada de {@code economico} (con su alertaActiva/radioAlertaM/linea cacheada),
     *  o null si esa unidad no está guardada. */
    public static void obtenerFavorito(Context c, String economico, Consumer<EconomicoFavoritoEntity> cb) {
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            EconomicoFavoritoEntity f = economico != null ? db(app).economicoFavoritoDao().obtener(economico) : null;
            MAIN.post(() -> { try { cb.accept(f); } catch (Exception ignore) {} });
        });
    }

    /**
     * Activa/desactiva "avisarme cuando esté cerca" para {@code economico}. Si la unidad TODAVÍA
     * no estaba guardada, esta llamada la guarda primero (no tiene sentido una alerta sin una fila
     * donde persistirla) -- "Guardada" y "Alerta" siguen siendo estados independientes: activar la
     * alerta puede implicar guardar, pero guardar nunca activa la alerta, y NUNCA toca
     * SeguimientoService en ningún sentido. {@code linea} se cachea solo si hubo que guardar ahora
     * (si ya estaba guardada, su caché de línea no se toca aquí).
     */
    public static void actualizarAlertaUnidad(Context c, String economico, int linea, boolean activa, Runnable alTerminar) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            EconomicoFavoritoDao dao = db(app).economicoFavoritoDao();
            if (dao.obtener(economico) == null) {
                EconomicoFavoritoEntity f = new EconomicoFavoritoEntity();
                f.economico = economico;
                f.fechaGuardado = System.currentTimeMillis();
                f.sincronizado = false;
                f.linea = linea;
                f.alertaActiva = activa;
                dao.insertar(f);
            } else {
                dao.actualizarAlertaActiva(economico, activa);
            }
            TelemetriaSync.sincronizar(app);
            if (alTerminar != null) MAIN.post(() -> { try { alTerminar.run(); } catch (Exception ignore) {} });
        });
    }

    /** Cambia solo el radio de aviso (250/500/1000 m) de una unidad YA guardada. Sin efecto si no
     *  está guardada (no hay fila donde escribirlo). */
    public static void actualizarRadioAlerta(Context c, String economico, int radioM, Runnable alTerminar) {
        if (economico == null || economico.isEmpty()) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> {
            db(app).economicoFavoritoDao().actualizarRadioAlerta(economico, radioM);
            if (alTerminar != null) MAIN.post(() -> { try { alTerminar.run(); } catch (Exception ignore) {} });
        });
    }

    /** Refresca la caché de línea de una unidad ya guardada (p. ej. al volver a verla en el feed
     *  con datos actuales). Solo presentación -- ver EconomicoFavoritoEntity.linea. */
    public static void refrescarLineaCache(Context c, String economico, int linea) {
        if (economico == null || economico.isEmpty() || linea <= 0) return;
        Context app = c.getApplicationContext();
        ioSeguro(() -> db(app).economicoFavoritoDao().actualizarLinea(economico, linea));
    }
}
