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

    // --- rutaRelativa(): Sistemas.carpeta()/numeroDeLinea() y Locuciones.carpetaEstacion() son
    // puros (sin Context, ya probados aparte en SistemasTest), así que esto SÍ es verificable aquí.

    @Test
    public void rutaRelativa_legacy_vaPorSistemaLineaYEstacion() {
        Fragmento f = Fragmento.legacy("Llegando a estación: Hidalgo", 1, "Hidalgo");
        String esperado = "Metrobus/L1/" + Locuciones.carpetaEstacion("Hidalgo")
                + "/" + Integer.toHexString(f.texto.hashCode()) + ".mp3";
        assertEquals(esperado, f.rutaRelativa(1));
    }

    @Test
    public void rutaRelativa_legacy_distintaLinea_distintaCarpeta() {
        Fragmento f = Fragmento.legacy("Llegando a estación: Hidalgo", 1, "Hidalgo");
        assertNotEquals(f.rutaRelativa(1), f.rutaRelativa(101));   // Metrobús L1 vs Mexibús L1
    }

    @Test
    public void rutaRelativa_estacion_esGlobal_ignoraLinea() {
        Fragmento f = Fragmento.estacion("Indios Verdes");
        String nombreArchivo = Integer.toHexString(f.texto.hashCode()) + ".mp3";
        assertEquals("_estaciones/" + nombreArchivo, f.rutaRelativa(1));
        assertEquals(f.rutaRelativa(1), f.rutaRelativa(101));   // misma ruta sin importar la línea
    }

    @Test
    public void rutaRelativa_comun_esGlobal_ignoraLinea() {
        Fragmento f = Fragmento.comun("5 estaciones");
        String nombreArchivo = Integer.toHexString(f.texto.hashCode()) + ".mp3";
        assertEquals("_comunes/" + nombreArchivo, f.rutaRelativa(1));
        assertEquals(f.rutaRelativa(1), f.rutaRelativa(202));
    }

    @Test
    public void rutaRelativa_estacionYComun_textosDistintos_archivosDistintos() {
        Fragmento a = Fragmento.estacion("Hidalgo");
        Fragmento b = Fragmento.estacion("Indios Verdes");
        assertNotEquals(a.rutaRelativa(1), b.rutaRelativa(1));
    }
}
