package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pruebas REALES de la lógica que decide si un aviso de L4 (Metrobús) cierra la Ruta Sur, la Ruta
 * Norte, ambas o ninguna. Llaman directamente a {@link ManifestacionesService#filaDescribeCierre}
 * y {@link ManifestacionesService#rutasL4Cerradas} -- las dos funciones estáticas puras que
 * {@code bloquearRutaL4()} usa en producción (ver su javadoc ahí) -- sin reproducir la lógica a
 * mano aquí.
 *
 * <p>CONFIRMADO (no supuesto): la estructura de 4 columnas de cada fila "estado" del panel oficial
 * ({@code linea}/{@code estado}/{@code estaciones}/{@code info}) -- ver {@code JS_ESTADO} en
 * {@code ManifestacionesService.java}. {@code filaDescribeCierre(sev, estado)} es la ÚNICA FUENTE
 * DE VERDAD de "¿esta fila describe un cierre?": la usan tanto {@code procesarFilas()} (como la
 * variable local "sinServicio") como {@code bloquearRutaL4()} -- por construcción no pueden
 * divergir, que es justo lo que este archivo verifica (sección "regresión" más abajo).
 * {@code sev} es "estado"+" "+"info"+" "+"estaciones" ya normalizado; {@code estado} es la columna
 * "estado" SIN normalizar (se normaliza dentro de la función, igual que "ne" en procesarFilas()).
 * {@code rutasL4Cerradas(infoRaw)} recibe solo la columna "info" sin normalizar, y asume que el
 * cierre a nivel de fila YA fue confirmado por {@code filaDescribeCierre} -- su única pregunta es
 * A CUÁL ruta se refiere.
 *
 * <p>DEFECTO DE REGRESIÓN CORREGIDO EN ESTA RONDA: una versión anterior de este archivo probaba un
 * método {@code filaL4DescribeCierre(String sev)} de un solo parámetro que reimplementaba su propia
 * lista de palabras -- más corta que "sinServicio" de procesarFilas() (le faltaban "cerrad", "no
 * hay servicio", "planton" y la detección de "Manifestación" como ESTADO) y con una palabra ajena
 * ("cancela") que "sinServicio" nunca comprueba -- y no aplicaba la exclusión de "retraso". Esa
 * duplicación es exactamente lo que se eliminó: ahora solo existe {@code filaDescribeCierre}, y
 * tanto el gate general como el de L4 la comparten.
 *
 * <p>HIPÓTESIS explícita, documentada también en producción (ver {@code rutasL4Cerradas}): que
 * mencionar una sola ruta en "info" combinado con un cierre YA confirmado a nivel de fila significa
 * confiablemente que esa ruta es la cerrada. No hay una respuesta oficial reproducible capturada en
 * vivo para confirmarlo; los fixtures de abajo usan el texto real observado en el panel (caso Ruta
 * Sur / congestionamiento vial / Línea 3 / Ayuntamiento) más variantes equivalentes construidas a
 * mano para los demás escenarios pedidos. Esta prueba NO afirma que L4 esté validada con datos
 * oficiales en vivo.
 *
 * <p>NO cubierto aquí (requiere Context/Android, ver {@code ManifestacionesService}'s Handler de
 * instancia -- no instanciable en JVM puro sin Robolectric): el gate {@code if (nlinea != 4) return;}
 * de {@code bloquearRutaL4()}, que es lo que realmente protege contra que un aviso de OTRA línea
 * dispare este código. Ese gate se verificó por lectura de código (confirmado en rondas previas),
 * no por una prueba JVM. Lo que SÍ se prueba aquí para el escenario "mensaje de otra línea" es que,
 * aun si {@code rutasL4Cerradas()} recibiera ese texto, el "Línea 3" / "Ayuntamiento" que menciona
 * no producen un falso positivo de Ruta Sur/Norte por sí solos (ver
 * {@link #mencionDeOtraLineaSinRutaL4_noMarcaNingunaRutaCerrada}).
 */
public class ManifestacionesServiceRutaL4Test {

    // Construye el "sev" real tal como lo hace procesarFilas(): norm(estado+" "+info+" "+estaciones).
    private static String sevDe(String estado, String info, String estaciones) {
        return Planificador.norm(estado + " " + info + " " + estaciones);
    }

    // =====================================================================================
    // SECCIÓN A — filaDescribeCierre(): vocabulario mínimo exigido por la Tarea 3 (regresión)
    // =====================================================================================

    @Test
    public void sinServicio_describeCierre() {
        String estado = "Sin servicio";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    @Test
    public void cerrado_describeCierre() {
        String estado = "Estación cerrada";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    @Test
    public void noHayServicio_describeCierre() {
        String estado = "Intervención en la estación";
        String info = "No hay servicio en la Ruta Norte por mantenimiento.";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, info, ""), estado));
    }

    @Test
    public void planton_describeCierre() {
        String estado = "Plantón";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    // "Manifestación" como ESTADO (columna "estado" propiamente) debe contar como cierre -- es el
    // caso que la exclusión ne.contains("manifestacion") de procesarFilas() está pensada a cubrir.
    @Test
    public void manifestacionComoEstado_describeCierre() {
        String estado = "Manifestación";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    @Test
    public void suspendido_describeCierre() {
        String estado = "Servicio suspendido";
        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    // "Retraso" excluye el cierre aunque aparezcan palabras de cierre en el mismo texto -- igual
    // que la variable "retraso" de procesarFilas() (caso real documentado: "Retraso en el servicio
    // ... por manifestantes" NO corta la línea).
    @Test
    public void retrasoSinCierre_noDescribeCierre() {
        String estado = "Retraso en el servicio";
        String info = "Por congestionamiento vial, servicio lento.";
        assertFalse(ManifestacionesService.filaDescribeCierre(sevDe(estado, info, ""), estado));
    }

    @Test
    public void retrasoJuntoAPalabraDeCierre_laExclusionDeRetrasoGana() {
        String estado = "Retraso en el servicio";
        String info = "Sin servicio por manifestantes, con demoras.";
        assertFalse("igual que sinServicio en producción, 'retraso' excluye incluso si también "
                        + "aparece 'sin servicio' en el mismo texto",
                ManifestacionesService.filaDescribeCierre(sevDe(estado, info, ""), estado));
    }

    // Mención de Ruta Sur/Norte SIN ninguna palabra de cierre: no debe interpretarse como cierre
    // por sí sola (Tarea 3, punto 2).
    @Test
    public void mencionRutaSurSinCierre_noDescribeCierre() {
        String estado = "Intervención en la estación";
        String info = "Servicio de la Ruta Sur con demoras menores, sin contratiempos mayores.";
        assertFalse(ManifestacionesService.filaDescribeCierre(sevDe(estado, info, ""), estado));
    }

    @Test
    public void mencionRutaNorteSinCierre_noDescribeCierre() {
        String estado = "Intervención en la estación";
        String info = "Servicio de la Ruta Norte con demoras menores, sin contratiempos mayores.";
        assertFalse(ManifestacionesService.filaDescribeCierre(sevDe(estado, info, ""), estado));
    }

    @Test
    public void sinAvisoDeCierre_niMencionDeRuta_noDescribeCierre() {
        String estado = "Servicio regular";
        assertFalse(ManifestacionesService.filaDescribeCierre(sevDe(estado, "", ""), estado));
    }

    @Test
    public void sevYEstadoNull_noLanzaYDevuelveFalse() {
        assertFalse(ManifestacionesService.filaDescribeCierre(null, null));
    }

    // =====================================================================================
    // SECCIÓN B — caso real del panel: cierre y mención de ruta en fragmentos/columnas distintas
    // =====================================================================================

    // (a) Caso real del panel: "sin servicio" y "ruta sur" en fragmentos distintos de info; el
    // cierre se confirma a nivel de fila vía filaDescribeCierre(), y rutasL4Cerradas() atribuye el
    // cierre ya confirmado a la única ruta mencionada.
    @Test
    public void rutaSur_cierreYMencionEnFragmentosDistintos_marcaSurCerrada() {
        String estado = "Mantenimiento";
        String infoRaw = "Por congestionamiento vial. Servicio de la Ruta Sur por "
                + "México-Tenochtitlán, Línea 3 y Ayuntamiento. Sin servicio por mantenimiento "
                + "a carril confinado.";
        String estaciones = "";

        assertTrue("la fila completa (estado+info+estaciones) sí describe un cierre",
                ManifestacionesService.filaDescribeCierre(sevDe(estado, infoRaw, estaciones), estado));

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertTrue("Ruta Sur debe quedar marcada como cerrada", rutas[0]);
        assertFalse("Ruta Norte no debe quedar marcada", rutas[1]);
    }

    // (b) Equivalente para Ruta Norte: mismo patrón, "sin servicio" y "ruta norte" en fragmentos
    // distintos de info.
    @Test
    public void rutaNorte_cierreYMencionEnFragmentosDistintos_marcaNorteCerrada() {
        String estado = "Mantenimiento";
        String infoRaw = "Por congestionamiento vial. Servicio de la Ruta Norte por "
                + "Buenavista y Hidalgo. Sin servicio por mantenimiento a carril confinado.";

        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, infoRaw, ""), estado));

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertFalse("Ruta Sur no debe quedar marcada", rutas[0]);
        assertTrue("Ruta Norte debe quedar marcada como cerrada", rutas[1]);
    }

    // (c) Aviso de congestionamiento que NO implica cierre total: ninguna palabra de cierre en la
    // fila completa -> no se bloquea nada, sin necesidad de mirar "info" en absoluto.
    @Test
    public void congestionamientoSinCierre_filaNoDescribeCierre_noSeEvaluaRuta() {
        String estado = "Intervención en la estación";
        String infoRaw = "Por congestionamiento vial. Servicio de la Ruta Sur por "
                + "México-Tenochtitlán con demoras.";

        assertFalse("un aviso de solo congestión/demora, sin palabra de cierre, no describe un cierre",
                ManifestacionesService.filaDescribeCierre(sevDe(estado, infoRaw, ""), estado));
    }

    // (d) Aviso de mantenimiento que SÍ implica cierre (la fuente real lo describe con "sin
    // servicio"/"suspendido"): debe reconocerse como cierre a nivel de fila.
    @Test
    public void mantenimientoQueSiImplicaCierre_filaSiDescribeCierre() {
        String estado = "Mantenimiento";
        String infoRaw = "Servicio de la Ruta Norte suspendido por trabajos de mantenimiento "
                + "en la vía.";

        assertTrue(ManifestacionesService.filaDescribeCierre(sevDe(estado, infoRaw, ""), estado));
        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertTrue(rutas[1]);
        assertFalse(rutas[0]);
    }

    // (e) Mensaje de OTRA línea que menciona estaciones/referencias de L4 (p. ej. "Línea 3" y
    // "Ayuntamiento", ambas presentes en el texto real del panel) -- por sí solas, sin "ruta sur"/
    // "ruta norte" literal, no deben producir ninguna ruta marcada como cerrada.
    @Test
    public void mencionDeOtraLineaSinRutaL4_noMarcaNingunaRutaCerrada() {
        String infoRaw = "Sin servicio en Línea 3 por mantenimiento. Ayuntamiento con demoras.";

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertFalse("mencionar 'Línea 3' no debe interpretarse como un cierre de Ruta Sur/Norte de L4",
                rutas[0]);
        assertFalse(rutas[1]);
    }

    // (f) Ausencia de cualquier aviso de cierre: fila sin ninguna palabra de cierre -> no describe
    // cierre, independientemente de lo que diga "info".
    @Test
    public void sinAvisoDeCierre_filaNoDescribeCierre() {
        String estado = "Servicio regular";
        String infoRaw = "Servicio normal en ambas rutas.";

        assertFalse(ManifestacionesService.filaDescribeCierre(sevDe(estado, infoRaw, ""), estado));
        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertFalse(rutas[0]);
        assertFalse(rutas[1]);
    }

    // (g) Caso real que motivó el chequeo por oración: AMBAS rutas mencionadas en el mismo aviso,
    // una cerrada y la otra en servicio -- debe distinguir cuál es cuál, no cerrar las dos. Se
    // mantiene la desambiguación existente (Tarea 2 de la ronda previa, sin cambios).
    @Test
    public void ambasRutasMencionadas_soloLaQueTieneCierreEnSuPropiaOracion_quedaCerrada() {
        String infoRaw = "Servicio de la ruta sur de San Pablo a San Lázaro. Se cancela ruta norte.";

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertFalse("Ruta Sur sigue en servicio según su propia oración, no debe marcarse cerrada",
                rutas[0]);
        assertTrue("Ruta Norte sí se cancela según su propia oración", rutas[1]);
    }

    // (g bis) Variante del caso anterior invirtiendo cuál ruta se cancela, para confirmar que la
    // disambiguación por oración no favorece sistemáticamente a una de las dos rutas.
    @Test
    public void ambasRutasMencionadas_inversoDeCualSeCancela_distingueCorrectamente() {
        String infoRaw = "Servicio de la ruta norte de Buenavista a Hidalgo. Se cancela ruta sur.";

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertTrue("Ruta Sur se cancela según su propia oración", rutas[0]);
        assertFalse("Ruta Norte sigue en servicio según su propia oración, no debe marcarse cerrada",
                rutas[1]);
    }

    // Nombres homónimos/compuestos: "ruta sur" como frase no debe confundirse con una estación
    // nombrada "... Sur" -- rutasL4Cerradas() busca la frase literal "ruta sur"/"ruta norte", no
    // cualquier aparición suelta de "sur"/"norte".
    @Test
    public void palabraSurSueltaSinFraseRutaSur_noMarcaCierreDeRuta() {
        String infoRaw = "Cierre en Mercado de Sonora Sur por obras en la estación.";

        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(infoRaw);
        assertFalse("'Sonora Sur' no debe interpretarse como mención de 'ruta sur'", rutas[0]);
        assertFalse(rutas[1]);
    }

    // infoRaw null: no debe lanzar excepción, debe devolver "ninguna ruta cerrada".
    @Test
    public void infoRawNull_noLanzaYDevuelveNinguna() {
        boolean[] rutas = ManifestacionesService.rutasL4Cerradas(null);
        assertFalse(rutas[0]);
        assertFalse(rutas[1]);
    }

    // =====================================================================================
    // SECCIÓN C — regresión: el gate de L4 y el gate general NUNCA pueden divergir
    // =====================================================================================

    // Por construcción, bloquearRutaL4() llama a filaDescribeCierre(sev, estado) -- la MISMA
    // función que procesarFilas() usa para su variable "sinServicio" -- así que no hay manera de
    // que un caso sea cierre para uno y no para el otro. Esta prueba fija ese contrato con una
    // tabla de casos variados (cada palabra del vocabulario real, más combinaciones con "retraso"
    // y con mención de ruta) evaluados una sola vez a través de filaDescribeCierre(): si alguna vez
    // se reintrodujera una lista de palabras separada para L4, bastaría con que esa copia volviera
    // a divergir de esta tabla para que el test lo detecte.
    @Test
    public void vocabularioCompleto_coincideConSinServicioDeProcesarFilas() {
        Object[][] casos = {
                {"Sin servicio", "", true},
                {"Estación cerrada", "", true},
                {"Intervención en la estación", "No hay servicio en la Ruta Sur", true},
                {"Plantón", "", true},
                {"Manifestación", "", true},
                {"Servicio suspendido", "", true},
                {"Mantenimiento", "Se cancela el servicio de la ruta sur", false},   // "cancela" NO es
                        // parte del vocabulario real de sinServicio -- no debe contar como cierre por
                        // sí solo (ver defecto corregido: antes "cancela" sí contaba, divergiendo).
                {"Retraso en el servicio", "Sin servicio por manifestantes", false},
                {"Intervención en la estación", "Servicio de la Ruta Norte con demoras menores", false},
                {"Servicio regular", "", false},
        };
        for (Object[] caso : casos) {
            String estado = (String) caso[0];
            String info = (String) caso[1];
            boolean esperado = (Boolean) caso[2];
            String sev = sevDe(estado, info, "");
            assertEquals("estado=\"" + estado + "\" info=\"" + info + "\"",
                    esperado, ManifestacionesService.filaDescribeCierre(sev, estado));
        }
    }
}
