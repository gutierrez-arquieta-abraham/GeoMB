package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado: el TTS leía "1ro de Mayo" (Mexibús L1/L1 Exprés) y "1° de Mayo" (L2) de
 * forma literal ("uno Ro de Mayo") en vez de "Primero de Mayo". {@link Locuciones#nom} es la réplica
 * pública de {@code respellVoz()} (debe mantenerse en sincronía con RecorridoService/DescargaVoz, ver
 * sus propios comentarios), así que se prueba contra la función real.
 */
public class LocucionesTest {

    @Test
    public void nom_1roDeMayo_seDiceComoPrimeroDeMayo() {
        assertEquals("Primero de Mayo", Locuciones.nom("MXB 1ro de Mayo"));
    }

    @Test
    public void nom_1gradoDeMayo_seDiceComoPrimeroDeMayo() {
        assertEquals("Primero de Mayo", Locuciones.nom("MXB 1° de Mayo"));
    }

    @Test
    public void nom_otraEstacion_noSeAltera() {
        assertEquals("Jardines de Morelos", Locuciones.nom("MXB Jardines de Morelos"));
    }
}
