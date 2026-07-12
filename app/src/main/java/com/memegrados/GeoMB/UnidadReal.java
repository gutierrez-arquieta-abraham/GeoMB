package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

/** Una unidad del Metrobús en servicio, tal como llega del feed en tiempo real. */
public class UnidadReal {

    public final String numero;     // número económico (label)
    public final Integer linea;     // 1..7, o null si no se pudo mapear la ruta
    public final String destino;    // nombre del destino según la ruta/dirección
    public final String placa;
    public final LatLng posicion;
    public final float rumbo;        // bearing en grados

    public UnidadReal(String numero, Integer linea, String destino,
                      String placa, double lat, double lon, float rumbo) {
        this.numero = numero;
        this.linea = linea;
        this.destino = destino;
        this.placa = placa;
        this.posicion = new LatLng(lat, lon);
        this.rumbo = rumbo;
    }
}
