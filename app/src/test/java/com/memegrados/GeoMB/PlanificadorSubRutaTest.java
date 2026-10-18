package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado: el trazo de L2A/L3A (Mexibús, circuito de un solo sentido -- ver
 * Planificador.dirRoute("L112o"/"L113o") y su cierre last→first en el grafo) quedaba incompleto o
 * iba por el lado equivocado del mapa cuando el tramo envolvía el punto de cierre del circuito (p.
 * ej. aborda en un índice del shape y baja en uno ANTERIOR, dando toda la vuelta). subRuta() asumía
 * que ir "hacia atrás" en el shape (sa > sb) siempre significa retroceder -- válido para líneas
 * bidireccionales normales, pero en un circuito de un sentido eso no existe: hay que seguir hacia
 * ADELANTE hasta el final del shape y envolver al principio. Se prueba contra la función real
 * (package-private para esto, mismo trato que corrManual()/nucleoCoincide()).
 */
public class PlanificadorSubRutaTest {

    // Shape sintético de 5 puntos en línea recta (como el "ruta" de una línea real): el círculo lo
    // cierra el GRAFO (arista last→first), no el propio arreglo de puntos -- igual que L2A real.
    private static final List<LatLng> SHAPE = java.util.Arrays.asList(
            new LatLng(19.000, -99.000),   // índice 0
            new LatLng(19.001, -99.000),   // índice 1
            new LatLng(19.002, -99.000),   // índice 2
            new LatLng(19.003, -99.000),   // índice 3
            new LatLng(19.004, -99.000));  // índice 4

    @Test
    public void subRuta_circuito_envuelveHaciaAdelante_noRetrocede() {
        // a cae en el segmento [2,3] (sa=2), b cae en el segmento [0,1] (sb=0): sa > sb.
        LatLng a = new LatLng(19.0029, -99.000);
        LatLng b = new LatLng(19.0001, -99.000);
        List<LatLng> out = Planificador.subRuta(SHAPE, a, b, true);
        // Debe seguir ADELANTE (índices 3, 4) y envolver al principio (índice 0) -- nunca retroceder
        // por los índices intermedios (2, 1) que quedan "detrás" de a en el sentido de viaje.
        assertTrue("debe incluir el punto índice 3 (sigue hacia adelante)", out.contains(SHAPE.get(3)));
        assertTrue("debe incluir el punto índice 4 (hasta el final del shape)", out.contains(SHAPE.get(4)));
        assertTrue("debe incluir el punto índice 0 (envuelve al principio)", out.contains(SHAPE.get(0)));
        assertFalse("NO debe retroceder por el índice 1 (eso no existe en un solo sentido)",
                out.contains(SHAPE.get(1)));
    }

    @Test
    public void subRuta_sinCircuito_mismoCaso_retrocedeComoAntes() {
        // Mismo par a/b, pero SIN circuito (líneas bidireccionales normales): conserva el
        // comportamiento anterior (retrocede), para no cambiar nada fuera de L2A/L3A.
        LatLng a = new LatLng(19.0029, -99.000);
        LatLng b = new LatLng(19.0001, -99.000);
        List<LatLng> out = Planificador.subRuta(SHAPE, a, b, false);
        assertTrue("debe retroceder por el índice 2", out.contains(SHAPE.get(2)));
        assertTrue("debe retroceder por el índice 1", out.contains(SHAPE.get(1)));
        assertFalse("no debe envolver al índice 4", out.contains(SHAPE.get(4)));
    }

    @Test
    public void subRuta_sentidoNormal_igualConOSinCircuito() {
        // a antes que b (sa <= sb): el flag circuito no debe cambiar nada, es el caso normal.
        LatLng a = new LatLng(19.0001, -99.000);
        LatLng b = new LatLng(19.0039, -99.000);
        List<LatLng> conCircuito = Planificador.subRuta(SHAPE, a, b, true);
        List<LatLng> sinCircuito = Planificador.subRuta(SHAPE, a, b, false);
        assertEquals(sinCircuito, conCircuito);
    }
}
