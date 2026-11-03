package com.memegrados.GeoMB;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Prueba de {@link RutasEstaticoDirecto} contra filas REALES de routes.txt (tal cual vienen en un
 * GTFS estático de Sonda bajado el 2026-11-03), incluyendo el caso con el bug que ya tiene el
 * routes.json curado del backend: un guion sin espacio antes ("el rosario- dep...") que, partido
 * con " - " literal, deja el destino vacío. Aquí debe resolver bien ambos casos.
 */
public class RutasEstaticoDirectoTest {

    @Test
    public void origenDestino_conEspacioEnAmbosLados() {
        String[] od = RutasEstaticoDirecto.origenDestino("L03d03-1 tenayuca - la raza");
        assertEquals("Tenayuca", od[0]);
        assertEquals("La Raza", od[1]);
    }

    @Test
    public void origenDestino_sinEspacioAntesDelGuion() {
        // Este es el caso que el routes.json curado del backend parte MAL (destino queda vacío).
        String[] od = RutasEstaticoDirecto.origenDestino("L06g03-2 el rosario- dep. 18 de marzo");
        assertEquals("El Rosario", od[0]);
        assertEquals("Dep. 18 De Marzo", od[1]);
    }

    @Test
    public void origenDestino_conDobleEspacioAntesDelGuion() {
        String[] od = RutasEstaticoDirecto.origenDestino("L03d05-2 pueblo sta. cruz atoyac  - tenayuca");
        assertEquals("Pueblo Sta. Cruz Atoyac", od[0]);
        assertEquals("Tenayuca", od[1]);
    }

    @Test
    public void origenDestino_preservaAbreviaturasConPunto() {
        String[] od = RutasEstaticoDirecto.origenDestino("L04e02-1 buenavista - san lázaro ote");
        assertEquals("Buenavista", od[0]);
        assertEquals("San Lázaro Ote", od[1]);
    }

    @Test
    public void tituloCorto_soloCapitalizaPrimeraLetraDeCadaPalabra() {
        assertEquals("Aicm", RutasEstaticoDirecto.tituloCorto("aicm"));
    }

    @Test
    public void parsearFila_rutaNormal() {
        Ruta r = RutasEstaticoDirecto.parsearFila(
                "19470,1339,6,L06g01-1 villa de aragón - el rosario,,3,"
                        + "https://www.metrobus.cdmx.gob.mx/mapas-de-sistema/mapa-linea-6,E44599,FFFFFF,,,");
        assertNotNull(r);
        assertEquals("19470", r.routeId);
        assertEquals(6, r.linea);
        assertEquals("Villa De Aragón", r.origen);
        assertEquals("El Rosario", r.destino);
        assertEquals("#E44599", r.colorHex);
    }

    @Test
    public void parsearFila_sinColor_usaDefecto() {
        Ruta r = RutasEstaticoDirecto.parsearFila("1,1339,1,L01a01-1 a - b,,3,,,,,,");
        assertNotNull(r);
        assertEquals("#D40D0D", r.colorHex);
    }

    @Test
    public void parsearFila_sinLineaNiRecorrido_seDescarta() {
        assertNull(RutasEstaticoDirecto.parsearFila("1,1339,,,,3,,,,,,"));
    }

    @Test
    public void parsearRoutesTxt_archivoCompleto() throws IOException {
        String csv = "route_id,agency_id,route_short_name,route_long_name,route_desc,route_type,route_url,route_color,route_text_color,route_sort_order,continuous_pickup,continuous_drop_off\n"
                + "19470,1339,6,L06g01-1 villa de aragón - el rosario,,3,url,E44599,FFFFFF,,,\n"
                + "19475,1339,6,L06g03-2 el rosario- dep. 18 de marzo,,3,url,E44599,FFFFFF,,,\n"
                + "\n";
        InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<Ruta> lista = RutasEstaticoDirecto.parsearRoutesTxt(in);
        assertEquals(2, lista.size());
        assertEquals("El Rosario", lista.get(0).destino);
        assertEquals("Dep. 18 De Marzo", lista.get(1).destino);
    }
}
