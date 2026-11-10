package com.memegrados.GeoMB;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Prueba {@link Planificador#noPreferido(int, Map)} con la implementación ORIGINAL (rangos sobre
 * el entero: solo Mexibús 100-199, ramal 111-119 excluido, exprés 121-124). Casos reales de
 * preferencia de servicio -- detecta cualquier cambio si se reescribe con LineaId.
 */
public class PlanificadorNoPreferidoTest {

    @Test
    public void sin_preferencia_nunca_penaliza() {
        assertFalse(Planificador.noPreferido(101, null));
        assertFalse(Planificador.noPreferido(121, new HashMap<>()));
    }

    @Test
    public void solo_aplica_a_troncales_mexibus_ordinario_o_expres() {
        Map<Integer, Boolean> pref = new HashMap<>();
        pref.put(101, true);   // quiere exprés en la base L1
        // Metrobús y Mexicable: nunca aplica, aunque haya preferencia puesta.
        assertFalse(Planificador.noPreferido(7, pref));
        assertFalse(Planificador.noPreferido(201, pref));
        // Ramal (111-113): sin exprés, nunca aplica.
        assertFalse(Planificador.noPreferido(111, pref));
    }

    @Test
    public void detecta_coincidencia_y_discrepancia_con_la_preferencia() {
        Map<Integer, Boolean> quiereExpres = new HashMap<>();
        quiereExpres.put(101, true);
        assertFalse("exprés pedido, línea exprés: coincide", Planificador.noPreferido(121, quiereExpres));
        assertTrue("exprés pedido, línea ordinaria: no coincide", Planificador.noPreferido(101, quiereExpres));

        Map<Integer, Boolean> quiereOrdinario = new HashMap<>();
        quiereOrdinario.put(101, false);
        assertFalse("ordinario pedido, línea ordinaria: coincide", Planificador.noPreferido(101, quiereOrdinario));
        assertTrue("ordinario pedido, línea exprés: no coincide", Planificador.noPreferido(121, quiereOrdinario));
    }

    @Test
    public void preferencia_de_otra_base_no_aplica() {
        Map<Integer, Boolean> pref = new HashMap<>();
        pref.put(102, true);   // preferencia puesta para la base L2, no L1
        assertFalse(Planificador.noPreferido(101, pref));
        assertFalse(Planificador.noPreferido(121, pref));
    }
}
