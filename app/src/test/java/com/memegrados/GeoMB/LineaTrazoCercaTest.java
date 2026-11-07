package com.memegrados.GeoMB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Distancia perpendicular al trazo alrededor de una estación (base de RecorridoService.sobreTrazo). */
public class LineaTrazoCercaTest {

    private static final double M_LAT = 111320.0;
    private static final double M_LON = 111320.0 * Math.cos(Math.toRadians(19.4));

    /** Trazo recto de ~2 km de oeste a este en lat 19.45. */
    private static final List<LatLng> TRAZO = Arrays.asList(
            new LatLng(19.45, -99.13), new LatLng(19.45, -99.12), new LatLng(19.45, -99.11));
    private static final LatLng ESTACION = new LatLng(19.45, -99.12);

    @Test
    public void puntoAlNorteDelTrazoMideSuSeparacion() {
        LatLng p = new LatLng(19.45 + 30 / M_LAT, -99.12 + 100 / M_LON);
        assertEquals(30.0, Linea.distPerpCerca(TRAZO, p, ESTACION, 600), 0.5);
    }

    @Test
    public void puntoSobreElTrazoEsCero() {
        LatLng p = new LatLng(19.45, -99.12 - 250 / M_LON);
        assertEquals(0.0, Linea.distPerpCerca(TRAZO, p, ESTACION, 600), 0.5);
    }

    @Test
    public void tramosLejosDeLaEstacionNoCuentan() {
        LatLng lejos = new LatLng(19.40, -99.20);   // el trazo no pasa a menos de 600 m de esta "estación"
        LatLng p = new LatLng(19.45, -99.12);
        assertTrue(Linea.distPerpCerca(TRAZO, p, lejos, 600) == Double.MAX_VALUE);
    }

    @Test
    public void sinTrazoDevuelveMaximo() {
        assertTrue(Linea.distPerpCerca(null, ESTACION, ESTACION, 600) == Double.MAX_VALUE);
    }
}
