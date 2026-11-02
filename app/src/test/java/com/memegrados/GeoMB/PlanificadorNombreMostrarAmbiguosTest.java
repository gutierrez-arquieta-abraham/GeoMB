package com.memegrados.GeoMB;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (reporte del usuario): la notificación de recorrido mostraba el MISMO
 * nombre dos veces -- "anterior" y "próxima" ambos "Deportivo 18 de Marzo" (L6 y L1), sin forma de
 * distinguirlos salvo por el ícono/color. Causa raíz en {@code Planificador.nombreMostrar()}: tenía
 * un atajo "el Metrobús no tiene estaciones homónimas reales dentro de su sistema" que saltaba la
 * desambiguación para CUALQUIER línea 1-99, sin importar que {@code ambiguos()} (que SÍ recorre
 * todas las líneas, Metrobús incluido) ya tuviera el nombre marcado como ambiguo.
 *
 * Verificado con datos reales (lineas.json/estaciones.json, distancia Haversine): 20 pares de
 * estaciones Metrobús comparten nombre exacto entre líneas distintas, TODOS con separación física
 * real (57-429 m, nunca el mismo punto) -- Indios Verdes (L1/L7, 429 m), La Raza (L1/L3, 294 m),
 * Plaza de la República (L1/L4, 353 m), Deportivo 18 de Marzo (L1/L6, 231 m), entre otros. La
 * premisa del atajo era falsa para los 20.
 *
 * El fix quita el atajo; Metrobús ahora desambigua con el mismo mecanismo que ya usaba Mexibús/
 * Mexicable. Prueba la pieza pura {@code nombreMostrarConAmbiguos()} (package-private para esto,
 * mismo trato que otras funciones puras de este archivo) -- separada de {@code nombreMostrar()}
 * porque esta última necesita Context/GtfsRepository (no testeable sin Robolectric) solo para
 * construir el conjunto de nombres ambiguos, no para la decisión de formato en sí.
 */
public class PlanificadorNombreMostrarAmbiguosTest {

    private static final Set<String> AMBIGUOS = Collections.singleton("deportivo 18 de marzo");

    @Test
    public void metrobus_nombreAmbiguo_agregaSufijoDeLinea() {
        // Antes del fix esto NUNCA pasaba para Metrobús (línea < 100), sin importar ambiguos().
        assertEquals("Deportivo 18 de Marzo 6",
                Planificador.nombreMostrarConAmbiguos("Deportivo 18 de Marzo", 6, AMBIGUOS));
        assertEquals("Deportivo 18 de Marzo 1",
                Planificador.nombreMostrarConAmbiguos("Deportivo 18 de Marzo", 1, AMBIGUOS));
    }

    @Test
    public void metrobus_nombreNoAmbiguo_sinSufijo() {
        assertEquals("Insurgentes",
                Planificador.nombreMostrarConAmbiguos("Insurgentes", 1, AMBIGUOS));
    }

    @Test
    public void mexibus_siguenIgualQueAntes_conSufijo() {
        // Comportamiento ya existente para Mexibús/Mexicable (línea >= 100): sin cambios. El sufijo
        // es el número de línea "corto" (etiquetaLineaCorta: 102 -> "2"), no el crudo "102".
        Set<String> amb = Collections.singleton("las americas");
        assertEquals("Las Américas 2",
                Planificador.nombreMostrarConAmbiguos("Las Américas", 102, amb));
    }

    @Test
    public void etiquetaDeLinea_metrobus_esSoloElNumero() {
        // Formato real esperado en pantalla para Metrobús: "Indios Verdes 7", no "Indios Verdes L7".
        assertEquals("Indios Verdes 7",
                Planificador.nombreMostrarConAmbiguos("Indios Verdes",
                        7, Collections.singleton("indios verdes")));
    }

    @Test
    public void conjuntoVacio_nuncaAgregaSufijo() {
        assertEquals("Deportivo 18 de Marzo",
                Planificador.nombreMostrarConAmbiguos("Deportivo 18 de Marzo", 6, Collections.emptySet()));
    }

    @Test
    public void nombreMostrar_sinContext_devuelveLimpioSinTocarAmbiguos() {
        // Salvaguarda de nombreMostrar() (no de la pieza pura): sin Context, nunca debe intentar
        // ambiguos(ctx) (reventaría, necesita GtfsRepository) -- siempre el nombre limpio tal cual.
        assertEquals("Deportivo 18 de Marzo",
                Planificador.nombreMostrar(null, "Deportivo 18 de Marzo", 6));
    }
}
