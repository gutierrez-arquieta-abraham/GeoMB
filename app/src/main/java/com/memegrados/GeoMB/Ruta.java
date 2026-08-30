package com.memegrados.GeoMB;

/**
 * Una ruta/servicio del Metrobús (del GTFS): un route_id con su línea y su
 * recorrido origen → destino. El "código" se asigna por línea agrupando los
 * dos sentidos (ida y vuelta) del mismo recorrido.
 */
public class Ruta {

    public final String routeId;
    public final int linea;        // 1..7
    public final String origen;
    public final String destino;
    public final String colorHex;
    public int codigo;             // asignado por RutasRepository (1..n por línea)

    public Ruta(String routeId, int linea, String origen, String destino, String colorHex) {
        this.routeId = routeId;
        this.linea = linea;
        this.origen = origen;
        this.destino = destino;
        this.colorHex = colorHex;
    }

    /** "Origen → Destino". */
    public String recorrido() {
        return origen + " → " + destino;
    }

    /** Clave del recorrido sin importar el sentido (ida/vuelta comparten código). */
    public String claveRecorrido() {
        String a = origen != null ? origen : "";
        String b = destino != null ? destino : "";
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
