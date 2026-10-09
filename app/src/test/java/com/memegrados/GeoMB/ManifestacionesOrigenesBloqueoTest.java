package com.memegrados.GeoMB;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pruebas REALES (JUnit sobre el JVM del host) de {@link Manifestaciones#origenesBloqueo} y de la
 * separación entre afectación real, simulación y las demás fuentes que REALMENTE forman parte de
 * {@link Manifestaciones#bloqueadas()}. No usa Context/Android: Manifestaciones.java es una clase
 * de datos en memoria, sin dependencias de Android.
 *
 * <p>Manifestaciones guarda estado ESTÁTICO COMPARTIDO por todo el proceso de pruebas: cada test
 * limpia ese estado antes y después (ver {@link #limpiarTodo}) para no contaminar otras pruebas.
 */
public class ManifestacionesOrigenesBloqueoTest {

    private static final int LINEA = 1;
    private static final String ESTACION = "estacion de prueba";
    private static final String TERMINAL_ESPECIFICA = "terminal de prueba";

    private static void limpiarTodo() {
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");
        Manifestaciones.limpiarSimulado();
        Manifestaciones.setMexibusBloqueadas(Collections.<String>emptySet());
    }

    @Before
    public void antes() { limpiarTodo(); }

    @After
    public void despues() { limpiarTodo(); }

    private static String clave() {
        return Planificador.claveTerminal(LINEA) + "|" + Planificador.norm(ESTACION);
    }

    // 1) Afectación oficial (Estado del Servicio) -> origen "afectada", y coincide con bloqueadas().
    @Test
    public void afectacionOficial_marcaOrigenAfectadaYCoincideConBloqueadas() {
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(clave())),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION));
        assertEquals(Collections.singleton(Manifestaciones.ORIGEN_AFECTADA), origenes);
        assertTrue("debe coincidir con bloqueadas(), no solo con el origen reportado",
                Manifestaciones.bloqueadas().contains(clave()));
    }

    // 2) Simulación con AMBOS -> SÍ es origen, y SÍ aparece en bloqueadas() (igual que antes).
    @Test
    public void simulacionConAmbos_esOrigenYApareceEnBloqueadas() {
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);   // null/"" -> AMBOS

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION));
        assertEquals(Collections.singleton(Manifestaciones.ORIGEN_SIMULADO), origenes);
        assertTrue(Manifestaciones.bloqueadas().contains(clave()));
    }

    // 3) Simulación de UN SOLO SENTIDO -> NO debe reportarse como origen (bloqueadas() tampoco la
    //    incluye): existe para el ruteo (bloqueoHacia/sentidosBloqueados), pero no cierra la
    //    estación completa, así que no debe atribuírsele el marcador gris.
    @Test
    public void simulacionDeUnSoloSentido_noEsOrigenNiApareceEnBloqueadas() {
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), TERMINAL_ESPECIFICA);

        assertTrue("un bloqueo de un solo sentido no debe reportarse como causa del marcador gris",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());
        assertFalse("bloqueadas() tampoco debe incluir un bloqueo de un solo sentido",
                Manifestaciones.bloqueadas().contains(clave()));
        // Pero SÍ debe seguir bloqueando el ruteo hacia esa terminal específica -- no se tocó esa
        // lógica, solo la de origenesBloqueo()/bloqueadas().
        assertTrue(Manifestaciones.bloqueadoHacia(LINEA, Planificador.norm(ESTACION), TERMINAL_ESPECIFICA, false));
        assertFalse("hacia OTRA terminal no debe estar bloqueado",
                Manifestaciones.bloqueadoHacia(LINEA, Planificador.norm(ESTACION), "otra terminal", false));
    }

    // 4) Bloqueo por sentido (mantenimiento/cierre real) CON AMBOS -> sí es origen.
    @Test
    public void bloqueoPorSentidoConAmbos_esOrigen() {
        java.util.Map<String, Set<String>> porSentido = new java.util.HashMap<>();
        porSentido.put(clave(), new HashSet<>(java.util.Arrays.asList(Manifestaciones.AMBOS)));
        Manifestaciones.actualizar(Collections.<String>emptySet(), porSentido,
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertEquals(Collections.singleton(Manifestaciones.ORIGEN_POR_SENTIDO),
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)));
        assertTrue(Manifestaciones.bloqueadas().contains(clave()));
    }

    // 5) Bloqueo por sentido SIN AMBOS (una terminal específica) -> NO es origen, y bloqueadas()
    //    tampoco lo incluye (misma regla que el caso 3, pero para porSentido en vez de simulado).
    @Test
    public void bloqueoPorSentidoSinAmbos_noEsOrigenNiApareceEnBloqueadas() {
        java.util.Map<String, Set<String>> porSentido = new java.util.HashMap<>();
        porSentido.put(clave(), new HashSet<>(java.util.Arrays.asList(Planificador.norm(TERMINAL_ESPECIFICA))));
        Manifestaciones.actualizar(Collections.<String>emptySet(), porSentido,
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());
        assertFalse(Manifestaciones.bloqueadas().contains(clave()));
    }

    // 6) Varias fuentes que bloquean simultáneamente (afectación real + simulación AMBOS sobre la
    //    MISMA estación): ambos orígenes deben reportarse, sin que uno enmascare al otro.
    @Test
    public void variasFuentesSimultaneas_sinMascararse() {
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(clave())),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);   // AMBOS

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION));
        assertEquals(2, origenes.size());
        assertTrue(origenes.contains(Manifestaciones.ORIGEN_AFECTADA));
        assertTrue(origenes.contains(Manifestaciones.ORIGEN_SIMULADO));
    }

    // 7) Elevador (porSentidoMR), aunque sea AMBOS: NUNCA es origen de bloqueadas(), porque
    //    bloqueadas() nunca consulta porSentidoMR bajo ninguna condición -- es información de
    //    accesibilidad, no una causa del marcador gris.
    @Test
    public void elevadorNuncaEsOrigenDeBloqueadas_aunqueSeaAmbos() {
        java.util.Map<String, Set<String>> porSentidoMR = new java.util.HashMap<>();
        porSentidoMR.put(clave(), new HashSet<>(java.util.Arrays.asList(Manifestaciones.AMBOS)));
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                porSentidoMR, Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("un elevador no debe aparecer como origen del marcador gris",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());
        assertFalse("bloqueadas() no debe incluir la estación solo por el elevador",
                Manifestaciones.bloqueadas().contains(clave()));
    }

    // 8) Afectación retirada (siguiente ciclo del feed sin esa clave) -> el origen desaparece.
    @Test
    public void afectacionRetirada_dejaDeAparecerComoOrigen() {
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(clave())),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        assertFalse(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());

        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("una afectación retirada del feed no debe seguir bloqueando",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());
    }

    // 9) Una simulación NO se limpia por un refresco del feed real -- solo por limpiarSimulado().
    @Test
    public void simulacion_sobreviveActualizacionDelFeedReal_yLimpiarSimuladoLaQuita() {
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION))
                .contains(Manifestaciones.ORIGEN_SIMULADO));

        Manifestaciones.limpiarSimulado();
        assertTrue(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION)).isEmpty());
    }
}
