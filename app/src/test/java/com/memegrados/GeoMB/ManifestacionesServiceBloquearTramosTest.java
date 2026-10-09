package com.memegrados.GeoMB;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pruebas REALES del algoritmo que calcula los extremos y las estaciones intermedias de un tramo
 * "Servicio de A a B", usando el orden REAL de L1 (copiado de
 * {@code app/src/main/assets/estaciones.json}, índices 0-45: la troncal secuencial completa, sin
 * los 5 andenes "soloMapa" duplicados que el catálogo agrega al final -- un detalle de
 * presentación del mapa, no de la topología de la troncal, fuera del alcance de esta prueba).
 *
 * <p>TODO lo que se invoca aquí es producción real, sin réplicas:
 * {@code ManifestacionesService.segmentoParcial(String)} y
 * {@code ManifestacionesService.indicesFueraDeRangoPorLinea(String, List<CandidatoLinea>)} -- el
 * núcleo PURO que {@code bloquearTramos()} llama directamente (no hay una copia aparte en el
 * test: ver el javadoc de esa función en producción). {@code CandidatoLinea} también es
 * producción: existe para no necesitar una {@code Linea} real (su constructor llama a
 * {@code android.graphics.Color.parseColor()}, que revienta en una prueba JVM pura sin
 * Robolectric) -- toma {@code List<Estacion>} en vez de {@code Linea}, y {@code Estacion} no toca
 * Android.
 *
 * <p>LO QUE SIGUE SIN PROBARSE AQUÍ (requiere un test de integración Android, no JVM puro):
 * {@code bloquearTramos()} en sí, específicamente los dos pasos que SÍ dependen de Context y que
 * esta extracción dejó fuera a propósito: resolver {@code GtfsRepository.getLineas(this)} (el
 * catálogo real completo) y escribir los efectos secundarios ({@code afect.add(...)},
 * {@code cortesAcc}/{@code cortarAlrededor}). Una prueba de integración con Robolectric o en
 * dispositivo sería necesaria para cubrir esos dos pasos; aquí se cubre todo lo demás -- que es,
 * en volumen, la parte no trivial del algoritmo (texto, resolución de nombre, selección de línea,
 * rangos).
 */
public class ManifestacionesServiceBloquearTramosTest {

    private static final List<Estacion> L1 = new ArrayList<>(Arrays.asList(
            new Estacion("Indios Verdes", 0, 0), new Estacion("Deportivo 18 de Marzo", 0, 0),
            new Estacion("Euzkaro", 0, 0), new Estacion("Potrero", 0, 0), new Estacion("La Raza", 0, 0),
            new Estacion("Circuito", 0, 0), new Estacion("San Simón", 0, 0), new Estacion("Manuel González", 0, 0),
            new Estacion("Buenavista", 0, 0), new Estacion("El Chopo", 0, 0), new Estacion("Revolución", 0, 0),
            new Estacion("Plaza de la República", 0, 0), new Estacion("Reforma", 0, 0), new Estacion("Hamburgo", 0, 0),
            new Estacion("Insurgentes", 0, 0), new Estacion("Durango", 0, 0), new Estacion("Álvaro Obregón", 0, 0),
            new Estacion("Sonora", 0, 0), new Estacion("Campeche", 0, 0), new Estacion("Chilpancingo", 0, 0),
            new Estacion("Nuevo León", 0, 0), new Estacion("La Piedad", 0, 0), new Estacion("Poliforum", 0, 0),
            new Estacion("Nápoles", 0, 0), new Estacion("Colonia del Valle", 0, 0), new Estacion("Ciudad de los Deportes", 0, 0),
            new Estacion("Parque Hundido", 0, 0), new Estacion("Felix Cuevas", 0, 0), new Estacion("Río Churubusco", 0, 0),
            new Estacion("Teatro de los Insurgentes", 0, 0), new Estacion("José María Velasco", 0, 0), new Estacion("Francia", 0, 0),
            new Estacion("Olivo", 0, 0), new Estacion("Altavista", 0, 0), new Estacion("La Bombilla", 0, 0),
            new Estacion("Dr. Gálvez", 0, 0), new Estacion("Ciudad Universitaria", 0, 0), new Estacion("Centro Cultural Universitario", 0, 0),
            new Estacion("Perisur", 0, 0), new Estacion("Villa Olímpica", 0, 0), new Estacion("Corregidora", 0, 0),
            new Estacion("Ayuntamiento", 0, 0), new Estacion("Fuentes Brotantes", 0, 0), new Estacion("Santa Úrsula", 0, 0),
            new Estacion("La Joya", 0, 0), new Estacion("El Caminero", 0, 0)));

    private static final ManifestacionesService.CandidatoLinea L1_CANDIDATO =
            new ManifestacionesService.CandidatoLinea(1, L1);

    // Caso real del audit (Caso B): ambos tramos, estaciones intermedias correctas (El Chopo,
    // Revolución, Plaza de la República, Reforma, Hamburgo -- idx 9-13), extremos de AMBOS tramos
    // NO bloqueados.
    @Test
    public void casoRealElChopoHamburgo_bloqueaExactamenteLasIntermedias() {
        String normFull = Planificador.norm(
                "Por manifestacion servicio provisional de Indios Verdes a Buenavista "
                        + "y Glorieta de Insurgentes a El Caminero. Retraso en el servicio.");
        String seg = ManifestacionesService.segmentoParcial(normFull);
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO));

        assertEquals(Arrays.asList(9, 10, 11, 12, 13), resultado.get(1));
    }

    // Tramo COMPLETO (ambos extremos = terminales reales de la troncal): nada queda fuera.
    @Test
    public void tramoCompleto_terminalATerminal_noBloqueaNada() {
        String seg = ManifestacionesService.segmentoParcial(
                Planificador.norm("Solo hay servicio de Indios Verdes a El Caminero"));
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO));

        assertEquals(Collections.emptyList(), resultado.get(1));
    }

    // Tramo PARCIAL de un solo rango interior: todo lo de afuera queda bloqueado, los extremos
    // (Perisur=38, Villa Olímpica=39) NO -- confirma ambos extremos y las intermedias correctas.
    @Test
    public void tramoParcialInterior_bloqueaTodoExceptoElRango() {
        String seg = ManifestacionesService.segmentoParcial(
                Planificador.norm("Solo hay servicio de Perisur a Villa Olímpica"));
        List<Integer> fuera = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO)).get(1);

        assertFalse("el extremo Perisur (38) no debe quedar bloqueado", fuera.contains(38));
        assertFalse("el extremo Villa Olímpica (39) no debe quedar bloqueado", fuera.contains(39));
        assertTrue(fuera.contains(37));   // Centro Cultural Universitario: justo antes, sí bloqueada
        assertTrue(fuera.contains(40));   // Corregidora: justo después, sí bloqueada
        assertEquals(44, fuera.size());   // 46 estaciones - 2 en servicio
    }

    // AMBOS extremos de un rango de dos estaciones adyacentes (Reforma=12, Hamburgo=13) deben
    // incluirse como "en servicio"; las estaciones justo afuera (Plaza de la República=11,
    // Insurgentes=14) sí deben quedar bloqueadas -- confirma el límite exacto del rango.
    @Test
    public void ambosExtremosDeUnRangoCorto_seIncluyenCompletos() {
        String seg = ManifestacionesService.segmentoParcial(
                Planificador.norm("Solo hay servicio de Reforma a Hamburgo"));
        List<Integer> fuera = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO)).get(1);

        assertFalse(fuera.contains(12));
        assertFalse(fuera.contains(13));
        assertTrue(fuera.contains(11));
        assertTrue(fuera.contains(14));
    }

    // Nombre NO encontrado en el catálogo (estación inexistente): ese tramo no resuelve ningún
    // rango -- igual que un tramo malformado, la línea queda sin entrada (null), no bloquea nada
    // por esta vía.
    @Test
    public void nombreNoEncontrado_noReconoceNingunRango() {
        String seg = ManifestacionesService.segmentoParcial(
                Planificador.norm("Solo hay servicio de Estacion Inexistente Xyz a El Caminero"));
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO));

        assertNull("un extremo que no existe en el catálogo no debe producir ningún rango",
                resultado.get(1));
    }

    // Tramo MALFORMADO (sin el segundo extremo: no hay " a " real que partir) -- no reconoce
    // ningún rango; la línea no aparece en el resultado (null), no "todo bloqueado" ni "nada por
    // accidente".
    @Test
    public void tramoMalformado_sinSegundoExtremo_noReconoceNingunRango() {
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm("Solo hay servicio de Reforma"));
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L1_CANDIDATO));

        assertNull("sin un segundo extremo, bloquearTramos() salta la línea -- no bloquea nada por esta vía",
                resultado.get(1));
    }

    // Texto que no describe ningún servicio parcial: segmentoParcial() ya devuelve null, antes de
    // llegar a indicesFueraDeRangoPorLinea() -- igual que en producción (bloquearTramos() retorna
    // temprano si seg==null).
    @Test
    public void sinMarcadorDeTramo_segmentoParcialYaEsNull() {
        assertNull(ManifestacionesService.segmentoParcial(
                Planificador.norm("Manifestacion en la estacion Insurgentes. Retraso en el servicio.")));
    }

    // SELECCIÓN DE LÍNEA: dos líneas candidatas cuyos catálogos comparten los MISMOS dos nombres
    // (nombres homónimos entre líneas, como "La Raza" en L1/L3 reales) -- el tramo debe resolverse
    // contra la PRIMERA línea de la lista únicamente (igual que bloquearTramos(): "primera línea
    // que contiene ambos extremos", con "break"), la segunda NUNCA debe recibir el rango ni
    // aparecer en el resultado.
    @Test
    public void seleccionDeLinea_dosLineasConNombresHomonimos_soloResuelveLaPrimera() {
        List<Estacion> lineaA = Arrays.asList(
                new Estacion("Terminal Norte", 0, 0), new Estacion("Estacion Comun Uno", 0, 0),
                new Estacion("Estacion Comun Dos", 0, 0), new Estacion("Terminal Sur", 0, 0));
        List<Estacion> lineaB = Arrays.asList(
                new Estacion("Otra Terminal", 0, 0), new Estacion("Estacion Comun Uno", 0, 0),
                new Estacion("Estacion Comun Dos", 0, 0));
        ManifestacionesService.CandidatoLinea candA = new ManifestacionesService.CandidatoLinea(101, lineaA);
        ManifestacionesService.CandidatoLinea candB = new ManifestacionesService.CandidatoLinea(102, lineaB);

        String seg = ManifestacionesService.segmentoParcial(
                Planificador.norm("Solo hay servicio de Estacion Comun Uno a Estacion Comun Dos"));
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Arrays.asList(candA, candB));

        assertNotNull("la primera línea candidata (101) sí debe resolver el tramo", resultado.get(101));
        assertEquals(Arrays.asList(0, 3), resultado.get(101));   // Terminal Norte y Sur quedan fuera
        assertNull("la segunda línea (102) NUNCA debe recibir el rango: la primera ya lo reclamó",
                resultado.get(102));
    }
}
