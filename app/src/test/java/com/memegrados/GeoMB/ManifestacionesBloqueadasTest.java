package com.memegrados.GeoMB;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pruebas REALES de {@link Manifestaciones#bloqueadas()} contra las colecciones que
 * {@code ManifestacionesService.procesarFilas()}/{@code guardarStore()} realmente le entregan
 * ({@code afectAcc}, {@code porSentidoAcc}), parte de la auditoría "GeoMB -- Auditoría y
 * corrección integral del coloreado de estaciones afectadas". {@code Manifestaciones.java} es una
 * clase de datos en memoria sin dependencias de Android, así que se prueba directamente, igual que
 * {@code ManifestacionesOrigenesBloqueoTest} (mismo patrón, estado estático compartido, limpiado
 * antes/después de cada prueba).
 *
 * <p>Estas pruebas verifican la CAPA DE AGREGACIÓN (qué combinación de {@code afectAcc}/
 * {@code porSentidoAcc} hace que una clave "linea|estacion" aparezca en {@code bloqueadas()}, que
 * es exactamente lo que {@code MapFragment.fueraDeServicio()} consulta para pintar el marcador gris
 * con el badge rojo "!") -- no reproducen el PARSEO de la fila oficial (eso lo cubren
 * {@code ManifestacionesServiceClasificacionAfectacionesTest} y
 * {@code ManifestacionesServiceBloquearTramosTest} con las funciones puras reales). Las claves que
 * se construyen aquí son las mismas que produciría {@code bloquearNn()}/{@code agregarSentido()}
 * para cada escenario (AMBOS vía {@code afectAcc.add(...)} para un cierre sin dirección, o una
 * terminal específica vía {@code porSentidoAcc} para un cierre direccional) -- documentado en el
 * javadoc de cada prueba de dónde viene esa combinación.
 */
public class ManifestacionesBloqueadasTest {

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

    private static String clave(int linea, String estacion) {
        return Planificador.claveTerminal(linea) + "|" + Planificador.norm(estacion);
    }

    // Caso real: L6 "416 Oriente", "Sin servicio por mantenimiento" sin dirección en el texto --
    // bloquearNn(6, "416 oriente", terminalSentido=null) agrega DIRECTO a afectAcc (ver
    // ManifestacionesService.bloquearNn: "if (terminalSentido != null) ... else afect.add(...)").
    @Test
    public void cierreDeEstacionIndividualSinDireccion_apareceEnBloqueadas() {
        String k = clave(6, "416 Oriente");
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(k)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("una estación cerrada sin dirección (AMBOS sentidos) debe quedar gris",
                Manifestaciones.bloqueadas().contains(k));
    }

    // Caso real: L3 "Poniente 128", "Sin servicio dirección a Tenayuca" -- bloquearNn(3, "poniente
    // 128", terminalSentido="tenayuca") agrega a porSentidoAcc con SOLO esa terminal (no AMBOS):
    // bloqueadas() no debe incluirla (un solo sentido bloqueado no es "fuera de servicio" visual).
    @Test
    public void cierreDireccional_unSoloSentido_noApareceEnBloqueadas() {
        String k = clave(3, "Poniente 128");
        Map<String, Set<String>> porSentido = new HashMap<>();
        porSentido.put(k, new HashSet<>(java.util.Arrays.asList(Planificador.norm("Tenayuca"))));
        Manifestaciones.actualizar(Collections.<String>emptySet(), porSentido,
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertFalse("un cierre direccional (un solo sentido) no debe pintar la estación gris -- "
                        + "el otro sentido sigue operando",
                Manifestaciones.bloqueadas().contains(k));
    }

    // Si el aviso direccional SÍ cubriera ambos sentidos explícitamente (terminalSentido==null por
    // texto sin dirección reconocible, o el propio feed reporta AMBOS), debe quedar gris -- control
    // de que porSentido CON AMBOS sí cuenta (a diferencia del caso anterior).
    @Test
    public void porSentidoConAmbos_siApareceEnBloqueadas() {
        String k = clave(3, "Poniente 128");
        Map<String, Set<String>> porSentido = new HashMap<>();
        porSentido.put(k, new HashSet<>(java.util.Arrays.asList(Manifestaciones.AMBOS)));
        Manifestaciones.actualizar(Collections.<String>emptySet(), porSentido,
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue(Manifestaciones.bloqueadas().contains(k));
    }

    // Retiro de la afectación en el siguiente ciclo del feed (la estación ya no aparece en ninguna
    // fila oficial): bloqueadas() debe dejar de incluirla -- no debe quedar gris "pegada".
    @Test
    public void afectacionRetirada_dejaDeAparecerEnBloqueadas() {
        String k = clave(6, "416 Oriente");
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(k)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        assertTrue(Manifestaciones.bloqueadas().contains(k));

        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertFalse("una afectación retirada del feed no debe seguir pintando la estación gris",
                Manifestaciones.bloqueadas().contains(k));
    }

    // Retiro de UNA afectación oficial que coexistía con otra estación bloqueada por una fuente
    // DISTINTA (simulación, panel de pruebas): al retirarse la oficial, la simulada debe seguir
    // vigente -- actualizar() nunca toca 'simulado' (confirmado por lectura de Manifestaciones.java:
    // el mapa 'simulado' es un estado APARTE, solo lo modifica simular()/limpiarSimulado()).
    @Test
    public void retiroDeAfectacionOficial_noBorraBloqueoSimuladoVigente() {
        String kOficial = clave(6, "416 Oriente");
        String kSimulado = clave(1, "Euzkaro");
        Manifestaciones.simular(1, Planificador.norm("Euzkaro"), null);   // AMBOS, fuente SIMULADA
        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(kOficial)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");
        assertTrue(Manifestaciones.bloqueadas().contains(kOficial));
        assertTrue(Manifestaciones.bloqueadas().contains(kSimulado));

        // Siguiente ciclo del feed real: la fila oficial ya no aparece.
        Manifestaciones.actualizar(Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertFalse("la afectación oficial retirada ya no debe bloquear",
                Manifestaciones.bloqueadas().contains(kOficial));
        assertTrue("la simulación, de una fuente aparte, debe seguir vigente pese al refresco del "
                        + "feed real",
                Manifestaciones.bloqueadas().contains(kSimulado));
    }

    // Dos filas de la MISMA línea con alcances distintos (una estación individual cerrada + un
    // tramo con varias estaciones intermedias, p. ej. el fixture real de L6 de esta misma
    // auditoría): ambas claves deben coexistir en bloqueadas(), sin que una sobrescriba a la otra
    // -- afectAcc es un Set que se va ACUMULANDO fila por fila dentro de un mismo ciclo (confirmado
    // por lectura de procesarFilas(): nunca se reinicia entre filas, solo entre ciclos completos).
    @Test
    public void dosFilasDeLaMismaLinea_conAlcancesDistintos_coexistenEnBloqueadas() {
        String k1 = clave(6, "416 Oriente");          // fila 1: cierre individual
        String k2 = clave(6, "Montevideo");            // fila 2: una de las 4 estaciones del tramo
        String k3 = clave(6, "San Bartolo");
        Set<String> nuevas = new HashSet<>(java.util.Arrays.asList(k1, k2, k3));
        Manifestaciones.actualizar(nuevas, Collections.<String, Set<String>>emptyMap(),
                Collections.<String, Set<String>>emptyMap(), Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue(Manifestaciones.bloqueadas().contains(k1));
        assertTrue(Manifestaciones.bloqueadas().contains(k2));
        assertTrue(Manifestaciones.bloqueadas().contains(k3));
    }

    // Líneas DISTINTAS con el mismo nombre de estación normalizado ("La Raza" en L1 y L3, caso real
    // del catálogo): bloquear SOLO la de una línea no debe afectar a la otra -- la clave lleva
    // SIEMPRE el número de línea (Planificador.claveTerminal), nunca solo el nombre.
    @Test
    public void lineasDistintas_mismoNombreDeEstacion_noSeContaminan() {
        String kL1 = clave(1, "La Raza");
        String kL3 = clave(3, "La Raza");
        assertNotEquals("las claves de L1 y L3 para 'La Raza' deben ser distintas", kL1, kL3);

        Manifestaciones.actualizar(new HashSet<>(java.util.Arrays.asList(kL1)),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, Set<String>>emptyMap(),
                Collections.<Manifestaciones.Afectacion>emptyList(), "");

        assertTrue("La Raza de L1 debe quedar bloqueada", Manifestaciones.bloqueadas().contains(kL1));
        assertFalse("La Raza de L3 NO debe quedar bloqueada solo porque L1 comparte el nombre",
                Manifestaciones.bloqueadas().contains(kL3));
    }
}
