package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Diagnóstico (no cobertura de procesarInterno completo -- esa función es privada y depende de
 * estado de instancia de un android.app.Service real, no testeable sin Robolectric). Mide, con las
 * funciones REALMENTE accesibles desde test (Planificador.distanciaZona() público; RecorridoService.
 * coUbicada()/radioLlegada() package-private), la geometría real de las 4 plataformas de Indios
 * Verdes contra los 4 puntos de "zona de cobertura" que dio el usuario (coordenadas medidas en el
 * CETRAM real, no las de las constantes IV_*_LLEGA/SALE -- esas son puntos distintos, más precisos
 * para describir dónde empieza/termina cada zona peatonal).
 *
 * Objetivo: confirmar a qué andén REAL corresponde cada punto de cobertura, y si -- geométricamente
 * -- el punto de paso cercano al andén "equivocado" cae dentro del radio que, sin los resguardos de
 * estado (alcanzasteAnterior/zonaAmbigua/cruzaZona en RecorridoService.procesarInterno), sonaría de
 * más. No reemplaza una simulación completa del estado (ultLlegando/avanceMin/corresPendienteCiclos),
 * que requeriría Robolectric para instanciar el Service.
 */
public class IndiosVerdesZonaCoberturaDiagnosticoTest {

    private static final int COLOR = 0xFF000000;

    private static Planificador.Parada parada(String nombre, int linea, LatLng pos) {
        return new Planificador.Parada(nombre, linea, COLOR, false, pos, "ic_est");
    }

    private static final Planificador.Parada L1_LLEGA =
            parada("Indios Verdes", 1, Planificador.IV_L1_LLEGA);
    private static final Planificador.Parada L1_SALE =
            parada("Indios Verdes", 1, Planificador.IV_L1_SALE);
    private static final Planificador.Parada L4_LLEGA =
            parada("Indios Verdes (conexión Metrobús L1 y L7)", 104, Planificador.IV_L4_LLEGA);
    private static final Planificador.Parada L4_SALE =
            parada("Indios Verdes (conexión Metrobús L1 y L7)", 104, Planificador.IV_L4_SALE);

    // Puntos de "zona de cobertura" dados por el usuario (CETRAM real).
    private static final double[] MB_NORTE_A_SUR  = {19.49678, -99.11932};
    private static final double[] MXB_NORTE_A_SUR = {19.493614, -99.120000};
    private static final double[] MB_SUR_A_NORTE   = {19.493833, -99.119751};
    private static final double[] MXB_SUR_A_NORTE  = {19.496624, -99.119039};

    private static double dz(Planificador.Parada p, double[] punto) {
        return Planificador.distanciaZona(p, punto[0], punto[1]);
    }

    @Test
    public void todasLas4ParadasTienenZona() {
        assertTrue(Planificador.tieneZona(L1_LLEGA));
        assertTrue(Planificador.tieneZona(L1_SALE));
        assertTrue(Planificador.tieneZona(L4_LLEGA));
        assertTrue(Planificador.tieneZona(L4_SALE));
    }

    @Test
    public void nortaASur_puntoMB_correspondeAlAndenL1Sale() {
        double aL1Sale = dz(L1_SALE, MB_NORTE_A_SUR);
        double aL1Llega = dz(L1_LLEGA, MB_NORTE_A_SUR);
        double aL4Sale = dz(L4_SALE, MB_NORTE_A_SUR);
        double aL4Llega = dz(L4_LLEGA, MB_NORTE_A_SUR);
        System.out.println("MB(norte->sur) dist a L1_SALE=" + aL1Sale + " L1_LLEGA=" + aL1Llega
                + " L4_SALE=" + aL4Sale + " L4_LLEGA=" + aL4Llega);
        // El punto dado por el usuario para "paso cerca del andén de metrobús" debe caer, de las 4
        // plataformas, más cerca de IV_L1_SALE (andén de ascenso norte) que de cualquier otra -- es
        // justo el andén que se usa para abordar L1 rumbo al sur.
        assertTrue("debe ser la plataforma MÁS cercana de las 4",
                aL1Sale < aL1Llega && aL1Sale < aL4Sale && aL1Sale < aL4Llega);
        assertTrue("dentro del radio ancho de zona (ZONA_CERCA_M=70 m, ver RecorridoService L236)",
                aL1Sale <= 70.0);
    }

    @Test
    public void nortaASur_puntoMXB_correspondeAlAndenL4Llega() {
        double aL4Llega = dz(L4_LLEGA, MXB_NORTE_A_SUR);
        double aL4Sale = dz(L4_SALE, MXB_NORTE_A_SUR);
        double aL1Sale = dz(L1_SALE, MXB_NORTE_A_SUR);
        double aL1Llega = dz(L1_LLEGA, MXB_NORTE_A_SUR);
        System.out.println("MXB(norte->sur) dist a L4_LLEGA=" + aL4Llega + " L4_SALE=" + aL4Sale
                + " L1_SALE=" + aL1Sale + " L1_LLEGA=" + aL1Llega);
        assertTrue("debe ser la plataforma MÁS cercana de las 4",
                aL4Llega < aL4Sale && aL4Llega < aL1Sale && aL4Llega < aL1Llega);
        assertTrue("dentro del radio ANGOSTO de llegada (RADIO_LLEGADA_ZONA_M=10 m, ver "
                + "RecorridoService L217) -- confirma que es genuinamente el andén de Mexibús, no solo la zona ancha",
                aL4Llega <= 10.0);
    }

    @Test
    public void nortaASur_enElPuntoMB_elRiesgoDeSonarDeMasExisteSinLosResguardosDeEstado() {
        // Comparación CRUDA (sin alcanzasteAnterior/zonaAmbigua/cruzaZona -- esos viven en
        // procesarInterno, privado, no invocable desde test): en el punto MB, ¿ya estás más cerca de
        // L1_SALE que de L4_LLEGA (la plataforma real donde debes anunciar la llegada de Mexibús)?
        double aL1Sale = dz(L1_SALE, MB_NORTE_A_SUR);
        double aL4Llega = dz(L4_LLEGA, MB_NORTE_A_SUR);
        System.out.println("MB(norte->sur): aL1Sale=" + aL1Sale + " aL4Llega=" + aL4Llega
                + " (si aL1Sale < aL4Llega, el resguardo de estado -- no la geometría -- es lo único"
                + " que evita que suene aquí)");
        assertTrue("confirma que, geométricamente, el punto MB SÍ está más cerca de L1_SALE que de "
                + "L4_LLEGA -- el motivo real de que no suene ahí es el resguardo de ESTADO "
                + "(alcanzasteAnterior exige haber anunciado antes la llegada a L4_LLEGA), no la zona",
                aL1Sale < aL4Llega);
    }

    @Test
    public void surANorte_puntoMB_correspondeAlAndenL1Llega() {
        double aL1Llega = dz(L1_LLEGA, MB_SUR_A_NORTE);
        double aL1Sale = dz(L1_SALE, MB_SUR_A_NORTE);
        double aL4Llega = dz(L4_LLEGA, MB_SUR_A_NORTE);
        double aL4Sale = dz(L4_SALE, MB_SUR_A_NORTE);
        System.out.println("MB(sur->norte) dist a L1_LLEGA=" + aL1Llega + " L1_SALE=" + aL1Sale
                + " L4_LLEGA=" + aL4Llega + " L4_SALE=" + aL4Sale);
        assertTrue("debe ser la plataforma MÁS cercana de las 4",
                aL1Llega < aL1Sale && aL1Llega < aL4Llega && aL1Llega < aL4Sale);
        assertTrue("dentro del radio ancho de zona (70 m)", aL1Llega <= 70.0);
    }

    @Test
    public void surANorte_puntoMXB_correspondeAlAndenL4Sale() {
        double aL4Sale = dz(L4_SALE, MXB_SUR_A_NORTE);
        double aL4Llega = dz(L4_LLEGA, MXB_SUR_A_NORTE);
        double aL1Sale = dz(L1_SALE, MXB_SUR_A_NORTE);
        double aL1Llega = dz(L1_LLEGA, MXB_SUR_A_NORTE);
        System.out.println("MXB(sur->norte) dist a L4_SALE=" + aL4Sale + " L4_LLEGA=" + aL4Llega
                + " L1_SALE=" + aL1Sale + " L1_LLEGA=" + aL1Llega);
        assertTrue("debe ser la plataforma MÁS cercana de las 4",
                aL4Sale < aL4Llega && aL4Sale < aL1Sale && aL4Sale < aL1Llega);
        assertTrue("dentro del radio angosto de llegada (10 m)", aL4Sale <= 10.0);
    }

    @Test
    public void l1SaleYL4LlegaSonCoUbicadas_porNucleoYDistancia() {
        // Confirma que coUbicada() (la que habilita el radio ancho de cruce, zonaAmbigua) SÍ los trata
        // como la misma correspondencia real -- necesario para que el bloque de cruce de
        // procesarInterno aplique en este par (si no fueran coUbicada(), ni siquiera entraría ese
        // bloque, y el umbral sería CAMBIO_LINEA_M=15 m en vez de 70 m).
        assertTrue(RecorridoService.coUbicada(L1_SALE, L4_LLEGA));
        assertTrue(RecorridoService.coUbicada(L1_LLEGA, L4_SALE));
    }

    @Test
    public void radioLlegada_esAngostoParaLas4Plataformas() {
        assertEquals(10f, RecorridoService.radioLlegada(L1_LLEGA), 0.001f);
        assertEquals(10f, RecorridoService.radioLlegada(L1_SALE), 0.001f);
        assertEquals(10f, RecorridoService.radioLlegada(L4_LLEGA), 0.001f);
        assertEquals(10f, RecorridoService.radioLlegada(L4_SALE), 0.001f);
    }
}
