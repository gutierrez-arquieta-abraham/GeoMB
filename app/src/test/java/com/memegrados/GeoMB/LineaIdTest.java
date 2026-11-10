package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Prueba {@link LineaId}: que el redondeo entero->estructura->entero sea exacto para TODOS los
 * códigos legacy reales del proyecto (ver CLAUDE.md), que coincida con {@link Sistemas} (misma
 * clasificación, ninguna de las dos se tocó para que la otra cambiara de comportamiento) y que
 * {@link LineaId#etiqueta()} coincida con la etiqueta corta real ya pública en
 * {@link Planificador#etiquetaLineaCortaPub(int)}.
 */
public class LineaIdTest {

    // Todos los códigos reales del proyecto: Metrobús 1-7, Mexibús ordinario 101-104,
    // ramal 111-113, exprés 121-124, Mexicable 201-202.
    private static final int[] CODIGOS_REALES = {
            1, 2, 3, 4, 5, 6, 7,
            101, 102, 103, 104,
            111, 112, 113,
            121, 122, 123, 124,
            201, 202,
    };

    @Test
    public void redondeo_codigo_estructura_codigo_es_exacto_para_todos_los_codigos_reales() {
        for (int c : CODIGOS_REALES) {
            assertEquals("codigo " + c, c, LineaId.desde(c).codigo());
        }
    }

    @Test
    public void sistema_coincide_con_Sistemas_sistemaDe_para_todos_los_codigos_reales() {
        for (int c : CODIGOS_REALES) {
            int esperado = Sistemas.sistemaDe(c);
            LineaId.Sistema s = LineaId.desde(c).sistema;
            int obtenido = s == LineaId.Sistema.MEXICABLE ? Sistemas.MEXICABLE
                    : s == LineaId.Sistema.MEXIBUS ? Sistemas.MEXIBUS : Sistemas.METROBUS;
            assertEquals("sistema de " + c, esperado, obtenido);
        }
    }

    @Test
    public void numero_coincide_con_Sistemas_numeroDeLinea_para_todos_los_codigos_reales() {
        for (int c : CODIGOS_REALES) {
            assertEquals("numero de " + c, Sistemas.numeroDeLinea(c), LineaId.desde(c).numero);
        }
    }

    @Test
    public void etiqueta_coincide_con_etiquetaLineaCortaPub_para_todos_los_codigos_reales() {
        for (int c : CODIGOS_REALES) {
            String esperado = "L" + Planificador.etiquetaLineaCortaPub(c);
            assertEquals("etiqueta de " + c, esperado, LineaId.desde(c).etiqueta());
        }
    }

    @Test
    public void variante_ramal_y_expres_se_distinguen_aunque_colapsen_a_la_misma_etiqueta() {
        LineaId ramal = LineaId.desde(111);
        LineaId expres = LineaId.desde(121);
        assertEquals(LineaId.Variante.RAMAL, ramal.variante);
        assertEquals(LineaId.Variante.EXPRES, expres.variante);
        assertEquals(1, ramal.numero);
        assertEquals(1, expres.numero);
        // misma línea física (L1 Mexibús), variante distinta -- no son el mismo LineaId.
        assertNotEquals(ramal, expres);
        assertEquals(ramal.numero, expres.numero);
    }

    @Test
    public void de_construye_el_mismo_resultado_que_desde() {
        assertEquals(LineaId.desde(104), LineaId.de(LineaId.Sistema.MEXIBUS, 4));
        assertEquals(LineaId.desde(111), LineaId.de(LineaId.Sistema.MEXIBUS, 1, LineaId.Variante.RAMAL));
        assertEquals(LineaId.desde(201), LineaId.de(LineaId.Sistema.MEXICABLE, 1));
        assertEquals(LineaId.desde(7), LineaId.de(LineaId.Sistema.METROBUS, 7));
    }
}
