package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Helpers nuevos de carpeta de voz (ver RecorridoService.archivoVoz()/DescargaVoz.archivoVoz()):
 * {@link Sistemas#carpeta} (nombre de carpeta del sistema) y {@link Sistemas#numeroDeLinea}
 * (número de línea "limpio", colapsando ordinario/ramal/exprés de Mexibús/Mexicable al mismo
 * número por su último dígito -- igual que el baseLinea() interno de cada clase).
 */
public class SistemasTest {

    @Test
    public void carpeta_metrobus() {
        assertEquals("Metrobus", Sistemas.carpeta(Sistemas.METROBUS));
    }

    @Test
    public void carpeta_mexibus() {
        assertEquals("Mexibus", Sistemas.carpeta(Sistemas.MEXIBUS));
    }

    @Test
    public void carpeta_mexicable() {
        assertEquals("Mexicable", Sistemas.carpeta(Sistemas.MEXICABLE));
    }

    @Test
    public void sistemaDe_todos_los_rangos() {
        for (int n = 1; n <= 7; n++) assertEquals("sistemaDe(" + n + ")", Sistemas.METROBUS, Sistemas.sistemaDe(n));
        for (int n : new int[]{101, 102, 103, 104, 111, 112, 113, 121, 122, 123, 124})
            assertEquals("sistemaDe(" + n + ")", Sistemas.MEXIBUS, Sistemas.sistemaDe(n));
        for (int n : new int[]{201, 202}) assertEquals("sistemaDe(" + n + ")", Sistemas.MEXICABLE, Sistemas.sistemaDe(n));
    }

    @Test
    public void numeroDeLinea_metrobus_directo() {
        for (int n = 1; n <= 7; n++) assertEquals(n, Sistemas.numeroDeLinea(n));
    }

    @Test
    public void numeroDeLinea_mexibus_colapsaOrdinarioRamalExpres() {
        // L1 Mexibús: ordinario 101, ramal 111, exprés 121 -> mismo número de línea (1).
        assertEquals(1, Sistemas.numeroDeLinea(101));
        assertEquals(1, Sistemas.numeroDeLinea(111));
        assertEquals(1, Sistemas.numeroDeLinea(121));
        // L4: ordinario 104, exprés 124.
        assertEquals(4, Sistemas.numeroDeLinea(104));
        assertEquals(4, Sistemas.numeroDeLinea(124));
    }

    @Test
    public void numeroDeLinea_mexicable() {
        assertEquals(1, Sistemas.numeroDeLinea(201));
        assertEquals(2, Sistemas.numeroDeLinea(202));
    }
}
