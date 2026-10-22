package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real confirmado: el TTS leía "1ro de Mayo" (Mexibús L1/L1 Exprés) y "1° de Mayo" (L2) de
 * forma literal ("uno Ro de Mayo") en vez de "Primero de Mayo". {@link Locuciones#nom} es la réplica
 * pública de {@code respellVoz()} (debe mantenerse en sincronía con RecorridoService/DescargaVoz, ver
 * sus propios comentarios), así que se prueba contra la función real.
 */
public class LocucionesTest {

    @Test
    public void nom_1roDeMayo_seDiceComoPrimeroDeMayo() {
        assertEquals("Primero de Mayo", Locuciones.nom("MXB 1ro de Mayo"));
    }

    @Test
    public void nom_1gradoDeMayo_seDiceComoPrimeroDeMayo() {
        assertEquals("Primero de Mayo", Locuciones.nom("MXB 1° de Mayo"));
    }

    @Test
    public void nom_otraEstacion_noSeAltera() {
        assertEquals("Jardines de Morelos", Locuciones.nom("MXB Jardines de Morelos"));
    }

    /**
     * Defecto real confirmado (Indios Verdes, Mexibús→Metrobús: "conexión" Metrobús↔Edomex): la
     * pre-descarga ({@link DescargaVoz#textosLinea}) generaba para TODA correspondencia, incluida
     * "conexión", el aviso de bajada genérico ("Baja y realiza tu conexión") -- pero en vivo,
     * {@code RecorridoService.instruccionBajada()} usa un texto DISTINTO para "conexión" (nombra la
     * estación+línea de destino: "Camina hasta llegar a la conexión con …", ver
     * {@code voz_camina_conexion}/{@link Locuciones#nombreConexion}). Como los hashes nunca
     * coincidían, el audio de bajada en Indios Verdes NUNCA quedaba realmente pre-descargado -- sonaba
     * el TTS del dispositivo aun con la línea "descargada". {@link Locuciones#palabraTransferencia} es
     * la función real que decide, por cada línea de destino, cuál de las 2 instrucciones aplica -- se
     * prueba contra ella para que el selector de la pre-descarga no se desincronice de nuevo.
     */
    @Test
    public void palabraTransferencia_mexibusAMetrobus_esConexion_indiosVerdes() {
        assertEquals(R.string.voz_palabra_conexion, Locuciones.palabraTransferencia(104, 1));
    }

    @Test
    public void palabraTransferencia_metrobusAMetrobus_esTransbordo() {
        assertEquals(R.string.voz_palabra_transbordo, Locuciones.palabraTransferencia(1, 3));
    }

    @Test
    public void palabraTransferencia_mexibusAMexibus_esCorrespondencia() {
        assertEquals(R.string.voz_palabra_correspondencia, Locuciones.palabraTransferencia(104, 101));
    }
}
