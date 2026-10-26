package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Nuevo campo Parada.cambioServicio (pedido por el usuario): mostrar/anunciar el cambio
 * ordinario↔exprés de una misma línea base (p. ej. L4 104↔124) en el deslizador de rutas
 * (EstacionRutaAdapter), sin afectar 'transbordo' (que a propósito es false para este caso --
 * ver el comentario en Planificador.calcular()). La condición real que arma 'secuencia' usa
 * claveTerminal(linea) para decidir si dos paradas consecutivas son el mismo "servicio base";
 * se prueba esa pieza real (pública para esto, mismo trato que otras funciones puras de este
 * archivo) ya que el bucle completo que arma 'secuencia' necesita Context (no testeable sin
 * Robolectric, que este proyecto no usa).
 */
public class PlanificadorCambioServicioTest {

    @Test
    public void claveTerminal_L4OrdinarioYExpres_mismaClave() {
        assertEquals(Planificador.claveTerminal(104), Planificador.claveTerminal(124));
    }

    @Test
    public void claveTerminal_L4YOtraLinea_clavesDistintas() {
        assertNotEquals(Planificador.claveTerminal(104), Planificador.claveTerminal(101));
    }

    @Test
    public void parada_constructorCorto_cambioServicioFalsePorDefecto() {
        Planificador.Parada p = new Planificador.Parada("X", 104, 0, false,
                new com.google.android.gms.maps.model.LatLng(19.4, -99.1), null);
        assertFalse(p.cambioServicio);
    }

    @Test
    public void parada_constructorLargo_cambioServicioSePropaga() {
        Planificador.Parada p = new Planificador.Parada("X", 124, 0, false, true,
                new com.google.android.gms.maps.model.LatLng(19.4, -99.1), null);
        assertTrue(p.cambioServicio);
        assertFalse(p.transbordo);   // confirma que son campos independientes
    }
}
