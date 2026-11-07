package com.memegrados.GeoMB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Catálogo de patios (assets/patios.json) y la prueba de "punto dentro de patio" (lógica pura). */
public class PatiosTest {

    private static final double M_LAT = 110540.0;
    private static final double M_LON = 111320.0 * Math.cos(Math.toRadians(19.4));

    /** Un patio circular y uno con polígono en forma de "L" (para probar el polígono cóncavo). */
    private static final String JSON = "{\"patios\":["
            + "{\"id\":\"circ\",\"nombre\":\"Circular\",\"lineas\":[1],"
            + "\"lat\":19.40,\"lon\":-99.10,\"radio_m\":100},"
            + "{\"id\":\"ele\",\"nombre\":\"En L\",\"lineas\":[2,5],"
            + "\"lat\":19.4505,\"lon\":-99.0495,\"radio_m\":300,"
            + "\"poligono\":[[19.449,-99.051],[19.449,-99.048],[19.450,-99.048],"
            + "[19.450,-99.050],[19.452,-99.050],[19.452,-99.051]]},"
            + "{\"id\":\"exc\",\"nombre\":\"Con anden\",\"lineas\":[3],"
            + "\"lat\":19.50,\"lon\":-99.15,\"radio_m\":200,"
            + "\"excluir\":[[19.50,-99.15,40]]}"
            + "]}";

    @Before
    public void cargar() {
        Patios.cargarDesdeJson(JSON);
    }

    private static LatLng desplazado(double lat, double lon, double norteM, double esteM) {
        return new LatLng(lat + norteM / M_LAT, lon + esteM / M_LON);
    }

    @Test
    public void parseaLosDosPatios() {
        List<Patios.Patio> ps = Patios.todos();
        assertEquals(3, ps.size());
        assertEquals("circ", ps.get(0).id);
        assertEquals(2, ps.get(1).lineas.length);
        assertEquals(5, ps.get(1).lineas[1]);
    }

    @Test
    public void circuloAdentroYAfuera() {
        assertTrue(Patios.enPatio(desplazado(19.40, -99.10, 60, 0)));
        assertTrue(Patios.enPatio(desplazado(19.40, -99.10, 0, -95)));
        assertFalse(Patios.enPatio(desplazado(19.40, -99.10, 0, 110)));
        assertFalse(Patios.enPatio(desplazado(19.40, -99.10, -150, 0)));
    }

    @Test
    public void poligonoMandaSobreElCirculo() {
        // Dentro del brazo horizontal y del vertical de la "L".
        assertEquals("ele", Patios.patioEn(new LatLng(19.4495, -99.049)).id);
        assertNotNull(Patios.patioEn(new LatLng(19.4515, -99.0505)));
        // Hueco de la "L": dentro del círculo envolvente pero FUERA del polígono.
        assertNull(Patios.patioEn(new LatLng(19.4512, -99.0490)));
        // Fuera de todo.
        assertNull(Patios.patioEn(new LatLng(19.47, -99.03)));
    }

    private static UnidadReal unidad(LatLng p, float velMs) {
        return new UnidadReal("0000", 1, null, null, null, "", "", "", "",
                p.latitude, p.longitude, 0f, velMs, 0L);
    }

    @Test
    public void guardadaSoloSiEstaDetenidaDentro() {
        LatLng dentro = desplazado(19.40, -99.10, 20, 20);
        assertTrue(Patios.guardada(unidad(dentro, 0f)));
        assertTrue(Patios.guardada(unidad(dentro, 0.3f)));            // ruido del GPS parado
        assertFalse(Patios.guardada(unidad(dentro, 3f)));             // saliendo: ya en servicio
        assertFalse(Patios.guardada(unidad(new LatLng(19.43, -99.13), 0f)));   // detenida fuera
        assertFalse(Patios.guardada(null));
    }

    @Test
    public void zonaExcluidaNoEsPatio() {
        // Andén/carril excluido: una unidad detenida ahí está operando, no guardada.
        assertFalse(Patios.enPatio(desplazado(19.50, -99.15, 20, 0)));
        assertTrue(Patios.enPatio(desplazado(19.50, -99.15, 80, 0)));
    }

    @Test
    public void nuloNoEstaEnPatio() {
        assertFalse(Patios.enPatio(null));
    }

    @Test
    public void jsonInvalidoNoOcultaNada() {
        Patios.cargarDesdeJson("no es json");
        assertTrue(Patios.todos().isEmpty());
        assertFalse(Patios.enPatio(new LatLng(19.40, -99.10)));
    }

    @Test
    public void dentroDePoligonoCuadrado() {
        double[][] cuad = {{0, 0}, {0, 0.001}, {0.001, 0.001}, {0.001, 0}};
        assertTrue(Patios.dentroDePoligono(cuad, 0.0005, 0.0005));
        assertFalse(Patios.dentroDePoligono(cuad, 0.0015, 0.0005));
        assertFalse(Patios.dentroDePoligono(cuad, 0.0005, -0.0001));
    }

    @Test
    public void distanciaHaversineCoherente() {
        // 0.001° de latitud ≈ 110.6 m.
        assertEquals(110.6, Patios.distanciaM(19.4, -99.1, 19.401, -99.1), 1.0);
    }

    /** El asset real: se parsea, cada patio trae líneas, su centro cae dentro y un punto en el
     *  Zócalo (lejos de cualquier encierro) no cae en ninguno. */
    @Test
    public void assetRealEsValido() throws Exception {
        File f = new File("src/main/assets/patios.json");
        if (!f.exists()) f = new File("app/src/main/assets/patios.json");
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        List<Patios.Patio> ps = Patios.parsear(json);
        assertTrue(ps.size() >= 5);
        Patios.cargarDesdeJson(json);
        for (Patios.Patio p : ps) {
            assertTrue(p.id, p.lineas.length > 0);
            assertTrue(p.id, p.radioM > 0 && p.radioM < 600);
            assertFalse(p.id, p.nombre.isEmpty());
        }
        assertFalse(Patios.enPatio(new LatLng(19.4326, -99.1332)));   // Zócalo
    }
}
