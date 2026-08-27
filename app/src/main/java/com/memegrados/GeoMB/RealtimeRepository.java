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
 * (Railway) y las entrega ya parseadas en el hilo principal.
 *
 * También guarda la selección actual (unidad o línea a mostrar en el mapa)
 * para comunicar entre pantallas.
 */
public final class RealtimeRepository {

    private static RealtimeRepository instancia;

    /** Selección hecha desde el buscador (número económico) o -1/null si ninguna. */
    public static String unidadSeleccionada = null;
    public static int lineaSeleccionada = -1;
    /** Económico que el buscador del mapa manda al buscador de unidades si no está en vivo. */
    public static String ecoParaBuscar = null;

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

    /** Últimas unidades que cumplen los filtros activos. */
    public List<UnidadReal> filtradas() {
        if (!filtro.hayAlguno()) return ultimo;
        List<UnidadReal> res = new ArrayList<>();
        for (UnidadReal u : ultimo) if (filtro.cumple(u)) res.add(u);
        return res;
    }

    /** Unidades en servicio de una línea (respetando los demás filtros). */
    public List<UnidadReal> deLinea(int numeroLinea) {
        List<UnidadReal> res = new ArrayList<>();
        for (UnidadReal u : ultimo) {
            if (u.linea != null && u.linea == numeroLinea && filtro.cumple(u)) res.add(u);
        }
        return res;
    }

    /** Cuántas unidades en servicio hay por línea (1..7). */
    public int cuentaLinea(int numeroLinea) {
        int c = 0;
        for (UnidadReal u : ultimo) if (u.linea != null && u.linea == numeroLinea) c++;
        return c;
    }

    /** Descarga el feed en segundo plano y responde en el hilo principal. */
    public void fetch(Callback cb) {
        executor.execute(() -> {
            try {
                String json = Backend.descargar(Config.PATH_VEHICLES);
                List<UnidadReal> lista = parsear(json);
                ultimo = lista;
                main.post(() -> cb.onData(lista));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "error"));
            }
        });
    }

    private List<UnidadReal> parsear(String json) throws Exception {
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
            // Override "control maestro": unidades recién enviadas al corredor L1 que el feed todavía
            // no mapea (line vacío). Se fuerzan a L1 para que aparezcan con color y se filtren por línea.
            // Se comparan por el número "pelado" (solo dígitos, sin ceros a la izquierda) para tolerar
            // etiquetas como "09507", "9507 " o "MB-9507" que trae el feed. NOTA: solo asigna la línea;
            // si la unidad NO viene en el feed (sin GPS), no hay posición y no se puede dibujar.
            // Quitar de esta lista cuando el feed ya las mapee.
            String eco = numero.replaceAll("\\D", "").replaceFirst("^0+", "");
            if ("9516".equals(eco) || "9517".equals(eco) || "9518".equals(eco)
                    || "9507".equals(eco) || "9524".equals(eco)) {
                linea = 1;
            }

            String destino = o.isNull("destino") ? null : o.optString("destino", null);
            String origen = o.isNull("origen") ? null : o.optString("origen", null);
            String ruta = o.isNull("route_id") ? null : o.optString("route_id", null);
            String placa = o.optString("plate", "");
            float rumbo = (float) o.optDouble("bearing", 0);
            // Empresa, marca y modelo salen TODOS del CSV de Drive (Modelos).
            Modelos.Ficha ficha = Modelos.paraEconomico(numero);

            lista.add(new UnidadReal(numero, linea, destino, origen, ruta, ficha.empresa,
                    ficha.marca, ficha.modelo, placa, lat, lon, rumbo));
        }
        return lista;
    }
}
