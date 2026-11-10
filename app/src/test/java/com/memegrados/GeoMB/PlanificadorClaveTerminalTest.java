package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Prueba {@link Planificador#claveTerminal(int)}: exprés (12X) mapea a su troncal (10X); ramal
 * (11X), ordinario (10X), Metrobús (1-7) y Mexicable (20X) se quedan igual. Valores calculados a
 * mano con la implementación ORIGINAL para los 20 códigos reales -- detecta cualquier cambio si
 * se reescribe con LineaId.
 */
public class PlanificadorClaveTerminalTest {

    @Test
    public void valores_por_codigo_real() {
        for (int n = 1; n <= 7; n++) assertEquals("claveTerminal(" + n + ")", n, Planificador.claveTerminal(n));
        for (int n : new int[]{101, 102, 103, 104}) assertEquals("claveTerminal(" + n + ")", n, Planificador.claveTerminal(n));
        for (int n : new int[]{111, 112, 113}) assertEquals("claveTerminal(" + n + ")", n, Planificador.claveTerminal(n));
        assertEquals(101, Planificador.claveTerminal(121));
        assertEquals(102, Planificador.claveTerminal(122));
        assertEquals(103, Planificador.claveTerminal(123));
        assertEquals(104, Planificador.claveTerminal(124));
        for (int n : new int[]{201, 202}) assertEquals("claveTerminal(" + n + ")", n, Planificador.claveTerminal(n));
    }
}
