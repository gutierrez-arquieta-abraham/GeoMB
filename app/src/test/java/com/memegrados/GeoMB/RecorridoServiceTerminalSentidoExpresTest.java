package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (reporte del usuario): un viaje "Bosques del Valle" -> "Instituto
 * Politécnico Nacional" usando Mexibús L2 Exprés (122) anunciaba la terminal "Puente de Fierro" en
 * vez de la real ("Ecatepec"/"Las Américas" según el sentido). Causa raíz en
 * {@code RecorridoService.terminalSentido()}: el tramo puede extenderse por un cambio
 * exprés→ordinario SIN transbordo real (mismo {@code baseLinea()}, ver su bucle de 'fin'), pero
 * los índices ia/ib se buscaban en el catálogo de ESTACIONES del servicio abordado (122) -- un
 * subconjunto reducido (confirmado en {@code assets/mexibus.json}: termina en "MXB Ecatepec"/
 * "MXB Lechería", nunca incluye "MXB Puente de Fierro") -- así que {@code Horarios.idxEnLinea()}
 * fallaba y la función caía al respaldo crudo (el nombre de la última parada del tramo, cualquiera
 * que fuera, en este caso donde el A* ya sigue en ordinario). El fix resuelve ia/ib contra el
 * ORDINARIO del mismo {@code Planificador.claveTerminal()} (que, a diferencia de
 * {@code baseLinea()}, NO colapsa ramales -- solo ordinario↔exprés, ver su propio comentario),
 * cuyo catálogo SIEMPRE contiene cada parada del exprés (mismo corredor/orden).
 *
 * {@code terminalSentido()} es privado y usa Context (GtfsRepository.porNumero); además,
 * construir un {@code Linea} real (para probar {@code Horarios.idxEnLinea()} con catálogos de
 * prueba) requiere {@code android.graphics.Color.parseColor()}, que los tests unitarios de este
 * proyecto (sin Robolectric) no mockean -- lanza RuntimeException. Por eso se prueba aquí solo la
 * pieza pura y Context-free que es la CLAVE del fix: {@code Planificador.claveTerminal()} (decide
 * contra qué línea se resuelve el sentido) y {@code Planificador.terminales()} (confirma que
 * "Puente de Fierro" nunca pudo venir de la tabla canónica, solo del respaldo crudo que el fix
 * evita).
 */
public class RecorridoServiceTerminalSentidoExpresTest {

    @Test
    public void claveTerminal_l2Expres_colapsaAOrdinario() {
        // La clave del fix: a diferencia de baseLinea() (que también colapsaría un ramal, p. ej.
        // 111 L1A -> 101 L1, una línea físicamente distinta -- bug ya corregido esta sesión en otro
        // lado), claveTerminal() solo colapsa ordinario<->exprés.
        assertEquals(102, Planificador.claveTerminal(122));
    }

    @Test
    public void claveTerminal_ramalNoSeColapsaComoBaseLineaSiLoHaria() {
        // Contraste explícito: si el fix usara baseLinea() en vez de claveTerminal(), un viaje en
        // L1A (111, físicamente distinto de L1 101) resolvería el sentido contra el catálogo
        // EQUIVOCADO (L1 ordinario, que no tiene las paradas de L1A). claveTerminal() lo deja
        // intacto.
        assertEquals(111, Planificador.claveTerminal(111));
        assertNotEquals(Planificador.claveTerminal(111), 101);
    }

    @Test
    public void terminales_l2Expres_noIncluyePuenteDeFierro() {
        // La tabla canónica de 122 (el texto que SÍ debe anunciarse una vez resuelto el sentido)
        // nunca es "Puente de Fierro" -- confirma que el nombre erróneo que salía antes venía del
        // respaldo crudo (fin de tramo), no de esta tabla.
        String[] canon = Planificador.terminales(122);
        assertNotNull(canon);
        for (String t : canon) assertFalse(t.toLowerCase().contains("puente de fierro"));
    }

    @Test
    public void terminales_l2Ordinario_incluyeLasAmericas() {
        // El otro extremo del reporte del usuario ("debería decir Las Américas"): sí está en la
        // tabla canónica del ordinario (102), la que el fix ahora sí logra alcanzar.
        String[] canon = Planificador.terminales(102);
        assertNotNull(canon);
        assertTrue(java.util.Arrays.asList(canon).stream().anyMatch(t -> t.toLowerCase().contains("américas")));
    }
}
