package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Carga las líneas del Metrobús desde assets/lineas.json (generado del GTFS). */
public final class GtfsRepository {

    private static List<Linea> lineas;

    private GtfsRepository() {}

    public static synchronized List<Linea> getLineas(Context context) {
        if (lineas == null) {
            lineas = cargar(context.getApplicationContext());
        }
        return lineas;
    }

    public static Linea porNumero(Context context, int numero) {
        for (Linea l : getLineas(context)) {
            if (l.numero == numero) return l;
        }
        return null;
    }

    private static List<Linea> cargar(Context context) {
        List<Linea> resultado = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("lineas.json"), StandardCharsets.UTF_8));
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea);
            reader.close();

            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("lineas");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                List<Estacion> estaciones = new ArrayList<>();
                JSONArray ests = o.getJSONArray("estaciones");
                for (int j = 0; j < ests.length(); j++) {
                    JSONObject e = ests.getJSONObject(j);
                    estaciones.add(new Estacion(e.getString("n"),
                            e.getDouble("lat"), e.getDouble("lon")));
                }

                List<LatLng> ruta = new ArrayList<>();
                JSONArray pts = o.getJSONArray("ruta");
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray p = pts.getJSONArray(j);
                    ruta.add(new LatLng(p.getDouble(0), p.getDouble(1)));
                }

                resultado.add(new Linea(o.getInt("numero"), o.getString("nombre"),
                        o.getString("color"), estaciones, ruta));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.unmodifiableList(resultado);
    }
}
