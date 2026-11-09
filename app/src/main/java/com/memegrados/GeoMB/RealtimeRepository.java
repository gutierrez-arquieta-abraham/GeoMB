package com.memegrados.GeoMB;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Descarga las posiciones de las unidades en tiempo real desde el backend
 * y las entrega ya parseadas en el hilo principal.
 *
 * También guarda la selección actual (unidad o línea a mostrar en el mapa)
 * para comunicar entre pantallas.
 */
// ============================================================
// CLASE    : RealtimeRepository
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Descarga las POSICIONES de las unidades en tiempo real desde el
// backend y las entrega ya parseadas EN EL HILO PRINCIPAL (para que la
// UI las dibuje sin trabarse). También guarda la selección actual
// (unidad o línea a mostrar) para comunicar entre pantallas.
//
// PATRÓN SINGLETON: hay UNA sola instancia (get()), porque el estado
// (última lista, filtros, selección) se comparte entre el mapa y el
// listado. La descarga corre en un executor (2º plano) y publica en el
// 'main' Handler.
//
// Incluye el filtro de "fantasmas" (descarta unidades con timestamp viejo)
// y el de unidades guardadas en patio (visibleParaUsuario, ver Patios).
// ============================================================
public final class RealtimeRepository {

    private static RealtimeRepository instancia;

    /** Selección hecha desde el buscador (número económico) o -1/null si ninguna. */
    public static String unidadSeleccionada = null;
    public static int lineaSeleccionada = -1;
    /** Estación elegida en el listado de una línea (pestaña Líneas): el mapa la centra y la
     *  destella al llegar. Se sabe la posición exacta de antemano (viene del propio listado),
     *  a diferencia de unidadSeleccionada que necesita esperar el feed en vivo. */
    public static com.google.android.gms.maps.model.LatLng estacionSeleccionadaPos = null;
    public static int estacionSeleccionadaLinea = -1;

    /** Timestamp (ms) del último fetch() exitoso y si el más reciente terminó en error -- lo lee
     *  {@link DiagnosticoReporte} para el diagnóstico técnico de ReporteApp (antigüedad del dato,
     *  estado del seguimiento), sin disparar ninguna petición nueva. */
    public static volatile long ultimoFetchExitoTs = 0L;
    public static volatile boolean ultimoFetchError = false;
    /** true si el último fetch() exitoso vino de un camino que decodifica el .proto EN EL
     *  TELÉFONO (UnidadRtEdge o UnidadRtDirecto) en vez del JSON ya armado por el servidor
     *  (Config.PATH_VEHICLES) -- lo lee MapFragment para avisar que puede ser menos preciso y que
     *  las notificaciones (FCM, siempre del lado del servidor) van a llegar más tarde. */
    public static volatile boolean ultimoCaminoLocal = false;

    /** Filtros activos (compartidos entre mapa y listado). */
    public static final Filtro filtro = new Filtro();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<UnidadReal> ultimo = new ArrayList<>();

    private RealtimeRepository() {}

    public static synchronized RealtimeRepository get() {
        if (instancia == null) instancia = new RealtimeRepository();
        return instancia;
    }

    public interface Callback {
        void onData(List<UnidadReal> unidades);
        void onError(String mensaje);
    }

    public List<UnidadReal> getUltimo() {
        return ultimo;
    }

    /** Busca una unidad por número económico en la última descarga. */
    public UnidadReal buscar(String numero) {
        for (UnidadReal u : ultimo) {
            if (u.numero != null && u.numero.equalsIgnoreCase(numero)) return u;
        }
        return null;
    }

    /** ¿Este usuario ve la unidad? Las GUARDADAS en patio ({@link Patios#guardada}) solo se ven con
     *  la función "unidades en patio" ({@link Modos#verUnidadesPatio}); es el mismo criterio que
     *  aplica el mapa (MapFragment.actualizarUnidades), para que listas y conteos cuadren con él. */
    public static boolean visibleParaUsuario(UnidadReal u) {
        return !Patios.ocultar(GeoMBApplication.get(), u);
    }

    /** Últimas unidades que cumplen los filtros activos (sin las guardadas en patio, salvo con la
     *  función "unidades en patio"). */
    public List<UnidadReal> filtradas() {
        List<UnidadReal> res = new ArrayList<>();
        for (UnidadReal u : ultimo) if (filtro.cumple(u) && visibleParaUsuario(u)) res.add(u);
        return res;
    }

    /** Unidades en servicio de una línea (respetando los demás filtros). */
    public List<UnidadReal> deLinea(int numeroLinea) {
        List<UnidadReal> res = new ArrayList<>();
        for (UnidadReal u : ultimo) {
            if (u.linea != null && u.linea == numeroLinea && filtro.cumple(u)
                    && visibleParaUsuario(u)) res.add(u);
        }
        return res;
    }

    /** Cuántas unidades en servicio hay por línea (1..7). */
    public int cuentaLinea(int numeroLinea) {
        int c = 0;
        for (UnidadReal u : ultimo)
            if (u.linea != null && u.linea == numeroLinea && visibleParaUsuario(u)) c++;
        return c;
    }

    /** Descarga el feed en segundo plano y responde en el hilo principal. El try/catch de aquí solo
     *  cubre la descarga+parseo; cb.onData/onError corren DESPUÉS, dentro del post() al hilo
     *  principal, así que se protegen aparte -- si no, una excepción del consumidor (casi todas las
     *  pantallas de la app llaman a fetch() en cada ciclo de refresco) tumbaría la app entera. */
    public void fetch(Callback cb) {
        executor.execute(() -> {
            try {
                List<UnidadReal> listaObtenida;
                boolean caminoLocal;
                try {
                    // Camino nuevo (ver UnidadRtEdge): la app baja y decodifica el feed directo
                    // del proveedor, el servidor solo reparte la URL firmada. Si algo falla por
                    // lo que sea (red, catálogo de rutas aún sin cargar, feed caduco), cae al
                    // camino de siempre SIN que el usuario note nada -- nunca se propaga este
                    // error como si fuera un fallo real de la descarga.
                    listaObtenida = UnidadRtEdge.obtener();
                    caminoLocal = true;
                } catch (Exception edgeErr) {
                    try {
                        String json = Backend.descargar(Config.PATH_VEHICLES);
                        listaObtenida = parsear(json);
                        caminoLocal = false;
                    } catch (Exception backendErr) {
                        // Los 2 caminos de siempre pasan por el backend propio (EC2/Oracle): si
                        // AMBOS fallan, es el backend el que está caído, no solo el feed. Último
                        // recurso -- ver UnidadRtDirecto -- para que el mapa en vivo siga
                        // funcionando pese a eso (p. ej. un corte por falta de pago del EC2).
                        listaObtenida = UnidadRtDirecto.obtener();
                        caminoLocal = true;
                    }
                }
                final List<UnidadReal> lista = listaObtenida;
                ultimo = lista;
                ultimoFetchExitoTs = System.currentTimeMillis();
                ultimoFetchError = false;
                ultimoCaminoLocal = caminoLocal;
                main.post(() -> {
                    try { cb.onData(lista); } catch (Exception e) {
                        GeoMBApplication app = GeoMBApplication.get();
                        if (app != null) Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "RealtimeRepository.onData", String.valueOf(e.getMessage()));
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "error";
                ultimoFetchError = true;
                GeoMBApplication app = GeoMBApplication.get();
                // En pausa la falla es esperada: no se registra como error de red.
                if (app != null && !EstadoServicio.enPausa()) Telemetria.registrarError(app, Telemetria.ERR_RED, "RealtimeRepository.fetch", msg);
                main.post(() -> {
                    try { cb.onError(msg); } catch (Exception e2) {
                        GeoMBApplication app2 = GeoMBApplication.get();
                        if (app2 != null) Telemetria.registrarError(app2, Telemetria.ERR_EXCEPCION, "RealtimeRepository.onError", String.valueOf(e2.getMessage()));
                    }
                });
            }
        });
    }

    private List<UnidadReal> parsear(String json) throws org.json.JSONException {
        List<UnidadReal> lista = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            double lat = o.optDouble("lat", Double.NaN);
            double lon = o.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            String numero = o.optString("label", "");
            if (numero.isEmpty()) numero = o.optString("id", "—");

            // Excluir unidades de prueba/maqueta (no son flota real).
            String low = numero.toLowerCase();
            if (low.contains("test") || low.contains("maqueta") || low.contains("carraci")) continue;

            Integer linea = null;
            String lineaStr = o.isNull("line") ? null : o.optString("line", null);
            if (lineaStr != null && !lineaStr.isEmpty()) {
                try { linea = Integer.parseInt(lineaStr.trim()); } catch (NumberFormatException ignore) {}
            }
            String destino = o.isNull("destino") ? null : o.optString("destino", null);
            String origen = o.isNull("origen") ? null : o.optString("origen", null);
            String ruta = o.isNull("route_id") ? null : o.optString("route_id", null);
            String placa = o.optString("plate", "");
            float rumbo = (float) o.optDouble("bearing", 0);
            // El feed manda la velocidad en km/h; se guarda en m/s para animar el avance sobre el grafo.
            float velMs = (float) (o.optDouble("speed", 0) / 3.6);
            // Epoch (s) del último reporte de la unidad; sirve para descartar "fantasmas" (ver abajo).
            long ts = o.optLong("timestamp", 0);
            // Empresa, marca y modelo salen TODOS del CSV de Drive (Modelos).
            Modelos.Ficha ficha = Modelos.paraEconomico(numero);

            lista.add(new UnidadReal(numero, linea, destino, origen, ruta, ficha.empresa,
                    ficha.marca, ficha.modelo, placa, lat, lon, rumbo, velMs, ts));
        }
        // DESACTIVADO temporalmente: tanto la versión por MÁXIMO como la versión por MEDIANA de
        // filtrarFantasmas() terminaron descartando la mayoría de las ~845 unidades reales del feed
        // (bajaba a ~400 y luego a ~36), señal de que la distribución real de timestamps de este feed
        // no es la esperada (un cúmulo fresco + pocos outliers viejos) y cualquier criterio relativo
        // "adivinado" sin ver los datos reales corre el riesgo de ocultar unidades genuinamente activas.
        // Mientras no se analice una muestra real del feed para calibrar un criterio correcto, se
        // devuelve la lista completa (coincide con lo que confirma el backend: ~845 unidades reales).
        return lista;
    }
}
