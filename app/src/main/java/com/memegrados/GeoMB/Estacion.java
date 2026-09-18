package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

// ============================================================
// CLASE    : Estacion
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Representa UNA estación (parada) de una línea. Es una clase de
// DATOS: solo guarda información, no tiene lógica compleja.
//
// CAMPOS:
//   - nombre   : nombre de la estación (con prefijo MXB/MXC en los datos).
//   - posicion : sus coordenadas (latitud, longitud) en el mapa.
//   - icono    : nombre del dibujo (drawable ic_est_L_n) o "" si no tiene.
//   - soloMapa : si es true, se DIBUJA en el mapa pero el planificador
//                la IGNORA (p. ej. un segundo andén a ras de piso).
//
// NOTA — CONSTRUCTORES SOBRECARGADOS:
//   Hay 3 constructores con los mismos datos pero distintos parámetros
//   (con/sin icono, con/sin soloMapa). Los cortos LLAMAN al largo con
//   valores por defecto ("" y false). Esto se llama "sobrecarga".
//
// Los campos son 'final': se asignan una vez y ya no cambian (inmutable).
// ============================================================
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
