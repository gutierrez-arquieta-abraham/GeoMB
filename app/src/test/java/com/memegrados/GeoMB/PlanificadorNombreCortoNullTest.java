package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real (reporte del usuario): el planificador del estilo CITI cerraba la app al trazar.
 * Causa: un tramo sin terminal identificada (Instruccion.terminal == null) llegaba a
 * {@code Planificador.nombreMostrar()}, y {@code nombreCorto(null)} hacía {@code null.indexOf(...)}.
 * {@code nombreCorto} y {@code sinMxb} deben tolerar null sin lanzar.
 */
public class PlanificadorNombreCortoNullTest {

    @Test
    public void nombreCortoConNullNoTruena() {
        assertNull(Planificador.nombreCorto(null));
        assertNull(Planificador.sinMxb(Planificador.nombreCorto(null)));
    }

    @Test
    public void nombreCortoQuitaLaDireccionH72() {
        assertEquals("De la Salle", Planificador.nombreCorto("De la Salle · dirección Alameda Tacubaya"));
        assertEquals("Insurgentes", Planificador.nombreCorto("Insurgentes"));
    }

    @Test
    public void nombreMostrarSinContextoConNullNoTruena() {
        assertNull(Planificador.nombreMostrar(null, null, 4));
    }
}
