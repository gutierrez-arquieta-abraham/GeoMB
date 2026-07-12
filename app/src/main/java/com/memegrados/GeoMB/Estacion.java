package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

/** Estación del Metrobús. */
public class Estacion {

    public final String nombre;
    public final LatLng posicion;

    public Estacion(String nombre, double lat, double lon) {
        this.nombre = nombre;
        this.posicion = new LatLng(lat, lon);
    }
}
