package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defecto real CONFIRMADO en Puente de Fierro: su plataforma L4 es de las de "andén largo" (~100 m);
 * sin el radio ampliado, el aviso de "llegando" ahí no sonaba por falta de cobertura (ya resuelto,
 * ver {@link Planificador#andenLargo}/{@code ANDEN_LARGO_M}). Del lado L2 (el punto de Puente de
 * Fierro L2, 284 m real del de L4, misma estación por nombre, dos plataformas físicas distintas)
 * nunca se confirmó una falla equivalente.
 *
 * <p>El radio ampliado que SÍ existe en el punto L2 (ver {@link Planificador#esCruceL2PuenteDeFierro})
 * es PREVENTIVO -- un margen de seguridad para el umbral del cruce de línea L4->L2 en ese punto
 * (RecorridoService.radioCruce()), no la corrección de un defecto confirmado ahí.
 *
 * <p>Defecto real confirmado al aplicar ese radio de cruce TAMBIÉN al aviso normal de "llegando"
 * viajando dentro de L2 (sin cruce de línea de por medio): Casa de Morelos y Puente de Fierro L2
 * quedan a solo ~247 m reales entre sí, así que un radio amplio (90 m, vs. los 50 m normales de
 * Mexibús) hacía sonar el aviso mucho antes de tiempo. El propio andén de Puente de Fierro L2 NO es
 * largo (es una parada L2 normal, ~50 m); el radio ampliado solo debe aplicar al cruce. Por eso
 * Puente de Fierro L2 vive APARTE de {@code ANDENES_LARGOS}: {@link Planificador#andenLargo} (usada
 * por el aviso normal) no lo incluye.
 *
 * <p>El radio de ese cruce tampoco puede ser tan amplio como el de L4 (90 m, {@code ANDEN_LARGO_M}):
 * el propio tramo recto de L4 (San Cristóbal -> Puente de Fierro L4) pasa a solo ~82 m reales del
 * punto de Puente de Fierro L2 -- con 90 m, viajando en L4 (antes de llegar a SU propia parada) ya se
 * entraba al radio de "cruce" de L2. Por eso el cruce usa SU PROPIO radio, más angosto (60 m, ver
 * {@code RecorridoService.CRUCE_PF_L2_M}), vía {@link Planificador#esCruceL2PuenteDeFierro} -- ya no
 * comparte {@link Planificador#andenLargo}/{@code ANDEN_LARGO_M} con las plataformas L4.
 *
 * <p>Ambas funciones son públicas y estáticas (no tocan Context/Android), así que se prueban
 * directamente contra la función real, sin reproducir su lógica a mano.
 */
public class PlanificadorAndenLargoTest {

    private static Planificador.Parada paradaEn(double lat, double lon) {
        return new Planificador.Parada("Puente de Fierro", 102, 0xFFFFFFFF, false, new LatLng(lat, lon), "");
    }

    @Test
    public void andenLargo_puenteDeFierroL4_siCalifica() {
        assertTrue("el punto de L4 (plataforma genuinamente larga) debe calificar siempre",
                Planificador.andenLargo(paradaEn(19.603558838994957, -99.033337377486620)));
    }

    @Test
    public void andenLargo_puenteDeFierroL2_yaNoCalifica() {
        // Defecto real confirmado (ver arriba): su propio andén es normal (~50 m) -- el radio amplio
        // solo hace falta como margen preventivo para el CRUCE de línea ahí, no para el aviso normal
        // de "llegando" dentro de L2.
        assertFalse("el punto de L2 NO debe calificar para el aviso normal de 'llegando'",
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

    @Test
    public void esCruceL2PuenteDeFierro_puenteDeFierroL2_siCalifica() {
        // Margen preventivo (ver arriba): el umbral del CRUCE L4->L2 cubre este punto con SU PROPIO
        // radio (60 m), no el de andén largo (90 m).
        assertTrue("el punto de L2 debe calificar para el umbral de CRUCE de línea",
                Planificador.esCruceL2PuenteDeFierro(paradaEn(19.601038, -99.033787)));
    }

    @Test
    public void esCruceL2PuenteDeFierro_puenteDeFierroL4_noCalifica() {
        // Defecto real confirmado (ver arriba): esta función ya NO incluye el punto de L4 -- ese usa
        // su propio radio de andén largo (andenLargo()/ANDEN_LARGO_M), nunca el de este cruce.
        assertFalse(Planificador.esCruceL2PuenteDeFierro(paradaEn(19.603558838994957, -99.033337377486620)));
    }

    @Test
    public void esCruceL2PuenteDeFierro_casaDeMorelos_noCalifica() {
        assertFalse(Planificador.esCruceL2PuenteDeFierro(paradaEn(19.599582, -99.035558)));
    }

    @Test
    public void esCruceL2PuenteDeFierro_upe_noCalifica() {
        assertFalse(Planificador.esCruceL2PuenteDeFierro(paradaEn(19.601909, -99.041841)));
    }

    // =====================================================================================
    // Clínica 93 (L4): su acceso real llega hasta 19.550236,-99.052184, ~108 m del centro
    // registrado -- más que el radio compartido de ANDENES_LARGOS (90 m). Vive aparte con su propio
    // radio ampliado (115 m), confirmado seguro: la separación real a sus vecinas (Industrial,
    // Servicios Administrativos) es de ≥480 m, sin riesgo de solape.
    // =====================================================================================
    @Test
    public void andenLargo_clinica93_yaNoCalifica() {
        // Se sacó de ANDENES_LARGOS (ver su comentario): ya no usa el radio compartido de 90 m, sino
        // el propio (115 m, ver esClinica93_...).
        assertFalse("Clínica 93 ya no debe calificar por el radio compartido de andén largo",
                Planificador.andenLargo(paradaEn(19.551183746934417, -99.051961377306110)));
    }

    @Test
    public void esClinica93_centroRegistrado_siCalifica() {
        assertTrue(Planificador.esClinica93(paradaEn(19.551183746934417, -99.051961377306110)));
    }

    @Test
    public void esClinica93_industrial_noCalifica() {
        assertFalse(Planificador.esClinica93(paradaEn(19.557130059809854, -99.049387308172480)));
    }

    @Test
    public void esClinica93_serviciosAdministrativos_noCalifica() {
        assertFalse(Planificador.esClinica93(paradaEn(19.546968629067063, -99.052960641578550)));
    }

    // Confirma el caso real que motivó el radio de 115 m: el punto de acceso real reportado
    // (19.550236,-99.052184) queda a ≤115 m del centro registrado de Clínica 93 -- con el radio
    // compartido anterior (90 m) NO llegaba a cubrirlo (~108 m > 90 m).
    @Test
    public void clinica93_radioAmpliado_cubreElAccesoRealReportado() {
        LatLng centro = new LatLng(19.551183746934417, -99.051961377306110);
        LatLng accesoReal = new LatLng(19.550236, -99.052184);
        double d = Linea.distancia(centro, accesoReal);
        assertTrue("el acceso real reportado debe quedar dentro del radio ampliado (115 m)", d <= 115.0);
        assertTrue("debe confirmarse que el radio compartido anterior (90 m) NO alcanzaba", d > 90.0);
    }

    // =====================================================================================
    // Clínica 76 (L4): su acceso real llega hasta 19.527470,-99.081484, ~93 m del centro
    // registrado -- más que el radio compartido de ANDENES_LARGOS (90 m). Vive aparte con su propio
    // radio ampliado (100 m), confirmado seguro: la separación real a sus vecinas (Vía Morelos,
    // Martín Carrera/El Vigilante) es de ≥825 m, sin riesgo de solape.
    // =====================================================================================
    @Test
    public void andenLargo_clinica76_yaNoCalifica() {
        assertFalse("Clínica 76 ya no debe calificar por el radio compartido de andén largo",
                Planificador.andenLargo(paradaEn(19.527056332415310, -99.082258897174240)));
    }

    @Test
    public void esClinica76_centroRegistrado_siCalifica() {
        assertTrue(Planificador.esClinica76(paradaEn(19.527056332415310, -99.082258897174240)));
    }

    @Test
    public void esClinica76_viaMorelos_noCalifica() {
        assertFalse(Planificador.esClinica76(paradaEn(19.530792149456463, -99.075455040827200)));
    }

    @Test
    public void esClinica76_martinCarrera_noCalifica() {
        assertFalse(Planificador.esClinica76(paradaEn(19.520976690807990, -99.092839154629400)));
    }

    // Confirma el caso real que motivó el radio de 100 m: el punto de acceso real reportado
    // (19.527470,-99.081484) queda a ≤100 m del centro registrado de Clínica 76 -- con el radio
    // compartido anterior (90 m) NO llegaba a cubrirlo (~93 m > 90 m).
    @Test
    public void clinica76_radioAmpliado_cubreElAccesoRealReportado() {
        LatLng centro = new LatLng(19.527056332415310, -99.082258897174240);
        LatLng accesoReal = new LatLng(19.527470, -99.081484);
        double d = Linea.distancia(centro, accesoReal);
        assertTrue("el acceso real reportado debe quedar dentro del radio ampliado (100 m)", d <= 100.0);
        assertTrue("debe confirmarse que el radio compartido anterior (90 m) NO alcanzaba", d > 90.0);
    }

    // =====================================================================================
    // 1ro de Mayo (Mexibús L1/L1 Exprés, 101/121 -- misma coordenada para ambos): acceso real mide
    // ~135 m de punta a punta; el centro registrado queda a ~79 m del extremo lejano, más que el radio
    // normal de Mexibús (50 m) pero SÍ cubierto por el radio compartido de ANDENES_LARGOS (90 m), así
    // que vive ahí (no necesita radio propio como Clínica 93/76). andenLargo() no filtra por línea, así
    // que una sola fila cubre ordinario (101) y exprés (121) por igual.
    // =====================================================================================
    @Test
    public void andenLargo_primeroDeMayo_siCalifica() {
        assertTrue("el centro registrado de 1ro de Mayo debe calificar como andén largo",
                Planificador.andenLargo(paradaEn(19.587645, -99.024727)));
    }

    // Confirma el caso real reportado: el extremo lejano del andén (19.588244,-99.024314) queda
    // cubierto por el radio compartido (90 m), que antes (radio normal de 50 m) no alcanzaba.
    @Test
    public void primeroDeMayo_radioCompartido_cubreElExtremoLejanoReportado() {
        LatLng centro = new LatLng(19.587645, -99.024727);
        LatLng extremoLejano = new LatLng(19.588244, -99.024314);
        double d = Linea.distancia(centro, extremoLejano);
        assertTrue("el extremo lejano reportado debe quedar dentro del radio compartido (90 m)", d <= 90.0);
        assertTrue("debe confirmarse que el radio normal de Mexibús (50 m) NO alcanzaba", d > 50.0);
    }

    // =====================================================================================
    // Resto de los andenes largos de Mexibús L1/L1 Exprés (101/121): UNITEC, Vocacional 3, Hospital,
    // Jardines de Morelos y Cuahutémoc Norte. Separación real a sus vecinas en L1 confirmada ≥314 m en
    // todos los casos, sin riesgo de solape con el radio compartido (90 m).
    // =====================================================================================
    @Test
    public void andenLargo_unitec_siCalifica() {
        assertTrue(Planificador.andenLargo(paradaEn(19.554361, -99.01925)));
    }

    @Test
    public void andenLargo_vocacional3_siCalifica() {
        assertTrue(Planificador.andenLargo(paradaEn(19.570011, -99.017984)));
    }

    @Test
    public void andenLargo_hospital_siCalifica() {
        assertTrue(Planificador.andenLargo(paradaEn(19.594708, -99.020001)));
    }

    @Test
    public void andenLargo_jardinesDeMorelos_siCalifica() {
        assertTrue(Planificador.andenLargo(paradaEn(19.600724, -99.016138)));
    }

    @Test
    public void andenLargo_cuahutemocNorte_siCalifica() {
        assertTrue(Planificador.andenLargo(paradaEn(19.646458, -99.005058)));
    }

    @Test
    public void andenLargo_industrialL1_noCalifica() {
        // Control: estación vecina de UNITEC en L1 (nombre coincide con la de L4, línea distinta) --
        // no debe calificar, confirma que el radio de 30 m de andenLargo() no se infló de más.
        assertFalse(Planificador.andenLargo(paradaEn(19.550115, -99.020875)));
    }

    @Test
    public void andenLargo_insurgentes_siCalifica() {
        // Solo existe en L1 ordinario (el Exprés no para ahí); vecinas en L1 (Central de Abastos a
        // 1127 m, Hidalgo a 875 m) sin riesgo de solape.
        assertTrue(Planificador.andenLargo(paradaEn(19.625682, -99.003135)));
    }

    @Test
    public void andenLargo_hidalgo_siCalifica() {
        // Solo existe en L1 ordinario; vecinas en L1 (Insurgentes a 875 m, Cuauhtémoc Sur a 502 m) sin
        // riesgo de solape.
        assertTrue(Planificador.andenLargo(paradaEn(19.633488, -99.002121)));
    }

    // =====================================================================================
    // CEDA Ecatepec (Central de Abastos): plataforma CUÁDRUPLE unida, tanto en Mexibús L4/L4 Exprés
    // como en L1/L1 Exprés (2 estaciones físicas distintas, cada una con su propio corredor, centros a
    // ~37 m uno del otro). No comparte radio con las zonas de Indios Verdes (70 m) -- aquí no hay
    // andenes ambiguos que distinguir, el radio es angosto: 7 m.
    // =====================================================================================
    @Test
    public void esCedaEcatepec_centroRegistradoL4_siCalifica() {
        assertTrue(Planificador.esCedaEcatepec(paradaEn(19.616013, -99.007485)));
    }

    @Test
    public void esCedaEcatepec_centroRegistradoL1_siCalifica() {
        assertTrue(Planificador.esCedaEcatepec(paradaEn(19.616632, -99.007987)));
    }

    @Test
    public void esCedaEcatepec_izcalliPalomas_noCalifica() {
        // Control: estación vecina en L4 -- no debe calificar.
        assertFalse(Planificador.esCedaEcatepec(paradaEn(19.612332, -99.019792)));
    }

    @Test
    public void distanciaCedaEcatepec_paradaDistinta_devuelveMenosUno() {
        assertEquals(-1.0, Planificador.distanciaCedaEcatepec(
                paradaEn(19.612332, -99.019792), 19.616013, -99.007485), 0.001);
    }

    // Confirma que el corredor de L4 cubre la plataforma: un punto sobre uno de sus extremos (el real,
    // dado por el usuario como esquina de la zona de cobertura) debe quedar a ~0 m del corredor, aunque
    // esté a más de 20 m del centro registrado (ya corregido) usado solo para identificar la estación.
    @Test
    public void distanciaCedaEcatepecL4_extremoDelCorredor_quedaMuyCerca() {
        Planificador.Parada p = paradaEn(19.616013, -99.007485);
        double d = Planificador.distanciaCedaEcatepec(p, 19.615841666666668, -99.00731388888889);
        assertTrue("el extremo del corredor debe quedar prácticamente sobre él (~0 m)", d <= 1.0);
    }

    // Confirma que el corredor de L1 (polilínea de 3 puntos, 2 tramos: el andén no es recto) cubre toda
    // su extensión: tanto el punto INTERMEDIO (vértice entre los 2 tramos) como el extremo final deben
    // quedar prácticamente sobre él, aunque disten de donde cae el otro extremo.
    @Test
    public void distanciaCedaEcatepecL1_verticeIntermedio_quedaMuyCerca() {
        Planificador.Parada p = paradaEn(19.616632, -99.007987);
        double d = Planificador.distanciaCedaEcatepec(p, 19.616758333333333, -99.00809444444444);
        assertTrue("el vértice intermedio del corredor debe quedar prácticamente sobre él (~0 m)", d <= 1.0);
    }

    @Test
    public void distanciaCedaEcatepecL1_extremoFinal_quedaMuyCerca() {
        Planificador.Parada p = paradaEn(19.616632, -99.007987);
        double d = Planificador.distanciaCedaEcatepec(p, 19.616900000000000, -99.00846666666666);
        assertTrue("el extremo final del corredor debe quedar prácticamente sobre él (~0 m)", d <= 1.0);
    }
}
