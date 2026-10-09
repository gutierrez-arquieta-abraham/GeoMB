package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pruebas REALES (JUnit sobre el JVM del host) de
 * {@link ManifestacionesService#segmentoParcial}: la extracción de texto que alimenta
 * {@code bloquearTramos()} para reconocer rangos "Servicio de A a B (y de C a D)" y, por
 * complemento, las estaciones intermedias que quedan fuera de ese rango (bloqueadas).
 *
 * <p>LO QUE ES REAL: se invoca {@code ManifestacionesService.segmentoParcial(String)} tal cual
 * existe en producción -- es la función que, dado el texto YA normalizado (y con "A - B"
 * convertido a "A a B" por el llamador, como hace {@code procesarFilas()}), devuelve el segmento
 * de tramos a interpretar.
 *
 * <p>LO QUE NO SE PRUEBA AQUÍ (limitación declarada): la RESOLUCIÓN de cada nombre de tramo a un
 * índice real del catálogo ({@code idxEstacion()}/{@code bloquearTramos()}) necesita
 * {@code GtfsRepository.getLineas(Context)} -- Context de Android, no disponible en una prueba
 * JVM pura. Esta prueba confirma que el TEXTO se segmenta correctamente (incluida la fila real de
 * El Chopo/Hamburgo del audit); la resolución de índices se verificó manualmente contra el
 * catálogo real de L1 en el diagnóstico (ver el informe de esta sesión) y queda documentada ahí,
 * no repetida aquí como código.
 */
public class ManifestacionesServiceSegmentoParcialTest {

    // Caso real del audit (Caso B): "Por manifestación servicio provisional de Indios Verdes -
    // Buenavista y Glorieta de Insurgentes - El Caminero. Retraso en el servicio." Tal como llega a
    // segmentoParcial(): ya con "A - B" convertido a "A a B" y Planificador.norm() aplicado (ver
    // procesarFilas(), líneas ~560-565 de este archivo).
    @Test
    public void casoRealElChopoHamburgo_separaLosDosTramosPorLaConjuncionY() {
        String normFull = Planificador.norm(
                "Por manifestacion servicio provisional de Indios Verdes a Buenavista "
                        + "y Glorieta de Insurgentes a El Caminero. Retraso en el servicio.");

        String seg = ManifestacionesService.segmentoParcial(normFull);

        assertNotNull("el texto SÍ describe un servicio parcial -- no debe devolver null", seg);
        String[] tramos = seg.split("\\s+y\\s+");
        assertEquals("deben reconocerse los DOS tramos reales, no fusionarse en uno ni perderse",
                2, tramos.length);
        assertTrue(tramos[0].contains("indios verdes") && tramos[0].contains("buenavista"));
        assertTrue(tramos[1].contains("glorieta de insurgentes") && tramos[1].contains("el caminero"));
    }

    // Rango simple de un solo tramo: "Solo hay servicio de El Chopo a Hamburgo".
    @Test
    public void rangoSimple_unSoloTramo() {
        String normFull = Planificador.norm("Solo hay servicio de El Chopo a Hamburgo");
        String seg = ManifestacionesService.segmentoParcial(normFull);
        assertNotNull(seg);
        assertEquals(1, seg.split("\\s+y\\s+").length);
        assertTrue(seg.contains("el chopo") && seg.contains("hamburgo"));
    }

    // Texto SIN ningún marcador de servicio parcial: no debe inventarse un rango (evita falsos
    // positivos -- una manifestación puntual sin tramo no debe tratarse como "solo hay servicio de").
    @Test
    public void textoSinMarcadorDeTramo_noInventaUnRango() {
        String normFull = Planificador.norm("Manifestacion en la estacion Insurgentes. Retraso en el servicio.");
        assertNull("sin 'servicio de'/'provisional'/'parcial', no debe producir ningún segmento",
                ManifestacionesService.segmentoParcial(normFull));
    }

    // Varias oraciones "Servicio de X a Y." consecutivas (sin "y" explícito, separadas por punto,
    // que norm() ya quitó) también deben tratarse como tramos independientes vía el marcador
    // POSTERIOR actuando como separador (ver el comentario de producción sobre este caso real).
    @Test
    public void variasOracionesDeServicio_sinConjuncionExplicita_siSeparaEnTramos() {
        String normFull = Planificador.norm(
                "Servicio de Indios Verdes a El Caminero. Servicio de Cuauhtemoc a Pueblo de Santa Cruz.");
        String seg = ManifestacionesService.segmentoParcial(normFull);
        assertNotNull(seg);
        String[] tramos = seg.split("\\s+y\\s+");
        assertEquals(2, tramos.length);
        assertTrue(tramos[0].contains("indios verdes") && tramos[0].contains("el caminero"));
        assertTrue(tramos[1].contains("cuauhtemoc") && tramos[1].contains("pueblo de santa cruz"));
    }
}
