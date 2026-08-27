package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

/** Estación del Metrobús. */
public class Estacion {

    public final String nombre;
    public final LatLng posicion;
    public final String icono;   // nombre de recurso drawable (ic_est_L_n) o "" si no hay
    public final boolean soloMapa;   // true = se dibuja en el mapa pero el planificador la ignora (2º andén a ras)

    public Estacion(String nombre, double lat, double lon) {
        this(nombre, lat, lon, "", false);
    }

    public Estacion(String nombre, double lat, double lon, String icono) {
        this(nombre, lat, lon, icono, false);
    }

    public Estacion(String nombre, double lat, double lon, String icono, boolean soloMapa) {
        this.nombre = nombre;
        this.posicion = new LatLng(lat, lon);
        this.icono = icono != null ? icono : "";
        this.soloMapa = soloMapa;
    }
}
