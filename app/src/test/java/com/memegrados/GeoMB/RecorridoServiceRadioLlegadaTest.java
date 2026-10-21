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
 * NOTA sobre un respaldo REVERTIDO: se probó un {@code llegandoPorSalidaDeZona()} (disparar "llegando"
 * al detectar que ya entraste a la zona ancha y tu distancia empezó a crecer de nuevo), pensado para
 * cuando el shape oficial de Mexibús L4 nunca cae dentro del radio angosto (pasa a 11.8 m del corredor,
 * simulado). Confirmado con video real que esa heurística seguía disparando PREMATURO en el mismo punto
 * de siempre (el mínimo local se alcanza mucho antes del andén real) -- y ese disparo temprano
 * encadenaba un salto inmediato a la línea nueva (satisface alcanzasteAnterior en el bloque de cruce),
 * saltándose el aviso de "próxima: Indios Verdes" del lado Metrobús por completo. Se quitó: mejor que el
 * aviso a veces no dispare por imprecisión de GPS (silencioso) a que dispare mal y arrastre ese efecto
 * dominó. Solo queda el radio angosto.
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
    public void puenteDeFierro_radioAndenLargo90m_nuncaElAngostoDeIndiosVerdes() {
        // Confirma explícitamente lo que pidió el usuario al revertir llegandoPorSalidaDeZona(): Puente
        // de Fierro (andén largo, SIN zona) sigue con su propio radio de 90 m -- nunca el angosto de 10 m
        // de Indios Verdes (tieneZona() es falso para él, así que cae directo a radioCerca()).
        Planificador.Parada p = parada(104, new com.google.android.gms.maps.model.LatLng(19.603559, -99.033337));
        assertEquals(90f, RecorridoService.radioLlegada(p), 0.001f);
    }
}
