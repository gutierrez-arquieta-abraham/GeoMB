package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link Fragmento} es la pieza SIN Context (pura) de la voz fragmentada: el armado real de avisos
 * ({@code RecorridoService.avisoAbordajeFragmentos()}/{@code vozProxima()}/{@code vozLlegada()}) usa
 * {@code getString()} y datos de {@code GtfsRepository} (Context real), así que no se puede probar
 * aquí -- mismo límite ya documentado en {@code RecorridoServiceTerminalSentidoExpresTest} (sin
 * Robolectric/Mockito en este proyecto, construir cualquier cosa que toque Context truena). Esto
 * solo confirma que cada fábrica de Fragmento guarda el tipo/textos correctos, que es lo único
 * verificable sin Context.
 */
public class FragmentoTest {

    @Test
    public void legacy_guardaTipoTextoLineaYEstacion() {
        Fragmento f = Fragmento.legacy("Llegando a estación: Hidalgo", 1, "Hidalgo");
        assertEquals(Fragmento.LEGACY, f.tipo);
        assertEquals("Llegando a estación: Hidalgo", f.texto);
        assertEquals(1, f.linea);
        assertEquals("Hidalgo", f.estacion);
    }

    @Test
    public void estacion_guardaTipoYTexto_sinLineaNiEstacion() {
        Fragmento f = Fragmento.estacion("Indios Verdes");
        assertEquals(Fragmento.ESTACION, f.tipo);
        assertEquals("Indios Verdes", f.texto);
        assertEquals(0, f.linea);
        assertNull(f.estacion);
    }

    @Test
    public void comun_guardaTipoYTexto_sinLineaNiEstacion() {
        Fragmento f = Fragmento.comun("5 estaciones");
        assertEquals(Fragmento.COMUN, f.tipo);
        assertEquals("5 estaciones", f.texto);
        assertEquals(0, f.linea);
        assertNull(f.estacion);
    }

    @Test
    public void tiposSonDistintosEntreSi() {
        assertNotEquals(Fragmento.LEGACY, Fragmento.ESTACION);
        assertNotEquals(Fragmento.ESTACION, Fragmento.COMUN);
        assertNotEquals(Fragmento.LEGACY, Fragmento.COMUN);
    }
}
