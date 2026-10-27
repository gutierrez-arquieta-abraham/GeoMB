package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (reporte del usuario, descargas de audio de Las Américas / Mexibús L2):
 * Locuciones.basesCorresp() es una réplica AISLADA de RecorridoService.lineasEnEstacion() (ver su
 * propio comentario de clase: "debe mantenerse en sincronía con RecorridoService"), pero nunca
 * replicó la llamada a Planificador.corrManualLineas() -- la tabla de correspondencias MANUALES
 * para pares de nombres que no comparten ninguna palabra (p. ej. "1ro de Mayo"↔"Las Américas",
 * 288 m). Sin eso, el audio PRE-DESCARGADO de "Las Américas" nunca mencionaba la correspondencia
 * con la L1, aunque sí se anunciaba correctamente durante la navegación en vivo.
 *
 * basesCorresp() en sí necesita Context (GtfsRepository + Modos), así que no es testeable
 * directamente sin Robolectric -- se prueba la pieza real que faltaba (corrManualLineas/norm,
 * funciones estáticas puras), con los mismos valores EXACTOS que Locuciones.basesCorresp() le pasa
 * ahora tras el fix.
 */
public class LocucionesBasesCorrespTest {

    @Test
    public void corrManualLineas_lasAmericasOrdinarioL2_incluyeLinea1() {
        java.util.Set<Integer> r = Planificador.corrManualLineas(102, Planificador.norm("MXB Las Américas"));
        assertTrue("debería incluir 101 (1ro de Mayo)", r.contains(101));
    }

    @Test
    public void corrManualLineas_lasAmericasRamalL2A_incluyeLinea1() {
        java.util.Set<Integer> r = Planificador.corrManualLineas(112, Planificador.norm("MXB Las Américas"));
        assertTrue("debería incluir 101 (1ro de Mayo)", r.contains(101));
    }

    @Test
    public void corrManualLineas_1roDeMayoL1_incluyeLasAmericas() {
        java.util.Set<Integer> r = Planificador.corrManualLineas(101, Planificador.norm("MXB 1ro de Mayo"));
        assertFalse("debería incluir 102 y/o 112 (Las Américas)", r.isEmpty());
    }

    // Segundo defecto real confirmado (mismo reporte): faltaba la mención de L2A (112) al llegar a
    // "Las Américas" por L2 ordinario (102) -- misma terminal física, mismo nombre exacto, pero
    // lineasEnEstacion() se salta las líneas con el mismo baseLinea() (102 y 112 colapsan ahí por
    // error). Fila manual nueva en CORRESP_MANUAL (102↔112, mismo nombre en ambos lados).

    @Test
    public void corrManualLineas_lasAmericasOrdinarioL2_incluyeL2A() {
        java.util.Set<Integer> r = Planificador.corrManualLineas(102, Planificador.norm("MXB Las Américas"));
        assertTrue("debería incluir 112 (L2A)", r.contains(112));
    }

    @Test
    public void corrManualLineas_lasAmericasRamalL2A_incluyeL2Ordinario() {
        java.util.Set<Integer> r = Planificador.corrManualLineas(112, Planificador.norm("MXB Las Américas"));
        assertTrue("debería incluir 102 (L2 ordinario)", r.contains(102));
    }
}
