package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado en dispositivo: al hacer un recorrido L4 Express -> L2 Mexibús cruzando
 * por "Puente de Fierro", RecorridoService se quedaba mudo (ni "llegando" ni "transbordo") y el
 * seguimiento terminaba saltando directo a "UPE" vía reanclarOtraLinea(), saltándose Puente de
 * Fierro Y Casa de Morelos. Causa raíz: Planificador.ANDENES_LARGOS solo traía el punto de Puente
 * de Fierro del lado L4 -- el de L2 queda a 284 m real de distancia (misma estación por nombre,
 * dos plataformas físicas distintas) y nunca estuvo registrado, así que
 * RecorridoService.radioCerca() usaba el radio angosto por defecto (50 m) en vez del amplio de
 * andén largo (90 m) para ese lado del cruce.
 *
 * <p>{@link Planificador#andenLargo} es público y estático (no toca Context/Android), así que se
 * prueba directamente contra la función real, sin reproducir su lógica a mano.
 */
public class PlanificadorAndenLargoTest {

    private static Planificador.Parada paradaEn(double lat, double lon) {
        return new Planificador.Parada("Puente de Fierro", 102, 0xFFFFFFFF, false, new LatLng(lat, lon), "");
    }

    @Test
    public void andenLargo_puenteDeFierroL4_siCalifica() {
        assertTrue("el punto de L4 (ya registrado desde antes) debe seguir calificando",
                Planificador.andenLargo(paradaEn(19.603558838994957, -99.033337377486620)));
    }

    @Test
    public void andenLargo_puenteDeFierroL2_siCalifica() {
        // Coordenada real de "MXB Puente de Fierro" en la línea 102 (mexibus.json) -- defecto
        // confirmado: antes de este fix, este punto no calificaba (quedaba a 284 m del de L4, fuera
        // del radio de 30 m que usa andenLargo() para decidir "es el mismo punto registrado").
        assertTrue("el punto de L2 debe calificar ahora -- es la causa raíz del aviso mudo real",
                Planificador.andenLargo(paradaEn(19.601038, -99.033787)));
    }

    @Test
    public void andenLargo_casaDeMorelos_noCalifica() {
        // Control: Casa de Morelos (una parada real distinta, cercana pero NO el mismo andén) no debe
        // calificar como andén largo -- confirma que el radio de 30 m no se infló de más.
        assertFalse(Planificador.andenLargo(paradaEn(19.599582, -99.035558)));
    }

    @Test
    public void andenLargo_upe_noCalifica() {
        assertFalse(Planificador.andenLargo(paradaEn(19.601909, -99.041841)));
    }
}
