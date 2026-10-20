package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real CONFIRMADO con video (Bosques del Valle -> Instituto Politécnico Nacional, Mexibús L4
 * sur viniendo del norte + Metrobús L1 sur hacia Deportivo 18 de Marzo/Potrero): al ABORDAR Metrobús
 * L1 en Indios Verdes tras bajar de Mexibús L4, {@link Planificador#plataformaIndiosVerdes} siempre
 * devolvía el andén de ascenso NORTE (IV_L1_SALE), sin importar de qué lado de L4 vinieras. Si L4 te
 * dejó en su andén SUR (IV_L4_LLEGA), ese punto queda a ~276 m real de IV_L1_SALE -- más que el radio
 * normal de "llegando" de Mexibús (50 m) -- así que el aviso de llegada a Mexibús quedaba "atorado"
 * hasta acercarte al andén norte de L1. Se prueba contra la función real (hecha package-private para
 * esto, mismo trato que corrManual()/nucleoCoincide()/subRuta()).
 */
public class PlanificadorIndiosVerdesL1Test {

    private static final String NOMBRE_L1 = "Indios Verdes";
    private static final int L1 = 1;
    private static final int L4 = 104;
    private static final int L4_EXPRES = 124;

    @Test
    public void l1_abordaTrasL4Sur_usaAndenSur_noSiempreNorte() {
        // L4 ya resolvió su propio andén SUR (IV_L4_LLEGA, viniendo del norte -- caso real del video).
        // L1 sigue de largo (nextLin >= 0, misma base) hacia el sur: debe usar el andén SUR también,
        // no el norte de siempre.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4, L1, Planificador.IV_L4_LLEGA, null);
        assertEquals("debe usar el andén SUR de L1, igual que el de L4 (~40 m, no ~276 m)",
                Planificador.IV_L1_LLEGA, resultado);
    }

    @Test
    public void l1_abordaTrasL4SurExpres_usaAndenSur() {
        // Mismo caso, viniendo del exprés (124) en vez de ordinario (104) -- corrManualLineas() ya
        // colapsa ambos para la voz; aquí se confirma que el andén también se resuelve igual.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4_EXPRES, L1, Planificador.IV_L4_LLEGA, null);
        assertEquals(Planificador.IV_L1_LLEGA, resultado);
    }

    @Test
    public void l1_abordaTrasL4Norte_usaAndenNorte_comportamientoAnteriorIntacto() {
        // Si L4 te dejó en su andén NORTE (IV_L4_SALE), L1 debe seguir usando el andén NORTE (ya
        // eran ~40 m reales antes del fix -- no debe cambiar).
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L4, L1, Planificador.IV_L4_SALE, null);
        assertEquals(Planificador.IV_L1_SALE, resultado);
    }

    @Test
    public void l1_abordaSinTransbordoDeL4_usaAndenNorte_comportamientoAnteriorIntacto() {
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
        // sin cambios -- el fix solo toca el caso de ABORDAJE.
        LatLng resultado = Planificador.plataformaIndiosVerdes(
                L1, NOMBRE_L1, new LatLng(19.49678, -99.11932),
                L1, -1, null, null);
        assertEquals(Planificador.IV_L1_LLEGA, resultado);
    }
}
