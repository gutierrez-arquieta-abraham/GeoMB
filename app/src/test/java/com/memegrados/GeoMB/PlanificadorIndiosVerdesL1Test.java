package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * REVERTIDO: hubo un fix previo (a partir de un video, Bosques del Valle -> IPN) que hacía que, al
 * ABORDAR Metrobús L1 tras bajar de Mexibús L4-sur, se usara el andén SUR de L1 (IV_L1_LLEGA) en vez
 * del norte de siempre (IV_L1_SALE). El usuario lo corrigió con coordenadas reales: el abordaje de L1
 * en ese caso SIGUE siendo por el andén NORTE -- lo que de verdad hacía falta era corregir la
 * coordenada de IV_L1_SALE (19.496963,-99.119262 en vez de 19.496411,-99.119465), no cambiar de andén.
 * Se prueba contra la función real (package-private para esto, mismo trato que
 * corrManual()/nucleoCoincide()/subRuta()).
 */
public class PlanificadorIndiosVerdesL1Test {

    private static final String NOMBRE_L1 = "Indios Verdes";
    private static final int L1 = 1;
    private static final int L4 = 104;
    private static final int L4_EXPRES = 124;

    @Test
    public void l1_abordaTrasL4Sur_usaAndenNorte_comportamientoCorrecto() {
        // L4 ya resolvió su propio andén SUR (IV_L4_LLEGA, viniendo del norte). El abordaje de L1
        // sigue siendo por el andén NORTE (IV_L1_SALE) -- el fix anterior que usaba el andén sur aquí
        // quedó revertido.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4, L1, Planificador.IV_L4_LLEGA, null);
        assertEquals(Planificador.IV_L1_SALE, resultado);
    }

    @Test
    public void l1_abordaTrasL4SurExpres_usaAndenNorte() {
        // Mismo caso, viniendo del exprés (124) en vez de ordinario (104).
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4_EXPRES, L1, Planificador.IV_L4_LLEGA, null);
        assertEquals(Planificador.IV_L1_SALE, resultado);
    }

    @Test
    public void l1_abordaTrasL4Norte_usaAndenNorte_sinCambios() {
        // Si L4 te dejó en su andén NORTE (IV_L4_SALE), L1 sigue usando el andén NORTE -- sin cambios.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4, L1, Planificador.IV_L4_SALE, null);
        assertEquals(Planificador.IV_L1_SALE, resultado);
    }

    @Test
    public void l1_abordaSinTransbordoDeL4_usaAndenNorte_sinCambios() {
        // Abordaje normal (p. ej. desde el inicio del recorrido, sin venir de L4): sigue usando el
        // andén de ascenso NORTE por defecto, igual que siempre.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                -1, L1, null, null);
        assertEquals(Planificador.IV_L1_SALE, resultado);
    }

    @Test
    public void l1_bajaEnIndiosVerdes_usaAndenSur_sinCambios() {
        // Terminando el viaje en L1 (desciendes, no abordas): sigue usando el andén de descenso SUR,
        // sin cambios -- esto nunca estuvo en duda, solo el caso de ABORDAJE.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L1, -1, null, null);
        assertEquals(Planificador.IV_L1_LLEGA, resultado);
    }

    @Test
    public void ivL1Sale_coordenadaCorregida() {
        assertEquals(19.496963, Planificador.IV_L1_SALE.latitude, 0.0000005);
        assertEquals(-99.119262, Planificador.IV_L1_SALE.longitude, 0.0000005);
    }
}
