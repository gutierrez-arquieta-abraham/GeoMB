package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pruebas REALES (JUnit sobre el JVM del host) de {@link MapFragment#distanciaMismaParada} y del
 * umbral {@link MapFragment#RADIO_MISMA_PARADA_M}: el núcleo de la corrección de
 * {@code sirveRutaMixtaAlterna()} (caso 3 del audit -- Plaza de la República → Reforma).
 *
 * <p>LO QUE ES REAL: se invoca {@code MapFragment.distanciaMismaParada(LatLng, LatLng)}, que
 * delega en {@code Linea.distancia(LatLng, LatLng)} -- la MISMA función que ya usa el proyecto
 * para medir distancias reales en el mapa. Las coordenadas usadas son las REALES del catálogo
 * ({@code app/src/main/assets/estaciones.json}), copiadas tal cual.
 *
 * <p>LO QUE NO SE PRUEBA AQUÍ (limitación declarada): {@code sirveRutaMixtaAlterna()} en sí es un
 * método de INSTANCIA de {@link MapFragment} que necesita {@code requireContext()} y
 * {@code GtfsRepository} (Context de Android) para resolver el catálogo de la línea alterna -- no
 * se puede invocar en una prueba JVM pura sin Robolectric (no está en este proyecto). Esta prueba
 * cubre el cálculo de distancia que decide la exención (la parte donde estaba el defecto real) y
 * deja la integración completa para verificación manual en el dispositivo (ver el documento de
 * procedimiento de diagnóstico).
 */
public class MapFragmentRutaMixtaDistanciaTest {

    // Coordenadas reales (estaciones.json) de las 3 colisiones de nombre confirmadas en el audit:
    // la misma calle/corredor NO es la misma en L1 (Av. Insurgentes) que en L7 (Paseo de la Reforma)
    // ni en L4 (Eje Central), a pesar de compartir nombre de estación.
    private static final LatLng L1_REFORMA = new LatLng(19.4328, -99.15879);
    private static final LatLng L7_REFORMA = new LatLng(19.43143, -99.15871);
    private static final LatLng L1_HAMBURGO = new LatLng(19.42771, -99.16119);
    private static final LatLng L7_HAMBURGO = new LatLng(19.42995, -99.1616);
    private static final LatLng L1_PLAZA_REPUBLICA = new LatLng(19.43597, -99.15739);
    private static final LatLng L4_PLAZA_REPUBLICA = new LatLng(19.4368, -99.15414);

    @Test
    public void reforma_L1vsL7_estanALaMismaDistancia_mayorQueElRadio() {
        double d = MapFragment.distanciaMismaParada(L1_REFORMA, L7_REFORMA);
        assertTrue("distancia real ~153 m, debe superar el radio de 100 m", d > MapFragment.RADIO_MISMA_PARADA_M);
    }

    @Test
    public void hamburgo_L1vsL7_superaElRadio() {
        double d = MapFragment.distanciaMismaParada(L1_HAMBURGO, L7_HAMBURGO);
        assertTrue("distancia real ~253 m, debe superar el radio de 100 m", d > MapFragment.RADIO_MISMA_PARADA_M);
    }

    @Test
    public void plazaDeLaRepublica_L1vsL4_superaElRadio() {
        double d = MapFragment.distanciaMismaParada(L1_PLAZA_REPUBLICA, L4_PLAZA_REPUBLICA);
        assertTrue("distancia real ~353 m, debe superar el radio de 100 m", d > MapFragment.RADIO_MISMA_PARADA_M);
    }

    // Caso de control: una estación real y SU MISMA posición (correspondencia física legítima,
    // p. ej. el mismo andén servido por dos trazados) debe seguir EXENTA (distancia 0 ≤ radio).
    @Test
    public void mismaPosicionExacta_siSigueExenta() {
        LatLng mismaUbicacion = new LatLng(19.4328, -99.15879);
        double d = MapFragment.distanciaMismaParada(L1_REFORMA, mismaUbicacion);
        assertEquals(0.0, d, 0.001);
        assertTrue(d <= MapFragment.RADIO_MISMA_PARADA_M);
    }

    // Caso de control: una diferencia pequeña, propia de redondeo de datasets para EL MISMO andén
    // (decenas de metros), debe seguir dentro del radio.
    @Test
    public void diferenciaPequena_dentroDelRadio_sigueExenta() {
        LatLng cercaMismoAnden = new LatLng(19.43283, -99.15882); // ~4 m de L1_REFORMA
        double d = MapFragment.distanciaMismaParada(L1_REFORMA, cercaMismoAnden);
        assertTrue("una diferencia de pocos metros no debe tratarse como estación distinta",
                d <= MapFragment.RADIO_MISMA_PARADA_M);
    }
}
