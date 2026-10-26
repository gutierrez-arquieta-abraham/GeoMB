package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (viaje real, Mexibús L4 Periférico→Indios Verdes): sonaba "próxima:
 * Indios Verdes" DOS veces -- una al salir de Periférico (el bloque NORMAL de "próxima", correcto:
 * Indios Verdes es la parada inmediata siguiente y ya tiene correspondencia real con Metrobús) y
 * otra ya en la zona de cruce (el bloque de "correspondencia corta" en RecorridoService). Ambos
 * avisos apuntan a la MISMA estación física pero a DOS nodos de secuencia distintos (uno del lado
 * Mexibús, otro del lado Metrobús) -- "ultProxima != i" por sí solo no detectaba la coincidencia.
 * El fix usa coUbicada() (hecha package-private para esto) para reconocer ese caso. Se prueba
 * contra la función real con las coordenadas reales de Indios Verdes.
 */
public class RecorridoServiceProximaDuplicadaTest {

    private static Planificador.Parada parada(String nombre, int linea, com.google.android.gms.maps.model.LatLng pos) {
        return new Planificador.Parada(nombre, linea, 0, false, pos, null);
    }

    @Test
    public void coUbicada_indiosVerdesMexibusYMetrobus_sonLaMismaEstacion() {
        Planificador.Parada mexibus = parada("Indios Verdes (conexión Metrobús L1 y L7)", 104, Planificador.IV_L4_LLEGA);
        Planificador.Parada metrobus = parada("Indios Verdes", 1, Planificador.IV_L1_SALE);
        assertTrue(RecorridoService.coUbicada(mexibus, metrobus));
    }

    @Test
    public void coUbicada_estacionesDistintasLejanas_noSonLaMisma() {
        Planificador.Parada a = parada("MXB Periférico", 104,
                new com.google.android.gms.maps.model.LatLng(19.517536, -99.099233));
        Planificador.Parada metrobus = parada("Indios Verdes", 1, Planificador.IV_L1_SALE);
        assertFalse(RecorridoService.coUbicada(a, metrobus));
    }
}
