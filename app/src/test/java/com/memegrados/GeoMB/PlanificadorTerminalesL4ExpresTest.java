package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado: Planificador.terminales(124) (L4 Exprés) tenía "Indios Verdes" como
 * terminal SUR (canon[0], dirección de llegada) -- el usuario confirmó que La Raza SÍ funciona como
 * terminal de LLEGADA del exprés (igual que el ordinario, case 104), aunque el exprés no tenga
 * SALIDA (canon[1]=UMB, sin cambios) desde La Raza como sí la tiene el ordinario. Solo canon[0]
 * cambia; esa asimetría de salida no lo afecta. Se prueba contra la función real.
 */
public class PlanificadorTerminalesL4ExpresTest {

    @Test
    public void terminalesL4Expres_sur_esLaRaza_noIndiosVerdes() {
        String[] t = Planificador.terminales(124);
        assertEquals("MXB La Raza", t[0]);
    }

    @Test
    public void terminalesL4Expres_norte_sigueSiendoUMB_sinCambios() {
        String[] t = Planificador.terminales(124);
        assertEquals("MXB Universidad Mexiquense del Bicentenario", t[1]);
    }

    @Test
    public void terminalesL4Ordinario_igualQueExpres_sinCambios() {
        // El ordinario (104) ya tenía "MXB La Raza" correcto; confirma que no se tocó por error.
        String[] t = Planificador.terminales(104);
        assertEquals("MXB La Raza", t[0]);
        assertEquals("MXB Universidad Mexiquense del Bicentenario", t[1]);
    }
}
