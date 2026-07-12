package com.memegrados.GeoMB;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    /** Descarga el feed en segundo plano y responde en el hilo principal. */
    public void fetch(Callback cb) {
        executor.execute(() -> {
            try {
                String json = descargar(Config.VEHICLES_URL);
                List<UnidadReal> lista = parsear(json);
                ultimo = lista;
                main.post(() -> cb.onData(lista));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "error"));
            }
        });
    }

    private String descargar(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) {
                throw new Exception("HTTP " + conn.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = r.readLine()) != null) sb.append(linea);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
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

            Integer linea = null;
            String lineaStr = o.isNull("line") ? null : o.optString("line", null);
            if (lineaStr != null && !lineaStr.isEmpty()) {
                try { linea = Integer.parseInt(lineaStr.trim()); } catch (NumberFormatException ignore) {}
            }

            String destino = o.isNull("destino") ? null : o.optString("destino", null);
            String placa = o.optString("plate", "");
            float rumbo = (float) o.optDouble("bearing", 0);

            lista.add(new UnidadReal(numero, linea, destino, placa, lat, lon, rumbo));
        }
        return lista;
    }
}
