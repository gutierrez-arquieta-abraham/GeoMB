package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Prueba {@link Planificador#esperaExtra(int)} ANTES de reescribirlo con {@link LineaId} (hoy
 * usa rangos propios sobre el entero: >=200/>=121&&<=129/>=111/>=100). Valores calculados a mano
 * con la implementación ORIGINAL, para los 20 códigos reales del proyecto -- si tras el reemplazo
 * con LineaId algo cambia para cualquiera de estos, esta prueba lo detecta.
 */
public class PlanificadorEsperaExtraTest {

    @Test
    public void valores_por_codigo_real() {
        // Metrobús (1-7): 0.0 (sin penalización)
        for (int n = 1; n <= 7; n++) assertEquals("esperaExtra(" + n + ")", 0.0, Planificador.esperaExtra(n), 0);
        // Mexibús troncal ordinario (101-104): 120.0
        for (int n : new int[]{101, 102, 103, 104}) assertEquals("esperaExtra(" + n + ")", 120.0, Planificador.esperaExtra(n), 0);
        // Mexibús ramal (111-113): 1800.0
        for (int n : new int[]{111, 112, 113}) assertEquals("esperaExtra(" + n + ")", 1800.0, Planificador.esperaExtra(n), 0);
        // Mexibús exprés (121-124): 240.0
        for (int n : new int[]{121, 122, 123, 124}) assertEquals("esperaExtra(" + n + ")", 240.0, Planificador.esperaExtra(n), 0);
        // Mexicable (201-202): 60.0
        for (int n : new int[]{201, 202}) assertEquals("esperaExtra(" + n + ")", 60.0, Planificador.esperaExtra(n), 0);
    }
}
