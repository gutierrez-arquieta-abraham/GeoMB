package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regresión: el trazo que dibuja el Planificador para un tramo de viaje en Mexibús L1 (101) y L1A
 * (111) usaba SIEMPRE la misma geometría de {@code Linea.ruta} sin importar el sentido real de
 * viaje -- a diferencia de Metrobús, que ya elige entre "Lx-ida"/"Lx-vuelta" (sublineas.json) según
 * hacia dónde se viaja (ver {@code Planificador.geomSentido()}/{@code elegirSentido()}). Mexibús
 * nunca tuvo esas claves en sublineas.json, así que {@code geomSentido()} caía siempre al fallback
 * de {@code Linea.ruta} (un solo sentido). Se agregaron "L101-ida"/"L101-vuelta" y
 * "L111-ida"/"L111-vuelta" con la geometría real del GTFS (shapes direccionales reales, no la
 * misma calle invertida). Prueba la función pura {@code elegirSentido()} (package-private para
 * esto, mismo trato que corrManual()/nucleoCoincide()) con los extremos REALES de cada shape --
 * no hace falta Context/Robolectric porque no toca GtfsRepository.sublinea().
 */
public class PlanificadorGeomSentidoMexibusTest {

    // Extremos reales de L101-ida (shape-mvs9i1wz-1): Ojo de Agua -> Ciudad Azteca.
    private static final List<LatLng> L101_IDA = Arrays.asList(
            new LatLng(19.661659, -99.001886),   // Ojo de Agua
            new LatLng(19.534518, -99.027793));  // Ciudad Azteca

    // Extremos reales de L101-vuelta (shape-mvselgpt-58): Ciudad Azteca -> Ojo de Agua.
    private static final List<LatLng> L101_VUELTA = Arrays.asList(
            new LatLng(19.534544, -99.027783),   // Ciudad Azteca
            new LatLng(19.661745, -99.001867));  // Ojo de Agua

    // Extremos reales de L111-ida (shape-muujywlf-17): Ojo de Agua -> AIFA.
    private static final List<LatLng> L111_IDA = Arrays.asList(
            new LatLng(19.661654, -99.001183),   // Ojo de Agua
            new LatLng(19.735852, -99.02777));   // AIFA

    // Extremos reales de L111-vuelta (shape-muvuemuv-1): AIFA -> Ojo de Agua.
    private static final List<LatLng> L111_VUELTA = Arrays.asList(
            new LatLng(19.735858, -99.027762),   // AIFA
            new LatLng(19.661663, -99.001152));  // Ojo de Agua

    @Test
    public void elegirSentido_l1_haciaCiudadAzteca_usaIda() {
        List<LatLng> g = Planificador.elegirSentido(L101_IDA, L101_VUELTA,
                L101_IDA.get(0), L101_IDA.get(1));   // viaja Ojo de Agua -> Ciudad Azteca
        assertSame("debe elegir L101-ida (va en ese sentido)", L101_IDA, g);
    }

    @Test
    public void elegirSentido_l1_haciaOjoDeAgua_usaVuelta() {
        List<LatLng> g = Planificador.elegirSentido(L101_IDA, L101_VUELTA,
                L101_VUELTA.get(0), L101_VUELTA.get(1));   // viaja Ciudad Azteca -> Ojo de Agua
        assertSame("debe elegir L101-vuelta (va en ese sentido)", L101_VUELTA, g);
    }

    @Test
    public void elegirSentido_l1a_haciaAifa_usaIda() {
        List<LatLng> g = Planificador.elegirSentido(L111_IDA, L111_VUELTA,
                L111_IDA.get(0), L111_IDA.get(1));   // viaja Ojo de Agua -> AIFA
        assertSame("debe elegir L111-ida (va en ese sentido)", L111_IDA, g);
    }

    @Test
    public void elegirSentido_l1a_haciaOjoDeAgua_usaVuelta() {
        List<LatLng> g = Planificador.elegirSentido(L111_IDA, L111_VUELTA,
                L111_VUELTA.get(0), L111_VUELTA.get(1));   // viaja AIFA -> Ojo de Agua
        assertSame("debe elegir L111-vuelta (va en ese sentido)", L111_VUELTA, g);
    }

    @Test
    public void elegirSentido_sinVuelta_usaIda() {
        // Comportamiento de respaldo ya existente: si falta uno de los dos, se usa el que haya.
        List<LatLng> g = Planificador.elegirSentido(L101_IDA, null, L101_IDA.get(0), L101_IDA.get(1));
        assertSame(L101_IDA, g);
    }
}
