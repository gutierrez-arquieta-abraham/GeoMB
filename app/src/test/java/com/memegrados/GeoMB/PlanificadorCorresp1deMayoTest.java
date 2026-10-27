package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regresión del renombre "1° de Mayo" -> "1ro de Mayo" en Mexibús L1 (101) y L1 Exprés (121) --
 * SOLO esa estación, L2 (102) sigue llamándose "1° de Mayo". Prueba producción real:
 * {@code Planificador.corrManual()}/{@code excluidoCorresp()} (hechas package-private para esto,
 * mismo trato que {@code nucleoCoincide()}), que son el núcleo puro que el constructor del grafo
 * de correspondencias llama directo -- sin Context, así que no requieren Robolectric.
 */
public class PlanificadorCorresp1deMayoTest {

    private static String nn(String s) { return Planificador.norm("MXB " + s); }

    @Test
    public void corrManual_1roDeMayoL1_conLasAmericasL2_siCorresponde() {
        // La corrección real (288 m) sigue viva con el nombre NUEVO de L1.
        assertTrue(Planificador.corrManual(101, nn("1ro de Mayo"), 102, nn("Las Américas")));
        assertTrue(Planificador.corrManual(102, nn("Las Américas"), 101, nn("1ro de Mayo")));   // en cualquier orden
    }

    @Test
    public void corrManual_1roDeMayoL1_conLasAmericasL2A_siCorresponde() {
        assertTrue(Planificador.corrManual(101, nn("1ro de Mayo"), 112, nn("Las Américas")));
    }

    @Test
    public void corrManual_1roDeMayoL1Expres_conLasAmericasL2_siCorresponde() {
        // El mismo cruce, visto desde el EXPRÉS (121, no 101): sin esta fila, quien llega a "1ro de
        // Mayo" en TR3/TR4 no encontraba ningún cruce hacia L2 ahí -- el A* lo obligaba a bajarse a
        // ordinario varias paradas antes (desde Vocacional 3) para poder cruzar. Confirmado con un caso
        // real: Ciudad Azteca -> Eje 8 elegía exprés hasta Vocacional 3 y de ahí ordinario, aunque el
        // exprés sí llega hasta 1ro de Mayo.
        assertTrue(Planificador.corrManual(121, nn("1ro de Mayo"), 102, nn("Las Américas")));
        assertTrue(Planificador.corrManual(102, nn("Las Américas"), 121, nn("1ro de Mayo")));   // en cualquier orden
    }

    @Test
    public void corrManual_1roDeMayoL1Expres_conLasAmericasL2A_siCorresponde() {
        assertTrue(Planificador.corrManual(121, nn("1ro de Mayo"), 112, nn("Las Américas")));
    }

    @Test
    public void corrManual_nombreViejo_yaNoCorresponde() {
        // El nombre ANTERIOR ("1 de Mayo", sin el "ro") ya no debe disparar la correspondencia:
        // si alguna vez se revierte el renombre sin actualizar CORRESP_MANUAL, esta prueba lo marca.
        assertFalse(Planificador.corrManual(101, nn("1 de Mayo"), 102, nn("Las Américas")));
    }

    @Test
    public void excluidoCorresp_1deMayo_yaNoEsSelfPairExcluido() {
        // El self-pair {"mxb 1 de mayo","mxb 1 de mayo"} se quitó de EXCL_CORRESP al renombrar L1: ya
        // no comparten nombre normalizado con la de L2, así que ninguna lógica de nombre+cercanía
        // los puede confundir, y la exclusión explícita quedó de sobra.
        assertFalse(Planificador.excluidoCorresp(nn("1ro de Mayo"), nn("1° de Mayo")));
        assertFalse(Planificador.excluidoCorresp(nn("1° de Mayo"), nn("1° de Mayo")));
    }

    /**
     * Defecto real CONFIRMADO (distinto del de arriba): {@code RecorridoService.lineasEnEstacion()} --
     * la función que arma el texto "correspondencia con línea X" para la VOZ de llegada/próxima -- NUNCA
     * consulta {@code Planificador.CORRESP_MANUAL}/{@code corrManual()}. Solo encuentra correspondencias
     * por NOMBRE (igual o núcleo coincidente) + cercanía (≤600 m). El A* SÍ puede trazar/transbordar en
     * 1ro de Mayo↔Las Américas (ver corrManual_* arriba), pero como sus nombres NO comparten ninguna
     * palabra, nucleoCoincide() -- el mismo chequeo de nombre que usa lineasEnEstacion() -- nunca los
     * relaciona: el aviso de llegada a "1ro de Mayo" queda SIN nombrar "línea 2"/"línea 2A" aunque la
     * ruta sí transborde ahí (el "favor de irte preparando" genérico si suena, porque ese depende solo
     * del flag Parada.transbordo, no de esta función -- ver vozProxima()/RecorridoService.java:1201-1207).
     */
    @Test
    public void nucleoCoincide_1roDeMayo_lasAmericas_noCoincide_confirmaElHuecoDeLaVoz() {
        assertFalse("nombres sin palabra en común: lineasEnEstacion() nunca las relaciona por nombre",
                Planificador.nucleoCoincide("MXB 1ro de Mayo", "MXB Las Américas"));
    }

    // Corrección del hueco: Planificador.corrManualLineas() es lo que RecorridoService.lineasEnEstacion()
    // ahora consulta además de nombre+cercanía (ver su comentario).
    @Test
    public void corrManualLineas_1roDeMayoL1_devuelveL2yL2A() {
        java.util.Set<Integer> l = Planificador.corrManualLineas(101, nn("1ro de Mayo"));
        assertTrue("debe incluir L2 (102)", l.contains(102));
        assertTrue("debe incluir L2A (112), SIN colapsar a 102 -- para poder decir 'línea 2A'", l.contains(112));
        assertEquals(2, l.size());
    }

    @Test
    public void corrManualLineas_1roDeMayoL1Expres_mismoResultadoQueOrdinario() {
        // 121 (exprés) tiene sus propias filas en CORRESP_MANUAL -- mismo resultado que 101 (ordinario).
        java.util.Set<Integer> l = Planificador.corrManualLineas(121, nn("1ro de Mayo"));
        assertTrue(l.contains(102));
        assertTrue(l.contains(112));
    }

    @Test
    public void corrManualLineas_lasAmericasL2_devuelveL1YL2A() {
        // Desde Las Américas (L2), un lado es L1 (101) -- tanto si el A* cruzó con el ordinario
        // (101) como con el exprés (121) de 1ro de Mayo, se colapsan al mismo "línea 1" (claveTerminal).
        // Defecto real confirmado (reporte del usuario): el OTRO lado es L2A (112) -- misma terminal
        // física, mismo nombre exacto, pero lineasEnEstacion() se salta las líneas con el mismo
        // baseLinea() que la actual (102 y 112 colapsan ahí por error), así que nunca se comparaban
        // entre sí. Fila manual nueva en CORRESP_MANUAL (102↔112, ver su comentario).
        java.util.Set<Integer> l = Planificador.corrManualLineas(102, nn("Las Américas"));
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(101, 112)), l);
    }

    @Test
    public void corrManualLineas_lasAmericasL2A_devuelveL1YL2Ordinario() {
        java.util.Set<Integer> l = Planificador.corrManualLineas(112, nn("Las Américas"));
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(101, 102)), l);
    }

    @Test
    public void corrManualLineas_estacionSinCorrespondenciaManual_vacio() {
        assertTrue(Planificador.corrManualLineas(101, nn("Hospital")).isEmpty());
    }
}
