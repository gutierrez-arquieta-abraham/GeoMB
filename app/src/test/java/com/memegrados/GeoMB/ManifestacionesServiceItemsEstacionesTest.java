package com.memegrados.GeoMB;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pruebas REALES (JUnit sobre el JVM del host, sin mocks) de
 * {@link ManifestacionesService#itemsEstaciones}: la función de producción que reemplaza la
 * comparación "nEst.contains(nn)" por una separación de lista + igualdad exacta por elemento.
 *
 * <p>LO QUE ES REAL: se invoca {@code ManifestacionesService.itemsEstaciones(...)} tal cual existe
 * en producción (paquete-visible para esta prueba), y {@link Planificador#norm} (también real) es
 * el que normaliza cada elemento.
 *
 * <p>LO QUE ES UNA RÉPLICA (declarado explícitamente, no oculto): el paso final "¿coincide con el
 * catálogo?" se simula aquí con listas de nombres COPIADOS TAL CUAL de
 * {@code app/src/main/assets/estaciones.json} (L1/L3), porque la restricción real por línea usa
 * {@code GtfsRepository.porNumero(Context, int)} y {@code Context} de Android no está disponible
 * en una prueba JVM pura (sin Robolectric en este proyecto). La comparación en sí
 * ({@code catalogoNorm.contains(item)}) es EXACTAMENTE la misma que la línea de producción
 * modificada en ManifestacionesService.procesarFilas():
 * {@code items.contains(nn)} donde {@code items = itemsEstaciones(estaciones)}.
 */
public class ManifestacionesServiceItemsEstacionesTest {

    // Nombres reales de L1 (estaciones.json), copiados tal cual para el catálogo de prueba.
    private static final List<String> L1 = Arrays.asList(
            "Indios Verdes", "Deportivo 18 de Marzo", "Euzkaro", "Potrero", "La Raza",
            "El Chopo", "Revolución", "Plaza de la República", "Reforma", "Hamburgo",
            "Insurgentes", "Felix Cuevas", "Río Churubusco", "Teatro de los Insurgentes",
            "El Caminero");
    // Nombre real de L3 que colisiona por nombre con uno de L1 ("La Raza"), distinta estación física.
    private static final List<String> L3 = Arrays.asList("La Raza", "Mina", "Hidalgo");

    /** Replica EXACTA del paso de comparación de producción: igualdad exacta normalizada contra
     *  el catálogo de una sola línea (nunca contains()). */
    private static Set<String> bloqueadasEnCatalogo(List<String> catalogo, String estacionesRaw) {
        Set<String> itemsNorm = new HashSet<>(ManifestacionesService.itemsEstaciones(estacionesRaw));
        Set<String> out = new HashSet<>();
        for (String nombre : catalogo) {
            String nn = Planificador.norm(nombre);
            if (itemsNorm.contains(nn)) out.add(nn);
        }
        return out;
    }

    // 1) "Insurgentes" NO debe calzar con una fila que en realidad dice "Teatro de los Insurgentes".
    @Test
    public void teatroDeLosInsurgentes_noBloqueaInsurgentes() {
        Set<String> bloq = bloqueadasEnCatalogo(L1, "Teatro de los Insurgentes");
        assertTrue("debe bloquear la estación mencionada completa",
                bloq.contains(Planificador.norm("Teatro de los Insurgentes")));
        assertFalse("NO debe bloquear 'Insurgentes' por ser subcadena de palabras de otro nombre",
                bloq.contains(Planificador.norm("Insurgentes")));
    }

    // 2) Lista con varios separadores: coma y " y ".
    @Test
    public void listaConComaYConjuncion_reconoceLosTresNombres() {
        List<String> items = ManifestacionesService.itemsEstaciones("El Chopo, Hamburgo y La Raza");
        assertEquals(Arrays.asList(
                Planificador.norm("El Chopo"),
                Planificador.norm("Hamburgo"),
                Planificador.norm("La Raza")), items);

        Set<String> bloq = bloqueadasEnCatalogo(L1, "El Chopo, Hamburgo y La Raza");
        assertEquals(3, bloq.size());
        assertTrue(bloq.contains(Planificador.norm("El Chopo")));
        assertTrue(bloq.contains(Planificador.norm("Hamburgo")));
        assertTrue(bloq.contains(Planificador.norm("La Raza")));
    }

    // 3) Nombres compuestos: deben tratarse como UN solo elemento, no partirse por palabra.
    @Test
    public void nombreCompuesto_esUnSoloElemento() {
        List<String> items = ManifestacionesService.itemsEstaciones("Plaza de la República");
        assertEquals(1, items.size());
        assertEquals(Planificador.norm("Plaza de la República"), items.get(0));
    }

    // 4) Acentos, mayúsculas y puntuación: normalización existente (Planificador.norm) + separadores.
    @Test
    public void acentosYPuntuacion_seNormalizanIgual() {
        List<String> items = ManifestacionesService.itemsEstaciones("Félix Cuevas; Río Churubusco.");
        assertEquals(Arrays.asList(
                Planificador.norm("Felix Cuevas"),
                Planificador.norm("Río Churubusco")), items);
    }

    // 5) Estaciones homónimas de líneas distintas: el cruce lo evita restringir al catálogo DE ESA
    //    línea (comportamiento ya existente, aquí confirmado con el nombre real que colisiona).
    @Test
    public void homonimaDeOtraLinea_soloBloqueaEnSuPropiaLinea() {
        String raw = "La Raza";
        Set<String> bloqL1 = bloqueadasEnCatalogo(L1, raw);
        Set<String> bloqL3 = bloqueadasEnCatalogo(L3, raw);
        assertTrue(bloqL1.contains(Planificador.norm("La Raza")));
        assertTrue(bloqL3.contains(Planificador.norm("La Raza")));
        // Ambas colecciones bloquean "la raza" dentro de SU PROPIO catálogo -- la clave real que
        // las distingue en producción lleva el número de línea (Manifestaciones.clave()), fuera del
        // alcance de itemsEstaciones(); aquí solo se confirma que el nombre no se filtra de más NI
        // de menos al pasarlo por listas de catálogo distintas.
        assertEquals(1, bloqL1.size());
        assertEquals(1, bloqL3.size());
    }

    // 6) Coincidencia exacta simple: debe seguir funcionando igual que antes.
    @Test
    public void coincidenciaExactaSimple_sigueBloqueando() {
        Set<String> bloq = bloqueadasEnCatalogo(L1, "Insurgentes");
        assertEquals(1, bloq.size());
        assertTrue(bloq.contains(Planificador.norm("Insurgentes")));
    }

    // 7) Texto sin ninguna estación identificable: no debe inventarse una coincidencia.
    @Test
    public void textoSinEstacionIdentificable_noBloqueaNada() {
        Set<String> bloq = bloqueadasEnCatalogo(L1, "Varios puntos de la ruta, consultar aviso en redes");
        assertTrue("texto libre sin nombre real de estación no debe calzar con nada del catálogo",
                bloq.isEmpty());
    }

    // Caso adicional (regresión directa del defecto original): "El Caminero" con texto de cola NO
    // relacionado pegado por un parseo de tramo distinto no debe producir coincidencias falsas aquí
    // -- itemsEstaciones trata todo el texto como una lista de nombres, no como un tramo "A a B".
    @Test
    public void elCaminero_coincideExactoAunSoloYSinGarbageAdicional() {
        Set<String> bloq = bloqueadasEnCatalogo(L1, "El Caminero");
        assertEquals(1, bloq.size());
        assertTrue(bloq.contains(Planificador.norm("El Caminero")));
    }
}
