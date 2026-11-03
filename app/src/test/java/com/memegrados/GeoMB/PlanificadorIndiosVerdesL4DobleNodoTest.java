package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Caso nuevo desde que mexibus.json (líneas 104/124) pasó de UN punto promedio de "Indios Verdes" a
 * DOS puntos reales consecutivos (IV_L4_LLEGA, IV_L4_SALE -- ver commit que separó el nodo). Esto
 * rompe la heurística norte/sur de {@code plataformaIndiosVerdes()}: fue escrita para un único stop
 * "Indios Verdes" por recorrido, rodeado de vecinos de OTRO nombre; ahora el vecino inmediato de cada
 * uno de los 2 stops también se llama "Indios Verdes", así que la comparación de latitudes compara
 * Indios-Verdes-contra-Indios-Verdes en vez de contra la siguiente estación real (La Raza/Periférico).
 *
 * Reproducido con datos reales de mexibus.json línea 104: secuencia
 * La Raza(19.468052) -> IV_L4_LLEGA(19.493988) -> IV_L4_SALE(19.496371) -> Periférico(19.517536).
 * prevPos siempre es la posición YA RESUELTA (ver buscarYConstruir(), 'secuencia'), no la cruda --
 * se simula igual aquí, encadenando el resultado del stop anterior como prevPos del siguiente.
 */
public class PlanificadorIndiosVerdesL4DobleNodoTest {

    private static final String NOMBRE = "Indios Verdes (conexión Metrobús L1 y L7)";
    private static final int L4 = 104;
    private static final LatLng LA_RAZA = new LatLng(19.468052, -99.139529);
    private static final LatLng PERIFERICO = new LatLng(19.517536, -99.099233);

    @Test
    public void sentidoNorte_cadaStopConservaSuPropioAnden() {
        // La Raza -> IV_L4_LLEGA -> IV_L4_SALE -> Periférico (dirección real L104>, hacia UMB Tecámac).
        LatLng pos1 = Planificador.plataformaIndiosVerdes(
                L4, NOMBRE, Planificador.IV_L4_LLEGA, L4, L4, LA_RAZA, Planificador.IV_L4_SALE);
        LatLng pos2 = Planificador.plataformaIndiosVerdes(
                L4, NOMBRE, Planificador.IV_L4_SALE, L4, L4, pos1, PERIFERICO);

        assertEquals("el 1er stop (índice de llegada sur) debe quedar en el andén SUR",
                Planificador.IV_L4_LLEGA, pos1);
        assertEquals("el 2do stop (índice de salida norte) debe quedar en el andén NORTE",
                Planificador.IV_L4_SALE, pos2);
        assertNotEquals("los 2 stops consecutivos no deben colapsar al mismo punto", pos1, pos2);
    }

    @Test
    public void sentidoSur_cadaStopConservaSuPropioAnden() {
        // Periférico -> IV_L4_SALE -> IV_L4_LLEGA -> La Raza (dirección real L104<, hacia La Raza).
        LatLng pos1 = Planificador.plataformaIndiosVerdes(
                L4, NOMBRE, Planificador.IV_L4_SALE, L4, L4, PERIFERICO, Planificador.IV_L4_LLEGA);
        LatLng pos2 = Planificador.plataformaIndiosVerdes(
                L4, NOMBRE, Planificador.IV_L4_LLEGA, L4, L4, pos1, LA_RAZA);

        assertEquals("el 1er stop (índice de llegada norte) debe quedar en el andén NORTE",
                Planificador.IV_L4_SALE, pos1);
        assertEquals("el 2do stop (índice de salida sur) debe quedar en el andén SUR",
                Planificador.IV_L4_LLEGA, pos2);
        assertNotEquals("los 2 stops consecutivos no deben colapsar al mismo punto", pos1, pos2);
    }
}
