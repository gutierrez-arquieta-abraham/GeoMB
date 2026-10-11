package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regresión del renombre "1° de Mayo" -> "1ro de Mayo" en Mexibús L1 (101) y L1 Exprés (121) --
 * SOLO esa estación, L2 (102) sigue llamándose "1° de Mayo". Prueba producción real:
 * {@code Planificador.corrManual()}/{@code excluidoCorresp()} (hechas package-private para esto,
 * mismo trato que {@code nucleoCoincide()}), que son el núcleo puro que el constructor del grafo
 * de correspondencias llama directo -- sin Context, así que no requieren Robolectric.
 */
public class PlanificadorCorresp1deMayoTest {

    private static String nn(String s) { return Planificador.norm("MXB " + s); }

    @Test
    public void corrManual_1roDeMayoL1_conLasAmericasL2_siCorresponde() {
        // La corrección real (288 m) sigue viva con el nombre NUEVO de L1.
        assertTrue(Planificador.corrManual(101, nn("1ro de Mayo"), 102, nn("Las Américas")));
        assertTrue(Planificador.corrManual(102, nn("Las Américas"), 101, nn("1ro de Mayo")));   // en cualquier orden
    }

    @Test
    public void corrManual_1roDeMayoL1_conLasAmericasL2A_siCorresponde() {
        assertTrue(Planificador.corrManual(101, nn("1ro de Mayo"), 112, nn("Las Américas")));
    }

    @Test
    public void corrManual_nombreViejo_yaNoCorresponde() {
        // El nombre ANTERIOR ("1 de Mayo", sin el "ro") ya no debe disparar la correspondencia:
        // si alguna vez se revierte el renombre sin actualizar CORRESP_MANUAL, esta prueba lo marca.
        assertFalse(Planificador.corrManual(101, nn("1 de Mayo"), 102, nn("Las Américas")));
    }

    @Test
    public void excluidoCorresp_1deMayo_yaNoEsSelfPairExcluido() {
        // El self-pair {"mxb 1 de mayo","mxb 1 de mayo"} se quitó de EXCL_CORRESP al renombrar L1: ya
        // no comparten nombre normalizado con la de L2, así que ninguna lógica de nombre+cercanía
        // los puede confundir, y la exclusión explícita quedó de sobra.
        assertFalse(Planificador.excluidoCorresp(nn("1ro de Mayo"), nn("1° de Mayo")));
        assertFalse(Planificador.excluidoCorresp(nn("1° de Mayo"), nn("1° de Mayo")));
    }
}
