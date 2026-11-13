package com.memegrados.GeoMB;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Número de línea que dice la voz en las listas de correspondencia ("Mexibús Líneas 2 y 2A"). */
public class LocucionesNumLineaTest {

    @Test
    public void ramalesMexibusSeDicenConA() {
        assertEquals("1A", Locuciones.numLinea(111));
        assertEquals("2A", Locuciones.numLinea(112));   // antes "12": "correspondencia con Línea 12"
        assertEquals("3A", Locuciones.numLinea(113));
    }

    @Test
    public void troncalesMetrobusYMexicableSinCambio() {
        assertEquals("2", Locuciones.numLinea(102));
        assertEquals("4", Locuciones.numLinea(104));
        assertEquals("7", Locuciones.numLinea(7));
        assertEquals("2", Locuciones.numLinea(202));
    }
}
