package com.memegrados.GeoMB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Cierres por rango "A - B" en L4, que no sigue el orden de su catálogo (feed real del 13/11/2026). */
public class ManifestacionesL4RangoTest {

    @Test
    public void plazaDeLaRepublicaAVocacional5_soloRutaSur() {
        Set<String> r = ManifestacionesService.rangoPorServicios(4, "Plaza de la República - Vocacional 5");
        assertEquals(new LinkedHashSet<>(Arrays.asList(
                Planificador.norm("Plaza de la República"), Planificador.norm("Amajac"),
                Planificador.norm("Defensoría Pública"), Planificador.norm("Vocacional 5"))), r);
    }

    @Test
    public void ambosSentidos_noEsCierreDeUnSoloSentido() {
        String txt = Planificador.norm("Intervención en la estación Desvío en Ruta Sur, sin servicio de "
                + "Plaza de la República a Vocacional 5 ambos sentidos Plaza de la República - Vocacional 5");
        assertNull(ManifestacionesService.terminalEnTexto(4, txt));
    }

    @Test
    public void bellasArtesDireccionPantitlan_esUnSoloSentido() {
        String txt = Planificador.norm("Intervención en la estación Sin servicio por mantenimiento al carril "
                + "confinado en dirección a Pantitlán Bellas Artes");
        assertEquals(Planificador.norm("Pantitlán"), ManifestacionesService.terminalEnTexto(4, txt));
    }
}
