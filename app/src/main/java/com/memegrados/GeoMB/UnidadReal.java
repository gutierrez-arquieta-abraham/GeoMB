package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

// ============================================================
// CLASE    : UnidadReal
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Representa UNA unidad (autobús) del Metrobús en servicio, tal
// como llega del feed en tiempo real. Es una clase de DATOS.
//
// CAMPOS PRINCIPALES:
//   - numero    : número económico del autobús (su "placa interna").
//   - linea     : 1..7, o null si no se pudo saber su línea.
//   - origen/destino : de dónde sale y a dónde va (según su ruta).
//   - ruta      : route_id (identificador de la ruta en el feed).
//   - empresa/marca/modelo : se calculan a partir del económico.
//   - posicion  : coordenadas actuales (lat, lon).
//   - rumbo     : hacia dónde apunta (grados).
//   - velMs     : velocidad en m/s (el feed la manda en km/h ⇒ se divide entre 3.6).
//   - timestamp : segundos epoch del último reporte (0 = sin dato) →
//                 sirve para descartar unidades "fantasma" (viejas).
//
// MÉTODOS:
//   - marcaModelo() : arma "Marca Modelo" para mostrar (o "Desconocido").
//   - sinRuta()     : true si la unidad no tiene línea asignada.
//
// Campos 'final' = inmutable: cada actualización crea una unidad nueva.
// ============================================================
/** Una unidad del Metrobús en servicio, tal como llega del feed en tiempo real. */
public class UnidadReal {

    public final String numero;     // número económico (label)
    public final Integer linea;     // 1..7, o null si no se pudo mapear la ruta
    public final String destino;    // nombre del destino según la ruta/dirección
    public final String origen;     // nombre del origen (para detectar rutas mixtas)
    public final String ruta;       // route_id del feed (identificador de la ruta)
    public final String empresa;    // concesionario (calculado del económico)
    public final String marca;      // marca del autobús (calculada del económico)
    public final String modelo;     // modelo del autobús (calculado del económico)
    public final String placa;
    public final LatLng posicion;
    public final float rumbo;        // bearing en grados
    public final float velMs;        // velocidad en m/s (el feed la manda en km/h)
    public final long timestamp;     // epoch (s) del último reporte de la unidad (0 = sin dato)

    public UnidadReal(String numero, Integer linea, String destino, String origen, String ruta,
                      String empresa, String marca, String modelo,
                      String placa, double lat, double lon, float rumbo, float velMs, long timestamp) {
        this.numero = numero;
        this.linea = linea;
        this.destino = destino;
        this.origen = origen;
        this.ruta = ruta;
        this.empresa = empresa;
        this.marca = marca;
        this.modelo = modelo;
        this.placa = placa;
        this.posicion = new LatLng(lat, lon);
        this.rumbo = rumbo;
        this.velMs = velMs;
        this.timestamp = timestamp;
    }

    /** "Marca Modelo" listo para mostrar, o "Desconocido". */
    public String marcaModelo() {
        return new Modelos.Ficha(empresa, marca, modelo).etiqueta();
    }

    /** true si la unidad no está asignada a una de las 7 líneas. */
    public boolean sinRuta() {
        return linea == null;
    }
}
