package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado (Mexibús L4 sur viniendo del norte, p. ej. Clínica 76 -> Indios Verdes): el
 * corredor de cobertura de Indios Verdes (Planificador.Z_IV_L4_LLEGA, ~112 m) tiene su extremo NORTE a
 * solo ~50 m del andén de Metrobús L1 sur -- MENOS que el radio ancho que antes se usaba para el aviso
 * de "llegando" (ZONA_CERCA_M=70 m). El aviso de "llegando a tu Mexibús" podía sonar estando aún a la
 * altura del andén de Metrobús, no genuinamente sobre el andén de Mexibús. Se angostó el radio SOLO
 * para ese aviso propio ({@link RecorridoService#radioLlegada}, hecho package-private para esto, mismo
 * trato que {@code Planificador.corrManual()}/{@code subRuta()}), dejando intacto el radio ancho que
 * usa la lógica de cruce/correspondencia (radioCruce()/radioCerca(), sin cambios).
 *
 * El radio angosto depende de qué tan preciso sea el corredor registrado; simulado contra el shape
 * OFICIAL de Mexibús L4, el GPS nunca cae dentro de los 10 m (pasa a 11.8 m del corredor). Por eso se
 * agregó {@link RecorridoService#llegandoPorSalidaDeZona} como respaldo: si ya entraste a la zona
 * ANCHA (70 m, fiable) y tu distancia actual ya creció sobre el mínimo alcanzado, cuenta como llegada
 * aunque nunca hayas caído en el radio angosto. Gateado por tieneZona(): nunca aplica a Puente de
 * Fierro (sin zona).
 */
public class RecorridoServiceRadioLlegadaTest {

    private static Planificador.Parada parada(int linea, com.google.android.gms.maps.model.LatLng pos) {
        return new Planificador.Parada("Indios Verdes", linea, 0, false, pos, null);
    }

    @Test
    public void zonaIndiosVerdes_radioAngosto10m_noElAnchoDe70() {
        Planificador.Parada p = parada(104, Planificador.IV_L4_LLEGA);
        assertEquals(10f, RecorridoService.radioLlegada(p), 0.001f);
    }

    @Test
    public void zonaIndiosVerdes_L1_tambienRadioAngosto() {
        Planificador.Parada p = parada(1, Planificador.IV_L1_SALE);
        assertEquals(10f, RecorridoService.radioLlegada(p), 0.001f);
    }

    @Test
    public void paradaMexibusNormal_sinZona_comportamientoAnteriorIntacto() {
        // Una parada Mexibús cualquiera sin zona (ni andén largo/CEDA/Clínica): debe seguir devolviendo
        // el radio normal de Mexibús (50 m), igual que antes -- el fix NO debe tocar ningún otro caso.
        Planificador.Parada p = parada(104, new com.google.android.gms.maps.model.LatLng(19.6, -99.05));
        assertEquals(50f, RecorridoService.radioLlegada(p), 0.001f);
    }

    @Test
    public void paradaMetrobusNormal_sinZona_comportamientoAnteriorIntacto() {
        Planificador.Parada p = parada(1, new com.google.android.gms.maps.model.LatLng(19.4, -99.17));
        assertEquals(50f, RecorridoService.radioLlegada(p), 0.001f);
    }

    @Test
    public void llegandoPorSalida_zonaIndiosVerdes_entroYSeAleja_disparaAunqueNuncaCupoEnElRadioAngosto() {
        // Caso real confirmado por simulación: el shape oficial de Mexibús L4 llega a 11.8 m del
        // corredor (más que el radio angosto de 10 m) pero sí entra a la zona ancha (70 m). Si ya
        // alcanzaste ese mínimo (distMin=11.8) y ahora bd creció por encima del margen, cuenta como
        // llegada.
        Planificador.Parada p = parada(104, Planificador.IV_L4_LLEGA);
        assertTrue(RecorridoService.llegandoPorSalidaDeZona(p, 20f, 11.8f));
    }

    @Test
    public void llegandoPorSalida_zonaIndiosVerdes_todaviaAcercandose_noDispara() {
        // bd sigue bajando (o igual al mínimo): todavía te estás acercando, no alejando -- no debe
        // disparar por este camino (si acaso, por el radio angosto normal).
        Planificador.Parada p = parada(104, Planificador.IV_L4_LLEGA);
        assertFalse(RecorridoService.llegandoPorSalidaDeZona(p, 11.8f, 11.8f));
        assertFalse(RecorridoService.llegandoPorSalidaDeZona(p, 10f, 11.8f));
    }

    @Test
    public void llegandoPorSalida_zonaIndiosVerdes_margenPequenoNoCuenta_evitaRuidoDeGps() {
        // Un crecimiento menor al margen (5 m) es ruido normal de GPS rondando el mínimo, no una
        // salida real de la zona.
        Planificador.Parada p = parada(104, Planificador.IV_L4_LLEGA);
        assertFalse(RecorridoService.llegandoPorSalidaDeZona(p, 14f, 11.8f));
    }

    @Test
    public void llegandoPorSalida_nuncaEntroALaZonaAncha_noDispara() {
        // distMin > 70 m: ni siquiera llegaste a entrar a la zona ancha -- no hay "salida" que detectar.
        Planificador.Parada p = parada(104, Planificador.IV_L4_LLEGA);
        assertFalse(RecorridoService.llegandoPorSalidaDeZona(p, 90f, 80f));
    }

    @Test
    public void llegandoPorSalida_paradaSinZona_nuncaDispara_puenteDeFierroIntacto() {
        // Puente de Fierro (andén largo, SIN zona): esta lógica nunca debe aplicarle, sin importar los
        // valores de bd/distMin.
        Planificador.Parada p = parada(104, new com.google.android.gms.maps.model.LatLng(19.603559, -99.033337));
        assertFalse(RecorridoService.llegandoPorSalidaDeZona(p, 200f, 50f));
    }
}
