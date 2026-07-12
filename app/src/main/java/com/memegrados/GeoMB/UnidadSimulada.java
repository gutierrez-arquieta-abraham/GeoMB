package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

/**
 * Unidad del Metrobús simulada: avanza por el trazado de su línea
 * y rebota en las terminales. Cuando exista una fuente de posiciones
 * en tiempo real (GTFS-Realtime), esta clase se sustituye por datos reales.
 */
public class UnidadSimulada {

    public final String numero;
    public final Linea linea;

    private double posicionM;   // metros desde el inicio de la ruta
    private int direccion;      // +1 ida, -1 vuelta
    private final double velocidad; // m/s
    private long ultimaActualizacion;

    public UnidadSimulada(String numero, Linea linea, double posicionInicialM, double velocidad) {
        this.numero = numero;
        this.linea = linea;
        this.posicionM = posicionInicialM;
        this.velocidad = velocidad;
        this.direccion = 1;
        this.ultimaActualizacion = System.currentTimeMillis();
    }

    /** Avanza la unidad `segundos` de tiempo simulado. */
    public void avanzar(double segundos) {
        posicionM += direccion * velocidad * segundos;
        if (posicionM >= linea.largoTotal()) {
            posicionM = linea.largoTotal();
            direccion = -1;
        } else if (posicionM <= 0) {
            posicionM = 0;
            direccion = 1;
        }
        ultimaActualizacion = System.currentTimeMillis();
    }

    public LatLng posicion() {
        return linea.puntoEn(posicionM);
    }

    public String estado() {
        return "En ruta";
    }

    public long segundosDesdeActualizacion() {
        return (System.currentTimeMillis() - ultimaActualizacion) / 1000;
    }
}
