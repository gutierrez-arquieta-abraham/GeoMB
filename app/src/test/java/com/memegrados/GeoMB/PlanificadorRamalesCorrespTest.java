package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (mismo reporte del usuario que "Las Américas"): RecorridoService y
 * Locuciones usan baseLinea(), no claveTerminal(), para decidir si dos líneas son "el mismo
 * servicio" al buscar correspondencias por nombre+cercanía (lineasEnEstacion()). baseLinea()
 * colapsa ordinario↔ramal además de ordinario↔exprés (101/111/121 dan los 3 "101"), así que esa
 * comparación se saltaba por error CUALQUIER ramal que comparte estación física con su ordinario
 * -- verificado con coordenadas reales (mexibus.json): "Ojo de Agua" L1↔L1A (84 m), "Las
 * Américas" L2↔L2A (52 m), "Acuitlapilco" L3↔L3A (34 m) son la MISMA estación en los 3 casos
 * (distinto de "Las Américas" en L1, a 1067 m de la de L2 -- esa sí es coincidencia de nombre sin
 * relación, y debe seguir SIN conectarse).
 *
 * El fix real (cambiar baseLinea()→claveTerminal() en lineasEnEstacion()/transferenciaTexto())
 * necesita Context (GtfsRepository), no es testeable directo sin Robolectric. Se prueba la
 * propiedad pura de la que depende: claveTerminal() debe distinguir cada ramal de su ordinario
 * (NO colapsarlos), a diferencia de baseLinea() que sí los colapsa -- es justo esa diferencia la
 * que arregla el defecto.
 */
public class PlanificadorRamalesCorrespTest {

    @Test
    public void claveTerminal_L1OrdinarioYRamalAIFA_sonDistintos() {
        assertNotEquals(Planificador.claveTerminal(101), Planificador.claveTerminal(111));
    }

    @Test
    public void claveTerminal_L2OrdinarioYRamalL2A_sonDistintos() {
        assertNotEquals(Planificador.claveTerminal(102), Planificador.claveTerminal(112));
    }

    @Test
    public void claveTerminal_L3OrdinarioYRamalL3A_sonDistintos() {
        assertNotEquals(Planificador.claveTerminal(103), Planificador.claveTerminal(113));
    }

    @Test
    public void claveTerminal_expresSigueColapsandoConOrdinario() {
        // El fix NO debe perder el colapso ordinario↔exprés que sí es correcto (121 es "el mismo
        // servicio hablado" que 101, solo más rápido -- no un ramal distinto).
        assertEquals(Planificador.claveTerminal(101), Planificador.claveTerminal(121));
        assertEquals(Planificador.claveTerminal(102), Planificador.claveTerminal(122));
        assertEquals(Planificador.claveTerminal(103), Planificador.claveTerminal(123));
    }
}
