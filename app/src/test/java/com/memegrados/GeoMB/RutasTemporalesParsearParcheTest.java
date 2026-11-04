package com.memegrados.GeoMB;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link RutasTemporales#parsearParche} es la parte SIN Context (pura) de instalar un parche de
 * ruta temporal (desvío/servicio de apoyo cargado desde /admin/trazos del backend, ver
 * admin_trazos.py): valida lo mínimo (número, >=2 estaciones, >=2 puntos de ruta) antes de armar
 * la {@link Linea} que GtfsRepository.aplicarParches() instala. Un parche malformado devuelve
 * null -- NO debe tumbar la carga de los demás parches ni de la app.
 *
 * Solo cubre las validaciones que devuelven null ANTES de construir la Linea: el camino feliz
 * (new Linea(...)) usa Color.parseColor, que en este harness (JUnit puro, sin Robolectric) no
 * está mockeado -- mismo motivo por el que ninguna otra prueba del proyecto construye una Linea
 * real directamente.
 */
public class RutasTemporalesParsearParcheTest {

    private static final String PARCHE_OK = "{"
            + "\"numero\":11290,\"nombre\":\"Desvío de prueba\",\"nota\":\"nota\","
            + "\"estaciones\":["
            + "{\"nombre\":\"A\",\"lat\":19.1,\"lon\":-99.1},"
            + "{\"nombre\":\"B\",\"lat\":19.2,\"lon\":-99.2}],"
            + "\"ruta\":[[19.1,-99.1],[19.15,-99.15],[19.2,-99.2]]}";

    @Test
    public void sinNumero_devuelveNull() throws Exception {
        JSONObject p = new JSONObject(PARCHE_OK);
        p.remove("numero");
        assertNull(RutasTemporales.parsearParche(p, "#9E9E9E"));
    }

    @Test
    public void menosDeDosEstaciones_devuelveNull() throws Exception {
        JSONObject p = new JSONObject(PARCHE_OK);
        p.getJSONArray("estaciones").remove(1);
        assertNull(RutasTemporales.parsearParche(p, "#9E9E9E"));
    }

    @Test
    public void menosDeDosPuntosDeRuta_devuelveNull() throws Exception {
        JSONObject p = new JSONObject(PARCHE_OK);
        p.put("ruta", new org.json.JSONArray("[[19.1,-99.1]]"));
        assertNull(RutasTemporales.parsearParche(p, "#9E9E9E"));
    }

    @Test
    public void estacionSinLat_devuelveNull_noTumba() throws Exception {
        JSONObject p = new JSONObject(PARCHE_OK);
        p.getJSONArray("estaciones").getJSONObject(0).remove("lat");
        assertNull(RutasTemporales.parsearParche(p, "#9E9E9E"));
    }

    @Test
    public void jsonVacio_devuelveNull() {
        assertNull(RutasTemporales.parsearParche(new JSONObject(), "#9E9E9E"));
    }
}
