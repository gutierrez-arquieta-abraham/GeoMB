package com.memegrados.GeoMB;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Prueba de {@link RutasRepository#parsearRutas(String)} -- la pieza pura compartida entre el
 * camino normal (Backend.descargar, EC2) y el respaldo nuevo (assets/routes.json, empaquetado en
 * la app para cuando el backend no responda). Cubre el parseo básico y, con el
 * archivo REAL ya empaquetado, confirma que sigue siendo válido y que L1/L6 (casos ya conocidos
 * de esta sesión) resuelven bien.
 */
public class RutasRepositoryParsearRutasTest {

    @Test
    public void rutaNormal_conLineaYRecorrido_seConserva() throws Exception {
        String json = "[{\"route_id\":\"1\",\"line\":\"6\",\"origen\":\"El Rosario\",\"destino\":\"Villa de Aragón\",\"color\":\"#AAA\"}]";
        List<Ruta> lista = RutasRepository.parsearRutas(json);
        assertEquals(1, lista.size());
        assertEquals(6, lista.get(0).linea);
        assertEquals("El Rosario", lista.get(0).origen);
    }

    @Test
    public void sinLineaPeroConRecorrido_seConservaConLineaCero() throws Exception {
        String json = "[{\"route_id\":\"2\",\"origen\":\"A\",\"destino\":\"B\"}]";
        List<Ruta> lista = RutasRepository.parsearRutas(json);
        assertEquals(1, lista.size());
        assertEquals(0, lista.get(0).linea);
    }

    @Test
    public void sinLineaNiRecorrido_seDescarta() throws Exception {
        String json = "[{\"route_id\":\"3\"}]";
        assertTrue(RutasRepository.parsearRutas(json).isEmpty());
    }

    @Test
    public void listaVacia_devuelveListaVacia() throws Exception {
        assertTrue(RutasRepository.parsearRutas("[]").isEmpty());
    }

    @Test
    public void archivoRealEmpaquetado_esValidoYTraeL1yL6() throws IOException, org.json.JSONException {
        // Mismo archivo que se lee como asset en Android (app/src/main/assets/routes.json) --
        // aquí se lee por ruta de archivo porque un JUnit plano no tiene AssetManager, pero es
        // EXACTAMENTE el mismo contenido que RutasRepository.descargar() usaría de respaldo.
        String ruta = "src/main/assets/routes.json";
        assertTrue("falta el asset empaquetado -- ver RutasRepository.descargar()", Files.exists(Paths.get(ruta)));
        String json = new String(Files.readAllBytes(Paths.get(ruta)), java.nio.charset.StandardCharsets.UTF_8);
        List<Ruta> lista = RutasRepository.parsearRutas(json);
        assertFalse("el archivo real no debe quedar vacío tras parsear", lista.isEmpty());

        boolean hayL1 = false, hayL6 = false;
        for (Ruta r : lista) {
            if (r.linea == 1) hayL1 = true;
            if (r.linea == 6) hayL6 = true;
        }
        assertTrue("falta L1 en el respaldo empaquetado", hayL1);
        assertTrue("falta L6 en el respaldo empaquetado", hayL6);
    }
}
