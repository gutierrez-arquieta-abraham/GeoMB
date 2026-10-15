package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real #1 (ya corregido en una ronda anterior): al hacer un recorrido L4 Express -> L2
 * Mexibús cruzando por "Puente de Fierro", RecorridoService se quedaba mudo (ni "llegando" ni
 * "transbordo") y el seguimiento terminaba saltando directo a "UPE" vía reanclarOtraLinea(),
 * saltándose Puente de Fierro Y Casa de Morelos. Causa raíz: el punto de Puente de Fierro L2 (284 m
 * real del de L4, misma estación por nombre, dos plataformas físicas distintas) no tenía radio
 * amplio registrado, así que RecorridoService.radioCerca() usaba el radio angosto por defecto
 * (50 m) en vez del amplio de andén largo (90 m) para ese lado del cruce.
 *
 * <p>Defecto real #2 (el fix de arriba, aplicado sin distinguir el caso, causó este otro): el mismo
 * radio amplio de 90 m, al aplicarse TAMBIÉN al aviso normal de "llegando" viajando dentro de L2
 * (sin cruce de línea de por medio), hacía sonar el aviso mucho antes de tiempo en el tramo real
 * Casa de Morelos -> Puente de Fierro L2 (solo ~247 m entre sí) -- el propio andén de Puente de
 * Fierro L2 NO es largo (es una parada L2 normal, ~50 m), el radio amplio solo hacía falta para el
 * cruce L4->L2. Por eso Puente de Fierro L2 se sacó de {@link Planificador#andenLargo} (usada por el
 * aviso normal) y se agregó {@link Planificador#andenLargoOCruceL2} (para el umbral del cruce de
 * línea, que sí debe seguir cubriéndolo).
 *
 * <p>Ambas funciones son públicas y estáticas (no tocan Context/Android), así que se prueban
 * directamente contra la función real, sin reproducir su lógica a mano.
 */
public class PlanificadorAndenLargoTest {

    private static Planificador.Parada paradaEn(double lat, double lon) {
        return new Planificador.Parada("Puente de Fierro", 102, 0xFFFFFFFF, false, new LatLng(lat, lon), "");
    }

    @Test
    public void andenLargo_puenteDeFierroL4_siCalifica() {
        assertTrue("el punto de L4 (plataforma genuinamente larga) debe calificar siempre",
                Planificador.andenLargo(paradaEn(19.603558838994957, -99.033337377486620)));
    }

    @Test
    public void andenLargo_puenteDeFierroL2_yaNoCalifica() {
        // Defecto real #2 (ver arriba): su propio andén es normal (~50 m) -- el radio amplio solo
        // hace falta para el CRUCE de línea ahí, no para el aviso normal de "llegando" dentro de L2.
        assertFalse("el punto de L2 NO debe calificar para el aviso normal de 'llegando'",
                Planificador.andenLargo(paradaEn(19.601038, -99.033787)));
    }

    @Test
    public void andenLargo_casaDeMorelos_noCalifica() {
        // Control: Casa de Morelos (una parada real distinta, cercana pero NO el mismo andén) no debe
        // calificar como andén largo -- confirma que el radio de 30 m no se infló de más.
        assertFalse(Planificador.andenLargo(paradaEn(19.599582, -99.035558)));
    }

    @Test
    public void andenLargo_upe_noCalifica() {
        assertFalse(Planificador.andenLargo(paradaEn(19.601909, -99.041841)));
    }

    @Test
    public void andenLargoOCruceL2_puenteDeFierroL4_siCalifica() {
        assertTrue(Planificador.andenLargoOCruceL2(paradaEn(19.603558838994957, -99.033337377486620)));
    }

    @Test
    public void andenLargoOCruceL2_puenteDeFierroL2_siCalifica() {
        // Causa raíz del defecto real #1 (ver arriba): el umbral del CRUCE L4->L2 sí debe seguir
        // usando el radio amplio en este punto.
        assertTrue("el punto de L2 debe calificar para el umbral de CRUCE de línea",
                Planificador.andenLargoOCruceL2(paradaEn(19.601038, -99.033787)));
    }

    @Test
    public void andenLargoOCruceL2_casaDeMorelos_noCalifica() {
        assertFalse(Planificador.andenLargoOCruceL2(paradaEn(19.599582, -99.035558)));
    }

    @Test
    public void andenLargoOCruceL2_upe_noCalifica() {
        assertFalse(Planificador.andenLargoOCruceL2(paradaEn(19.601909, -99.041841)));
    }
}
