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

    // Orden REAL de L6 (copiado de estaciones.json, índices 0-36), usado por el caso de regresión
    // de la auditoría de coloreado ("GeoMB -- Auditoría y corrección integral del coloreado de
    // estaciones afectadas"): aviso real de "estado" con estado="Obstrucción de carril",
    // estaciones="Montevideo - San Bartolo", info="Servicio provisional de El Rosario a Norte 45 y
    // de Villa de Aragón a Instituto Politécnico Nacional".
    private static final List<Estacion> L6 = new ArrayList<>(Arrays.asList(
            new Estacion("El Rosario", 0, 0), new Estacion("Colegio de Bachilleres 1", 0, 0),
            new Estacion("De Las Culturas", 0, 0), new Estacion("Ferrocarriles Nacionales", 0, 0),
            new Estacion("UAM Azcapotzalco", 0, 0), new Estacion("Tecnoparque", 0, 0),
            new Estacion("Norte 59", 0, 0), new Estacion("Norte 45", 0, 0),
            new Estacion("Montevideo", 0, 0), new Estacion("Lindavista-Vallejo", 0, 0),
            new Estacion("Instituto del Petróleo", 0, 0), new Estacion("San Bartolo", 0, 0),
            new Estacion("Instituto Politécnico Nacional", 0, 0), new Estacion("Riobamba", 0, 0),
            new Estacion("Deportivo 18 de Marzo", 0, 0), new Estacion("La Villa", 0, 0),
            new Estacion("De los Misterios", 0, 0), new Estacion("Hospital Infantil La Villa", 0, 0),
            new Estacion("Gustavo A. Madero", 0, 0), new Estacion("Martín Carrera", 0, 0),
            new Estacion("Hospital General La Villa", 0, 0), new Estacion("San Juan de Aragón", 0, 0),
            new Estacion("Gran Canal", 0, 0), new Estacion("Casas Alemán", 0, 0),
            new Estacion("Pueblo San Juan de Aragón", 0, 0), new Estacion("Loreto Fabela", 0, 0),
            new Estacion("416 Poniente", 0, 0), new Estacion("Deportivo Los Galeana", 0, 0),
            new Estacion("482", 0, 0), new Estacion("Ampliación Providencia", 0, 0),
            new Estacion("Volcán de Fuego", 0, 0), new Estacion("414", 0, 0),
            new Estacion("416 Oriente", 0, 0), new Estacion("La Pradera", 0, 0),
            new Estacion("Colegio de Bachilleres 9", 0, 0), new Estacion("Francisco Morazán", 0, 0),
            new Estacion("Villa de Aragón", 0, 0)));

    private static final ManifestacionesService.CandidatoLinea L6_CANDIDATO =
            new ManifestacionesService.CandidatoLinea(6, L6);

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

    // ============================================================================================
    // Fixture real de la auditoría de coloreado -- L6, info con DOS tramos "y de": "Servicio
    // provisional de El Rosario a Norte 45 y de Villa de Aragón a Instituto Politécnico Nacional".
    // Debe bloquear EXACTAMENTE las 4 estaciones que quedan en la brecha entre ambos tramos
    // (Montevideo, Lindavista-Vallejo, Instituto del Petróleo, San Bartolo) -- ni toda la línea, ni
    // solo los 2 extremos nombrados en la columna "estaciones" ("Montevideo - San Bartolo").
    // ============================================================================================
    @Test
    public void fixtureL6_dosTramosProvisionales_bloqueaExactamenteLaBrechaIntermedia() {
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(
                "Servicio provisional de El Rosario a Norte 45 y de Villa de Aragón a "
                        + "Instituto Politécnico Nacional"));
        List<Integer> fuera = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L6_CANDIDATO)).get(6);

        // Montevideo(8), Lindavista-Vallejo(9), Instituto del Petróleo(10), San Bartolo(11).
        assertEquals(Arrays.asList(8, 9, 10, 11), fuera);
        // Ningún extremo de los tramos EN SERVICIO queda bloqueado.
        assertFalse(fuera.contains(0));    // El Rosario
        assertFalse(fuera.contains(7));    // Norte 45
        assertFalse(fuera.contains(12));   // Instituto Politécnico Nacional
        assertFalse(fuera.contains(36));   // Villa de Aragón
        // No se bloquea indiscriminadamente toda la línea (37 estaciones en total).
        assertEquals(4, fuera.size());
    }

    // Orden REAL de L3 (copiado de estaciones.json, índices 0-38), usado por el caso de regresión
    // reportado por el usuario en dispositivo: aviso real "Manifestación" / estaciones="Poniente
    // 128 - Cuitláhuac" / info="Servicio de Tenayuca a Poniente 134 y de Pueblo de Santa Cruz a
    // Héroe Nacozari." -- el texto oficial dice "Pueblo de Santa Cruz", que NO calza con el nombre
    // real del catálogo, "Pueblo Sta. Cruz Atoyac" (índice 38).
    private static final List<Estacion> L3 = new ArrayList<>(Arrays.asList(
            new Estacion("Tenayuca", 0, 0), new Estacion("San José de la Escalera", 0, 0),
            new Estacion("Progreso Nacional", 0, 0), new Estacion("Tres Anegas", 0, 0),
            new Estacion("Júpiter", 0, 0), new Estacion("La Patera", 0, 0),
            new Estacion("Poniente 146", 0, 0), new Estacion("Montevideo", 0, 0),
            new Estacion("Poniente 134", 0, 0), new Estacion("Poniente 128", 0, 0),
            new Estacion("Magdalena La Salinas", 0, 0), new Estacion("Coltongo", 0, 0),
            new Estacion("Cuitláhuac", 0, 0), new Estacion("Héroe de Nacozari", 0, 0),
            new Estacion("Hospital La Raza", 0, 0), new Estacion("La Raza", 0, 0),
            new Estacion("Circuito", 0, 0), new Estacion("Tolnáhuac", 0, 0),
            new Estacion("Tlatelolco", 0, 0), new Estacion("Ricardo Flores Magón", 0, 0),
            new Estacion("Buenavista II", 0, 0), new Estacion("Buenavista III", 0, 0),
            new Estacion("Guerrero", 0, 0), new Estacion("Mina", 0, 0),
            new Estacion("Hidalgo", 0, 0), new Estacion("Juárez", 0, 0),
            new Estacion("Balderas", 0, 0), new Estacion("Cuauhtémoc", 0, 0),
            new Estacion("Jardín Pushkin", 0, 0), new Estacion("Hospital General", 0, 0),
            new Estacion("Dr. Márquez", 0, 0), new Estacion("Centro Médico", 0, 0),
            new Estacion("Obrero Mundial", 0, 0), new Estacion("Etiopía-Plaza de la Transparencia", 0, 0),
            new Estacion("Luz Saviñón", 0, 0), new Estacion("Eugenia", 0, 0),
            new Estacion("División del Norte", 0, 0), new Estacion("Miguel Laurent", 0, 0),
            new Estacion("Pueblo Sta. Cruz Atoyac", 0, 0)));

    private static final ManifestacionesService.CandidatoLinea L3_CANDIDATO =
            new ManifestacionesService.CandidatoLinea(3, L3);

    // ============================================================================================
    // Defecto confirmado y corregido: cuando el aviso describe VARIOS tramos "en servicio" unidos
    // por "y" y uno de ellos no resuelve contra el catálogo (por una abreviatura distinta en el
    // texto oficial), indicesFueraDeRangoPorLinea() ya NO bloquea "todo lo que sobra" -- antes
    // bloqueaba desde Poniente 128 hasta la propia terminal Pueblo Sta. Cruz Atoyac (toda la mitad
    // sur de la línea) solo porque "Pueblo de Santa Cruz" no calzó con "Pueblo Sta. Cruz Atoyac".
    // ============================================================================================
    @Test
    public void fixtureL3_tramoConExtremoQueNoCalzaPorAbreviatura_noBloqueaElRestoDeLaLinea() {
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(
                "Servicio de Tenayuca a Poniente 134 y de Pueblo de Santa Cruz a Héroe Nacozari."));
        java.util.Map<Integer, List<Integer>> resultado = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L3_CANDIDATO));

        // Un tramo no resolvió ("Pueblo de Santa Cruz" no calza con "Pueblo Sta. Cruz Atoyac"): L3
        // no debe aparecer en el resultado en absoluto -- no se bloquea nada por esta vía, en vez de
        // bloquear de más por una resolución parcial.
        assertNull("con un tramo sin resolver, la línea no debe recibir NINGÚN bloqueo por esta vía",
                resultado.get(3));
    }

    // Control: el MISMO primer tramo, aislado (sin el segundo tramo que falla), sigue resolviendo
    // con normalidad -- confirma que el fix no rompe el caso de un solo tramo bien formado.
    @Test
    public void fixtureL3_unSoloTramoBienFormado_siResuelve() {
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(
                "Servicio de Tenayuca a Poniente 134."));
        List<Integer> fuera = ManifestacionesService.indicesFueraDeRangoPorLinea(
                seg, Collections.singletonList(L3_CANDIDATO)).get(3);

        assertNotNull(fuera);
        assertFalse(fuera.contains(0));   // Tenayuca
        assertFalse(fuera.contains(8));   // Poniente 134
        assertTrue(fuera.contains(9));    // Poniente 128: fuera del único tramo en servicio
        assertTrue(fuera.contains(38));   // Pueblo Sta. Cruz Atoyac: fuera del único tramo en servicio
    }
}
