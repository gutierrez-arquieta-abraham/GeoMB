package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (viaje real, Mexibús→Metrobús): el aviso de "correspondencia corta" en
 * Indios Verdes (ver RecorridoService) seguía disparando de más, consistente con ruido/multipath
 * del GPS típico de estructuras elevadas -- aun cumpliendo el radio de 70 m del andén nuevo en 2
 * ciclos de GPS seguidos. Se agregó un requisito adicional, independiente del radio de cada andén:
 * haber pasado por la zona CENTRAL que conecta las 4 plataformas (Mexibús L4 norte/sur, Metrobús L1
 * norte/sur). Se prueba contra las funciones reales (públicas para esto, mismo trato que
 * tieneZona()/esTerminalDe()).
 */
public class PlanificadorIndiosVerdesZonaCentralTest {

    @Test
    public void esPlataformaIndiosVerdes_las4Plataformas_true() {
        assertTrue(Planificador.esPlataformaIndiosVerdes(parada(Planificador.IV_L1_SALE)));
        assertTrue(Planificador.esPlataformaIndiosVerdes(parada(Planificador.IV_L1_LLEGA)));
        assertTrue(Planificador.esPlataformaIndiosVerdes(parada(Planificador.IV_L4_LLEGA)));
        assertTrue(Planificador.esPlataformaIndiosVerdes(parada(Planificador.IV_L4_SALE)));
    }

    @Test
    public void esPlataformaIndiosVerdes_otraEstacion_false() {
        // Puente de Fierro: otra correspondencia corta, pero SIN la geometría de 4 plataformas --
        // el requisito de zona central no debe aplicarle.
        assertFalse(Planificador.esPlataformaIndiosVerdes(
                parada(new LatLng(19.603559, -99.033337))));
    }

    @Test
    public void esPlataformaIndiosVerdes_paradaNull_false() {
        assertFalse(Planificador.esPlataformaIndiosVerdes(null));
    }

    @Test
    public void enZonaCentralIndiosVerdes_enElCentroide_true() {
        assertTrue(Planificador.enZonaCentralIndiosVerdes(Planificador.IV_ZONA_CENTRAL));
    }

    @Test
    public void enZonaCentralIndiosVerdes_sobreUnAnden_false() {
        // Las 4 plataformas quedan a 122-182 m del centroide -- fuera del radio de 100 m: estar
        // PARADO en un andén no debe contar como "ya pasó por la zona central".
        assertFalse(Planificador.enZonaCentralIndiosVerdes(Planificador.IV_L1_SALE));
        assertFalse(Planificador.enZonaCentralIndiosVerdes(Planificador.IV_L4_LLEGA));
    }

    @Test
    public void enZonaCentralIndiosVerdes_lejos_false() {
        assertFalse(Planificador.enZonaCentralIndiosVerdes(new LatLng(19.6, -99.05)));
    }

    @Test
    public void enZonaCentralIndiosVerdes_posicionNull_false() {
        assertFalse(Planificador.enZonaCentralIndiosVerdes(null));
    }

    private static Planificador.Parada parada(LatLng pos) {
        return new Planificador.Parada("Indios Verdes", 104, 0, false, pos, null);
    }
}
