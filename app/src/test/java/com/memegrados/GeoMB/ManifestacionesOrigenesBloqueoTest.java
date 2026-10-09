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
 * separación entre afectación real, simulación, elevador (movilidad reducida) y Mexibús. No usa
 * Context/Android: Manifestaciones.java es una clase de datos en memoria, sin dependencias de
 * Android, así que se invoca tal cual se usaría en producción.
 *
 * <p>Manifestaciones guarda estado ESTÁTICO COMPARTIDO por todo el proceso de pruebas: cada test
 * limpia ese estado antes y después (ver {@link #limpiarTodo}) para no contaminar otras pruebas de
 * esta clase ni de otras.
 */
public class ManifestacionesOrigenesBloqueoTest {

    private static final int LINEA = 1;
    private static final String ESTACION = "estacion de prueba";

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

    // 1) Afectación real (Estado del Servicio) -> origen "afectada", y SOLO ese.
    @Test
    public void afectacionReal_marcaSoloOrigenAfectada() {
        String k = Planificador.claveTerminal(LINEA) + "|" + Planificador.norm(ESTACION);
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(k)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false);
        assertEquals(Collections.singleton(Manifestaciones.ORIGEN_AFECTADA), origenes);
    }

    // 2) Simulación -> origen "simulado", NUNCA se confunde con "afectada".
    @Test
    public void simulacion_marcaSoloOrigenSimulado() {
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false);
        assertEquals(Collections.singleton(Manifestaciones.ORIGEN_SIMULADO), origenes);
        assertTrue("una simulación bloquea el ruteo (bloqueoHacia) igual que una afectación real",
                Manifestaciones.bloqueadoHacia(LINEA, Planificador.norm(ESTACION), "cualquier terminal", false));
    }

    // 3) Elevador (porSentidoMR) solo cuenta si se pide movilidadReducida=true -- y nunca aparece
    //    como "afectada" (el elevador no bloquea el ruteo normal, solo informa/afecta MR).
    @Test
    public void elevador_soloCuentaConMovilidadReducida() {
        String k = Planificador.claveTerminal(LINEA) + "|" + Planificador.norm(ESTACION);
        java.util.Map<String, Set<String>> porSentidoMR = new java.util.HashMap<>();
        porSentidoMR.put(k, new HashSet<>(java.util.Arrays.asList(Manifestaciones.AMBOS)));
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                porSentidoMR, Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), true)
                .contains(Manifestaciones.ORIGEN_POR_SENTIDO_MR));
        assertTrue("sin movilidad reducida, el elevador no debe aparecer como origen",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false).isEmpty());
        assertFalse("el elevador no es una afectación real de ruteo",
                Manifestaciones.bloqueadoHacia(LINEA, Planificador.norm(ESTACION), null, false));
    }

    // 4) Al "retirar" la afectación real (nuevo ciclo sin esa clave), el origen desaparece.
    @Test
    public void afectacionRetirada_dejaDeAparecerComoOrigen() {
        String k = Planificador.claveTerminal(LINEA) + "|" + Planificador.norm(ESTACION);
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(k)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        assertFalse(Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false).isEmpty());

        // Siguiente ciclo del feed real: la clave ya no viene -- actualizar() REEMPLAZA el conjunto
        // completo, no lo acumula (ver Manifestaciones.actualizar(): afectadas.clear() + addAll()).
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("una afectación retirada del feed no debe seguir bloqueando",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false).isEmpty());
    }

    // 5) Una simulación NO se limpia por un refresco del feed real -- solo por limpiarSimulado().
    @Test
    public void simulacion_sobreviveActualizacionDelFeedReal_yLimpiarSimuladoLaQuita() {
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);
        // Varios "ciclos" del feed real, sin mencionar esta estación en absoluto:
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("la simulación debe sobrevivir a refrescos del feed real que no la mencionan",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false)
                        .contains(Manifestaciones.ORIGEN_SIMULADO));

        Manifestaciones.limpiarSimulado();
        assertTrue("limpiarSimulado() debe quitar la simulación explícitamente",
                Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false).isEmpty());
    }

    // 6) Afectación real Y simulación a la vez sobre la MISMA estación: ambos orígenes conviven,
    //    sin que uno enmascare al otro.
    @Test
    public void afectacionRealYSimulacionJuntas_seDistinguenAmbas() {
        String k = Planificador.claveTerminal(LINEA) + "|" + Planificador.norm(ESTACION);
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(k)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        Manifestaciones.simular(LINEA, Planificador.norm(ESTACION), null);

        Set<String> origenes = Manifestaciones.origenesBloqueo(LINEA, Planificador.norm(ESTACION), false);
        assertTrue(origenes.contains(Manifestaciones.ORIGEN_AFECTADA));
        assertTrue(origenes.contains(Manifestaciones.ORIGEN_SIMULADO));
        assertEquals(2, origenes.size());
    }
}
