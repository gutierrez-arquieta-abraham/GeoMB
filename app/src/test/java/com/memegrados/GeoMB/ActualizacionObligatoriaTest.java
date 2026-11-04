package com.memegrados.GeoMB;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ActualizacionObligatoria#forzarEnMemoria} es la parte SIN Context (pura) del bloqueo:
 * la usa {@link Backend} al recibir un 426 en cualquier petición, para marcar
 * {@link ActualizacionObligatoria#requerida()} de inmediato sin esperar al próximo refrescar().
 * Estado estático: se resetea en @Before/@After, mismo trato que ManifestacionesBloqueadasTest.
 */
public class ActualizacionObligatoriaTest {

    private static final int ACTUAL = BuildConfig.VERSION_CODE;

    @Before
    public void antes() { ActualizacionObligatoria.resetParaPruebas(); }

    @After
    public void despues() { ActualizacionObligatoria.resetParaPruebas(); }

    @Test
    public void sinMinimaConfigurada_noRequerida() {
        assertFalse(ActualizacionObligatoria.requerida());
    }

    @Test
    public void minimaCero_seIgnora_noRequerida() {
        assertFalse(ActualizacionObligatoria.forzarEnMemoria(0));
        assertFalse(ActualizacionObligatoria.requerida());
    }

    @Test
    public void minimaIgualOMenorQueLaActual_noRequerida() {
        assertFalse(ActualizacionObligatoria.forzarEnMemoria(ACTUAL));
        assertFalse(ActualizacionObligatoria.requerida());
        assertFalse(ActualizacionObligatoria.forzarEnMemoria(ACTUAL - 1));
        assertFalse(ActualizacionObligatoria.requerida());
    }

    @Test
    public void minimaMayorQueLaActual_pasaARequerida_devuelveTrueSoloElCambio() {
        assertTrue(ActualizacionObligatoria.forzarEnMemoria(ACTUAL + 1));   // false -> true: cambió
        assertTrue(ActualizacionObligatoria.requerida());
        assertFalse(ActualizacionObligatoria.forzarEnMemoria(ACTUAL + 1));  // ya era true: no "cambió"
        assertTrue(ActualizacionObligatoria.requerida());   // pero sigue requerida
    }

    @Test
    public void minimaNuncaBaja_unaVecesVisto_elMasAltoGana() {
        ActualizacionObligatoria.forzarEnMemoria(ACTUAL + 5);
        ActualizacionObligatoria.forzarEnMemoria(ACTUAL + 1);   // menor que la ya vista: se ignora
        assertTrue(ActualizacionObligatoria.requerida());   // sigue bloqueada (el mínimo no "baja" solo)
    }
}
