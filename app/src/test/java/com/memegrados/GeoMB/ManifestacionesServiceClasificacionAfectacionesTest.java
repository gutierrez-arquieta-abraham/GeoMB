package com.memegrados.GeoMB;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Pruebas REALES de las funciones puras que clasifican un aviso de "estado" (cierre vs. retraso,
 * direccional vs. ambos sentidos, tramo parcial vs. obstrucción sin interrupción efectiva), parte
 * de la auditoría "GeoMB -- Auditoría y corrección integral del coloreado de estaciones
 * afectadas". Llama directamente a {@link ManifestacionesService#filaDescribeCierre},
 * {@link ManifestacionesService#terminalEnTexto}, {@link ManifestacionesService#segmentoParcial} y
 * {@link ManifestacionesService#oracionesDe} -- sin reproducir su lógica a mano.
 *
 * <p>Estas 4 funciones son estáticas y puras (no dependen de Context/Android), confirmado por
 * lectura directa de su código en {@code ManifestacionesService.java}: {@code terminalEnTexto} se
 * volvió {@code static} en esta misma ronda (ya no usaba {@code this} ni Context, solo
 * {@code Planificador.terminales()}, también estático).
 *
 * <p>NO cubierto aquí (requiere Context/Android -- instancia de {@code ManifestacionesService}, no
 * construible en JVM puro sin Robolectric por su campo {@code Handler}): el resto de
 * {@code procesarFilas()} (los booleanos {@code retraso}/{@code obstruccionCarril}/
 * {@code bloqueoFisico} en sí, {@code direccionEfectiva()}, {@code bloquearNn()},
 * {@code agregarSentido()}, {@code bloquearTramos()}/{@code bloquearTramosServicios()} como
 * métodos de instancia completos, y los efectos secundarios sobre
 * {@code afectAcc}/{@code porSentidoAcc}/{@code cortesAcc}). Estas pruebas verifican el núcleo de
 * TEXTO de esa clasificación -- la parte no trivial y la que puede probarse sin Android -- no la
 * escritura final en los acumuladores. Los fixtures de abajo son hipótesis basadas en el texto
 * real observado en rondas de auditoría previas, NO una respuesta oficial capturada en el momento;
 * no se afirma que representen el estado actual del feed.
 */
public class ManifestacionesServiceClasificacionAfectacionesTest {

    private static String sevDe(String estado, String info, String estaciones) {
        return Planificador.norm(estado + " " + info + " " + estaciones);
    }

    // =====================================================================================
    // Fixture L6 -- "416 ORIENTE": cierre de estación individual, sin dirección en el texto.
    // =====================================================================================
    @Test
    public void fixtureL6_416Oriente_sinServicioSinDireccion_esCierreYSinTerminalAtribuible() {
        String estado = "Intervención en la estación";
        String info = "Sin servicio por mantenimiento";
        String sev = sevDe(estado, info, "416 ORIENTE");

        assertTrue("el aviso sí describe un cierre (contiene 'sin servicio')",
                ManifestacionesService.filaDescribeCierre(sev, estado));
        assertNull("sin 'dirección'/'sentido'/'hacia' en el texto, no debe atribuirse a NINGUNA "
                        + "terminal -- el cierre es de AMBOS sentidos (toda la estación)",
                ManifestacionesService.terminalEnTexto(6, sev));
    }

    // =====================================================================================
    // Fixture L3 -- "Poniente 128": cierre direccional explícito ("dirección a Tenayuca"). Debe
    // identificar la terminal correcta y NO extrapolar al sentido contrario.
    // =====================================================================================
    @Test
    public void fixtureL3_poniente128_direccionATenayuca_identificaSoloEseSentido() {
        String estado = "Intervención en la estación";
        String info = "Sin servicio dirección a Tenayuca";
        String sev = sevDe(estado, info, "Poniente 128");

        assertTrue(ManifestacionesService.filaDescribeCierre(sev, estado));
        String terminal = ManifestacionesService.terminalEnTexto(3, sev);
        assertEquals("debe identificar 'Tenayuca' como la terminal del sentido afectado",
                "tenayuca", terminal);
        assertNotEquals("NO debe atribuirse al otro extremo de L3 (Pueblo Sta. Cruz Atoyac)",
                Planificador.norm("Pueblo Sta. Cruz Atoyac"), terminal);
    }

    // Control: el mismo texto pero SIN ninguna de las 3 palabras de dirección ("direccion"/
    // "sentido"/"hacia") no debe atribuirse a ninguna terminal, aunque mencione "Tenayuca" --
    // evita inferir dirección de una simple mención del nombre de la terminal.
    @Test
    public void mencionDeTerminalSinPalabraDeDireccion_noAtribuyeNingunSentido() {
        String sev = Planificador.norm("Intervención en la estación Servicio cerca de Tenayuca Poniente 128");
        assertNull(ManifestacionesService.terminalEnTexto(3, sev));
    }

    // =====================================================================================
    // Fixture L4 -- "Alameda Oriente - Calle 6": retraso por congestionamiento vial, SIN cierre.
    // =====================================================================================
    @Test
    public void fixtureL4_retrasoPorCongestionamiento_noEsCierre() {
        String estado = "Retraso en el servicio";
        String info = "Por congestionamiento vial";
        String sev = sevDe(estado, info, "Alameda Oriente - Calle 6");

        assertFalse("un retraso (sin palabra de cierre propia, y con 'retraso' presente) no debe "
                        + "interpretarse como cierre",
                ManifestacionesService.filaDescribeCierre(sev, estado));
    }

    // Control: si el MISMO texto además mencionara una palabra de cierre ("sin servicio"), la
    // exclusión de "retraso" sigue ganando -- un retraso nunca se convierte en cierre total.
    @Test
    public void retrasoConPalabraDeCierrePresente_laExclusionDeRetrasoSigueGanando() {
        String estado = "Retraso en el servicio";
        String info = "Sin servicio por congestionamiento vial en algunos tramos";
        String sev = sevDe(estado, info, "Alameda Oriente - Calle 6");

        assertFalse(ManifestacionesService.filaDescribeCierre(sev, estado));
    }

    // =====================================================================================
    // Fixture L7 -- info con DOS oraciones ajenas separadas por punto en el texto original:
    // "...a Hamburgo. Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac". Defecto
    // corregido en esta ronda: antes de oracionesDe(), Planificador.norm() quitaba los puntos y
    // ambas oraciones se procesaban como UN SOLO bloque, dejando que "Glorieta Cuitláhuac" (al
    // final del bloque fusionado) se resolviera por COINCIDENCIA DE SUBCADENA como si fuera el
    // destino de la PRIMERA oración real ("Hospital Infantil La Villa a Hamburgo").
    // =====================================================================================
    @Test
    public void fixtureL7_dosOracionesSeparadasPorPunto_seDividenCorrectamente() {
        String info = "Por bloqueo, servicio de Indios Verdes y Hospital Infantil La Villa a "
                + "Hamburgo. Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac";

        List<String> oraciones = ManifestacionesService.oracionesDe(info);
        assertEquals("el punto real (no una abreviatura) debe separar en 2 oraciones", 2, oraciones.size());
        assertTrue(oraciones.get(0).trim().endsWith("Hamburgo."));
        assertTrue(oraciones.get(1).trim().startsWith("Sin servicio la ruta"));

        // La PRIMERA oración, aislada, resuelve limpiamente "Hospital Infantil La Villa a
        // Hamburgo" como tramo -- sin la segunda oración pegada como cola.
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(oraciones.get(0)));
        assertNotNull(seg);
        assertTrue("el segmento extraído de la primera oración no debe contener texto de la "
                        + "segunda oración (ninguna mención de 'glorieta')",
                !seg.contains("glorieta"));
    }

    // =====================================================================================
    // oracionesDe(): no debe partir abreviaturas REALES del catálogo (confirmadas contra
    // estaciones.json) -- "Av. Talismán" (L7), "Dr. Gálvez" (L1), "Dr. Márquez" (L3), "Pueblo
    // Sta. Cruz Atoyac" (L3), "Gustavo A. Madero" (L6/L7, una sola letra antes del punto).
    // =====================================================================================
    @Test
    public void oracionesDe_noPartePorAbreviaturasRealesDelCatalogo() {
        assertEquals("Av. Talismán no debe partirse en dos oraciones",
                1, ManifestacionesService.oracionesDe("Servicio de Av. Talismán a Garrido").size());
        assertEquals("Dr. Gálvez no debe partirse",
                1, ManifestacionesService.oracionesDe("Sin servicio en Dr. Gálvez").size());
        assertEquals("Pueblo Sta. Cruz Atoyac no debe partirse",
                1, ManifestacionesService.oracionesDe("Servicio de Pueblo Sta. Cruz Atoyac a Tenayuca").size());
        assertEquals("Gustavo A. Madero (una sola letra antes del punto) no debe partirse",
                1, ManifestacionesService.oracionesDe("Sin servicio en Gustavo A. Madero").size());
    }

    @Test
    public void oracionesDe_siPartePorUnPuntoDeFinDeOracionReal() {
        // Nombres de varias letras a propósito (no "A"/"B" sueltas): una palabra de una sola letra
        // antes del punto es justo la excepción que protege a "Gustavo A. Madero", así que no sirve
        // como marcador de fin de oración real en esta prueba.
        List<String> r = ManifestacionesService.oracionesDe(
                "Servicio de Indios Verdes a Buenavista. Sin servicio de Hidalgo a Amajac.");
        assertEquals(2, r.size());
    }

    @Test
    public void oracionesDe_sinPuntos_devuelveUnaSolaOracion() {
        List<String> r = ManifestacionesService.oracionesDe("Sin servicio por mantenimiento");
        assertEquals(1, r.size());
        assertEquals("Sin servicio por mantenimiento", r.get(0));
    }

    @Test
    public void oracionesDe_null_devuelveListaVacia() {
        assertTrue(ManifestacionesService.oracionesDe(null).isEmpty());
    }

    // =====================================================================================
    // L7 -- verificación de extremo a extremo MÁS PROFUNDA del fixture multi-oración: encadena
    // oracionesDe() -> segmentoParcial() -> el split " y " real de bloquearTramosServicios() ->
    // idxEnSecuencia() CONTRA LAS SECUENCIAS REALES de RutasMixtas.SECUENCIAS (no una copia a
    // mano) -- las 4 funciones que bloquearTramosServicios() encadena para L7, confirmando que
    // resuelven los extremos CORRECTOS ("Hospital Infantil La Villa" a "Hamburgo") una vez que la
    // oración 2 ("Sin servicio la ruta Alameda Tacubaya...") ya no está pegada a la cola.
    //
    // <p>LO QUE SIGUE SIN PROBARSE (replica exacta de bloquearTramosServicios() como método): el
    // propio bucle que combina el resultado de idxEnSecuencia() en los 4 servicios de L7 para
    // construir "enServicio" y escribir en {@code afect}/{@code cortesAcc} -- es un método de
    // instancia que necesita Context (GtfsRepository no interviene aquí, pero el método en sí
    // vive en ManifestacionesService, instanciable solo con Android/Robolectric). Esta prueba
    // conecta las piezas REALES que preceden a ese bucle, sin reproducir el bucle mismo.
    // =====================================================================================
    @Test
    public void fixtureL7_extremosResueltosContraSecuenciasRealesDeRutasMixtas() {
        String info = "Por bloqueo, servicio de Indios Verdes y Hospital Infantil La Villa a "
                + "Hamburgo. Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac";
        String primeraOracion = ManifestacionesService.oracionesDe(info).get(0);
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(primeraOracion));
        assertNotNull(seg);

        // Mismo split " y " que bloquearTramosServicios() aplica sobre "seg" -- no es una réplica
        // de SU lógica de resolución, solo cómo se le entrega a idxEnSecuencia() el mismo chunk
        // que recibiría en producción.
        String[] chunks = seg.split("\\s+y\\s+");
        String ultimoChunk = chunks[chunks.length - 1];
        int ap = ultimoChunk.indexOf(" a ");
        assertTrue(ap >= 3);
        String x = ultimoChunk.substring(0, ap).trim();
        String y = ultimoChunk.substring(ap + 3).trim();

        java.util.List<RutasMixtas.SeqMixta> secuenciasL7 = new java.util.ArrayList<>();
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            for (int ln : sm.lineas) if (ln == 7) { secuenciasL7.add(sm); break; }
        }
        assertFalse("RutasMixtas debe traer al menos una secuencia real de L7", secuenciasL7.isEmpty());

        boolean algunaResolvioHospitalAHamburgo = false;
        for (RutasMixtas.SeqMixta sm : secuenciasL7) {
            int ix = ManifestacionesService.idxEnSecuencia(sm, 7, x);
            int iy = ManifestacionesService.idxEnSecuencia(sm, 7, y);
            if (ix < 0 || iy < 0) continue;
            algunaResolvioHospitalAHamburgo = true;
            // Ninguna secuencia debe resolver "y" (el destino) a "Glorieta Cuitláhuac" -- ese
            // nombre pertenece a la SEGUNDA oración, ya separada por oracionesDe(); si volviera a
            // aparecer aquí sería la regresión original (fusión de oraciones).
            assertFalse("el destino resuelto no debe ser 'Glorieta Cuitláhuac' (eso pertenece a "
                            + "la segunda oración, ya separada)",
                    Planificador.norm(sm.estaciones[iy]).equals(Planificador.norm("Glorieta Cuitláhuac")));
        }
        assertTrue("al menos una secuencia real de L7 debe resolver Hospital Infantil La Villa "
                        + "-> Hamburgo",
                algunaResolvioHospitalAHamburgo);
    }

    // =====================================================================================
    // Defecto confirmado y corregido: variante real del caso L7 anterior, pero con las dos
    // oraciones separadas por COMA en vez de punto ("...a Hamburgo, Sin servicio la ruta Alameda
    // Tacubaya a Glorieta Cuitláhuac"). oracionesDe() solo dividía por punto, así que esta variante
    // reproducía el mismo defecto de fusión -- ver conLimitesDeOracion().
    // =====================================================================================
    @Test
    public void conLimitesDeOracion_convierteComaAntesDeFraseDeCierreEnPunto() {
        String info = "Por bloqueo, servicio de Indios Verdes y Hospital Infantil la Villa a "
                + "Hamburgo, Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac.";

        String conLimites = ManifestacionesService.conLimitesDeOracion(info);
        List<String> oraciones = ManifestacionesService.oracionesDe(conLimites);

        assertEquals("ambas comas preceden a una frase de inicio reconocida ('servicio de' / "
                        + "'sin servicio'): deben tratarse como límite, igual que un punto",
                3, oraciones.size());
        assertTrue(oraciones.get(1).trim().endsWith("Hamburgo."));
        assertTrue(oraciones.get(2).trim().startsWith("Sin servicio la ruta"));
    }

    @Test
    public void conLimitesDeOracion_comaSinFraseDeCierreDespues_noSePartee() {
        // "México-Tenochtitlán, Línea 3 y Ayuntamiento" -- "Línea 3" no es una frase de cierre
        // reconocida: la coma no debe convertirse en punto.
        String info = "Servicio de la Ruta Sur por México-Tenochtitlán, Línea 3 y Ayuntamiento.";
        assertEquals(info, ManifestacionesService.conLimitesDeOracion(info));
    }

    @Test
    public void fixtureL7_variantesConComa_laOracionLimpiaNoMencionaGlorieta() {
        String info = "Por bloqueo, servicio de Indios Verdes y Hospital Infantil la Villa a "
                + "Hamburgo, Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac.";

        List<String> oraciones = ManifestacionesService.oracionesDe(
                ManifestacionesService.conLimitesDeOracion(info));
        // La oración que describe "Hospital Infantil La Villa a Hamburgo" debe quedar aislada --
        // igual que en la variante con punto, el segmento extraído no debe mencionar "glorieta".
        String oracionDelTramo = oraciones.get(1);
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(oracionDelTramo));
        assertNotNull(seg);
        assertFalse(seg.contains("glorieta"));
    }

    // =====================================================================================
    // Defecto real reportado en dispositivo (L7, confirmado por el usuario: "Indios verdes esta
    // considerado como operativa la neta"): "servicio de Indios Verdes y Hospital Infantil La
    // Villa a Hamburgo" tiene DOS orígenes para UN destino compartido. Antes de esta corrección,
    // bloquearTramosServicios() partía por " y " y descartaba cualquier chunk sin su propio " a Y"
    // (chunk.indexOf(" a ") < 3) -- "Indios Verdes" se perdía así sin aportar nada, dejando esa
    // estación marcada fuera de servicio pese a que el aviso dice explícitamente que sigue
    // operando. bloquearTramosServicios() es un método de instancia (no instanciable en JVM puro,
    // Service no se construye sin Robolectric), así que esta prueba verifica contra las piezas
    // REALES y estáticas que sostienen la corrección: que "Indios Verdes" es un nombre real
    // resoluble por idxEnSecuencia() dentro de alguna secuencia real de L7, y que efectivamente NO
    // tiene su propio " a " (confirmando que antes de la corrección este chunk se descartaba por
    // completo en vez de emparejarse con el destino del siguiente chunk, "Hamburgo").
    // =====================================================================================
    @Test
    public void fixtureL7_indiosVerdes_esOrigenColganteSinSuPropioDestino_yResuelveComoEstacionRealDeL7() {
        String info = "Por bloqueo, servicio de Indios Verdes y Hospital Infantil La Villa a Hamburgo";
        String primeraOracion = ManifestacionesService.oracionesDe(info).get(0);
        String seg = ManifestacionesService.segmentoParcial(Planificador.norm(primeraOracion));
        assertNotNull(seg);

        String[] chunks = seg.split("\\s+y\\s+");
        assertEquals("el aviso real tiene 2 chunks separados por ' y '", 2, chunks.length);
        String primerChunk = chunks[0].trim();
        assertEquals("indios verdes", primerChunk);
        assertTrue("el primer chunk ('Indios Verdes') NO tiene su propio ' a Y' -- es el origen "
                        + "'colgante' que antes de esta corrección se descartaba sin más",
                primerChunk.indexOf(" a ") < 3);

        java.util.List<RutasMixtas.SeqMixta> secuenciasL7 = new java.util.ArrayList<>();
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            for (int ln : sm.lineas) if (ln == 7) { secuenciasL7.add(sm); break; }
        }
        assertFalse(secuenciasL7.isEmpty());

        boolean indiosVerdesResuelveConHamburgo = false;
        for (RutasMixtas.SeqMixta sm : secuenciasL7) {
            int ix = ManifestacionesService.idxEnSecuencia(sm, 7, Planificador.norm("Indios Verdes"));
            int iy = ManifestacionesService.idxEnSecuencia(sm, 7, Planificador.norm("Hamburgo"));
            if (ix >= 0 && iy >= 0) indiosVerdesResuelveConHamburgo = true;
        }
        assertTrue("'Indios Verdes' debe resolver como estación real de L7 junto con 'Hamburgo' "
                        + "dentro de alguna secuencia real -- confirma que, emparejado con el "
                        + "destino del siguiente chunk (la corrección aplicada), el tramo 'Indios "
                        + "Verdes a Hamburgo' es resoluble y por tanto queda EN SERVICIO en vez de "
                        + "bloqueado",
                indiosVerdesResuelveConHamburgo);
    }

    // =====================================================================================
    // Defecto real reportado por el usuario (L7, ramal H72): "Sin servicio la ruta Alameda
    // Tacubaya a Glorieta Cuitláhuac" nunca bloqueaba nada. Dos causas confirmadas: (1)
    // segmentoParcial() solo reconoce "servicio de X a Y" (el tramo que SÍ opera) -- "sin servicio
    // la ruta X a Y" no contiene "servicio de", así que siempre devolvía null; (2) "Alameda
    // Tacubaya" es el nombre de TERMINAL (línea 2) que el feed real usa para los dos ramales de
    // H72 -- ver RutasMixtas.LISTA, Mixta("París", 7, "Alameda Tacubaya", 2) y
    // Mixta("Glorieta Cuitláhuac", 7, "Alameda Tacubaya", 2) -- pero idxEnSecuencia(sm, 7, ...)
    // solo busca entre las entradas etiquetadas línea 7 de H72, y "Alameda Tacubaya"/"Tacubaya"
    // están etiquetadas línea 2 ahí, así que nunca se encontraba buscando con nlinea=7.
    // =====================================================================================
    @Test
    public void fixtureH72_alamedaTacubayaEsElTerminalRealDeLosDosRamales_segunRutasMixtasLista() {
        boolean ramalCorto = false, ramalLargo = false;
        for (RutasMixtas.Mixta m : RutasMixtas.LISTA) {
            if (m.lineaB == 2 && Planificador.norm(m.terminalB).equals(Planificador.norm("Alameda Tacubaya"))) {
                if (m.lineaA == 7 && Planificador.norm(m.terminalA).equals(Planificador.norm("París"))) ramalCorto = true;
                if (m.lineaA == 7 && Planificador.norm(m.terminalA).equals(Planificador.norm("Glorieta Cuitláhuac"))) ramalLargo = true;
            }
        }
        assertTrue("RutasMixtas.LISTA debe traer el ramal corto (Alameda Tacubaya <-> París)", ramalCorto);
        assertTrue("RutasMixtas.LISTA debe traer el ramal largo (Alameda Tacubaya <-> Glorieta Cuitláhuac)", ramalLargo);
    }

    @Test
    public void rutaSinServicio_reconoceElPatronRealDeH72_dondeSegmentoParcialSiempreFallaba() {
        String normFull = Planificador.norm("Sin servicio la ruta Alameda Tacubaya a Glorieta Cuitláhuac");

        assertNull("segmentoParcial() no reconoce 'sin servicio la ruta X a Y' -- no contiene "
                        + "'servicio de' -- este es justo el defecto confirmado",
                ManifestacionesService.segmentoParcial(normFull));

        String seg = ManifestacionesService.rutaSinServicio(normFull);
        assertNotNull("rutaSinServicio() sí debe reconocer este patrón", seg);
        assertTrue(seg.contains("alameda tacubaya"));
        assertTrue(seg.contains(" a "));
        assertTrue(seg.trim().endsWith("glorieta cuitlahuac"));
    }

    @Test
    public void rutaSinServicio_sinElMarcador_devuelveNull() {
        assertNull(ManifestacionesService.rutaSinServicio(
                Planificador.norm("Servicio de Indios Verdes a Hamburgo")));
    }

    @Test
    public void idxEnSecuencia_alamedaTacubaya_resuelveComoElExtremoDeLinea7DeH72() {
        RutasMixtas.SeqMixta h72 = null;
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            if ("H72".equals(sm.nombre)) { h72 = sm; break; }
        }
        assertNotNull("RutasMixtas.SECUENCIAS debe traer H72", h72);

        int ixAlameda = ManifestacionesService.idxEnSecuencia(h72, 7, Planificador.norm("Alameda Tacubaya"));
        int ixChapultepec = ManifestacionesService.idxEnSecuencia(h72, 7, Planificador.norm("Chapultepec"));
        assertTrue("'Alameda Tacubaya' debe resolver (antes de esta corrección devolvía -1 "
                        + "siempre, por buscar nlinea=7 contra entradas etiquetadas línea 2)",
                ixAlameda >= 0);
        assertEquals("'Alameda Tacubaya' es el extremo de la secuencia, ANTES de su primer tramo "
                        + "de línea 7 -- debe resolver exactamente al mismo índice que la primera "
                        + "estación real de línea 7 en H72 ('Chapultepec')",
                ixChapultepec, ixAlameda);

        int ixGlorieta = ManifestacionesService.idxEnSecuencia(h72, 7, Planificador.norm("Glorieta Cuitláhuac"));
        assertTrue(ixGlorieta >= 0);
        assertTrue("el tramo completo (Alameda Tacubaya -> Glorieta Cuitláhuac) debe cubrir TODA "
                        + "la porción de línea 7 de H72", ixGlorieta > ixAlameda);

        // Control: "Alameda Tacubaya" NO debe resolver dentro de una secuencia que no tenga
        // "Tacubaya" en línea 2 -- el alias está acotado a H72, no es una coincidencia genérica.
        RutasMixtas.SeqMixta l7ic = null;
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            if ("L7-IC".equals(sm.nombre)) { l7ic = sm; break; }
        }
        assertNotNull(l7ic);
        assertEquals(-1, ManifestacionesService.idxEnSecuencia(l7ic, 7, Planificador.norm("Alameda Tacubaya")));
    }
}
