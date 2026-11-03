package com.memegrados.GeoMB;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Codifica a mano un FeedMessage sintético (mismas reglas del formato wire de Protobuf, sin usar
 * UnidadRtWire para construirlo -- así la prueba no queda circular) y verifica que
 * {@link UnidadRtWire#leerFeed(byte[])} lo decodifica correctamente. Cubre: valores normales,
 * campos de otro tipo que deben ignorarse (saltar) sin tronar, y más de una unidad en el mismo feed.
 */
public class UnidadRtWireTest {

    // ---- codificador mínimo (espejo independiente del formato wire, solo para esta prueba) ----

    private static void varint(ByteArrayOutputStream o, long v) {
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) { o.write(b | 0x80); } else { o.write(b); break; }
        }
    }

    private static void tag(ByteArrayOutputStream o, int campo, int tipo) { varint(o, (long) ((campo << 3) | tipo)); }

    private static void campoVarint(ByteArrayOutputStream o, int campo, long valor) {
        tag(o, campo, 0); varint(o, valor);
    }

    private static void campoFloat(ByteArrayOutputStream o, int campo, float valor) {
        tag(o, campo, 5);
        int bits = Float.floatToIntBits(valor);
        o.write(bits & 0xFF); o.write((bits >>> 8) & 0xFF); o.write((bits >>> 16) & 0xFF); o.write((bits >>> 24) & 0xFF);
    }

    private static void campoBytes(ByteArrayOutputStream o, int campo, byte[] datos) {
        tag(o, campo, 2); varint(o, datos.length);
        o.write(datos, 0, datos.length);
    }

    private static void campoCadena(ByteArrayOutputStream o, int campo, String s) {
        campoBytes(o, campo, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void campoMensaje(ByteArrayOutputStream o, int campo, byte[] submensaje) {
        campoBytes(o, campo, submensaje);
    }

    private static byte[] vehicleDescriptor(String id, String label, String placa) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoCadena(o, 1, id);
        campoCadena(o, 2, label);
        campoCadena(o, 3, placa);
        return o.toByteArray();
    }

    /** route_id es el campo 5 de TripDescriptor (NO el 2 -- ver UnidadRtWire, numeración real
     *  confirmada contra gtfs-realtime-bindings, la misma librería generada que usa el backend). */
    private static byte[] tripDescriptor(String routeId, int directionId) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoCadena(o, 5, routeId);
        campoVarint(o, 6, directionId);
        return o.toByteArray();
    }

    /** Con trip_id (campo 1) -- Sonda lo manda SIEMPRE, con route_id vacío en una fracción normal
     *  de las unidades (en depósito/en vacío). */
    private static byte[] tripDescriptor(String tripId, String routeId, int directionId) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoCadena(o, 1, tripId);
        campoCadena(o, 5, routeId);
        campoVarint(o, 6, directionId);
        return o.toByteArray();
    }

    private static byte[] position(float lat, float lon, float bearing, float speed) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoFloat(o, 1, lat);
        campoFloat(o, 2, lon);
        campoFloat(o, 3, bearing);
        campoFloat(o, 5, speed);
        return o.toByteArray();
    }

    /** timestamp es el campo 5 de VehiclePosition (NO el 6 -- ese es congestion_level; ver
     *  UnidadRtWire, numeración real confirmada contra gtfs-realtime-bindings). */
    private static byte[] vehiclePosition(byte[] trip, byte[] pos, long timestamp, byte[] vehicleDesc) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoMensaje(o, 1, trip);
        campoMensaje(o, 2, pos);
        campoVarint(o, 5, timestamp);
        campoMensaje(o, 8, vehicleDesc);
        return o.toByteArray();
    }

    private static byte[] feedEntity(String entityId, byte[] vehiclePos) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        campoCadena(o, 1, entityId);      // id, campo que UnidadRtWire debe IGNORAR sin tronar
        campoMensaje(o, 4, vehiclePos);
        return o.toByteArray();
    }

    private static byte[] feedMessage(byte[]... entities) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // header (campo 1) con un timestamp -- también debe ignorarse (no es 'entity').
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        campoVarint(header, 3, 1700000000L);
        campoMensaje(o, 1, header.toByteArray());
        for (byte[] e : entities) campoMensaje(o, 2, e);
        return o.toByteArray();
    }

    @Test
    public void unaUnidad_todosLosCamposSeLeenBien() throws IOException {
        byte[] vd = vehicleDescriptor("123", "0123", "ABC123");
        byte[] trip = tripDescriptor("19429", 1);
        byte[] pos = position(19.4f, -99.1f, 45f, 8.3f);
        byte[] vp = vehiclePosition(trip, pos, 1700000000L, vd);
        byte[] feed = feedMessage(feedEntity("e1", vp));

        List<UnidadRtWire.Registro> lista = UnidadRtWire.leerFeed(feed);
        assertEquals(1, lista.size());
        UnidadRtWire.Registro r = lista.get(0);
        assertEquals("123", r.id);
        assertEquals("0123", r.label);
        assertEquals("ABC123", r.placa);
        assertEquals("19429", r.routeId);
        assertEquals(1, r.directionId);
        assertEquals(19.4f, r.lat, 0.0001f);
        assertEquals(-99.1f, r.lon, 0.0001f);
        assertEquals(45f, r.bearing, 0.0001f);
        assertEquals(8.3f, r.speed, 0.0001f);
        assertEquals(1700000000L, r.timestamp);
    }

    @Test
    public void variasUnidades_seLeenTodasEnOrden() throws IOException {
        byte[] vp1 = vehiclePosition(tripDescriptor("A1", 0), position(1f, 2f, 3f, 4f), 100L,
                vehicleDescriptor("u1", "U1", ""));
        byte[] vp2 = vehiclePosition(tripDescriptor("B2", 1), position(5f, 6f, 7f, 8f), 200L,
                vehicleDescriptor("u2", "U2", ""));
        byte[] feed = feedMessage(feedEntity("e1", vp1), feedEntity("e2", vp2));

        List<UnidadRtWire.Registro> lista = UnidadRtWire.leerFeed(feed);
        assertEquals(2, lista.size());
        assertEquals("u1", lista.get(0).id);
        assertEquals("u2", lista.get(1).id);
        assertEquals("A1", lista.get(0).routeId);
        assertEquals("B2", lista.get(1).routeId);
    }

    @Test
    public void feedVacio_devuelveListaVacia() throws IOException {
        byte[] feed = feedMessage();
        assertTrue(UnidadRtWire.leerFeed(feed).isEmpty());
    }

    @Test
    public void tripId_seLeeInclusoConRouteIdVacio() throws IOException {
        // Caso real confirmado en el feed: Sonda deja route_id vacío (unidad en depósito/en vacío)
        // pero siempre manda trip_id -- ver RutasRepository.porTripId/RutasEstaticoDirecto.
        byte[] vp = vehiclePosition(tripDescriptor("fc73eb8fc73e9UP1", "", 0),
                position(19.4f, -99.1f, 45f, 8.3f), 1700000000L, vehicleDescriptor("9999", "", ""));
        byte[] feed = feedMessage(feedEntity("e1", vp));

        List<UnidadRtWire.Registro> lista = UnidadRtWire.leerFeed(feed);
        assertEquals(1, lista.size());
        assertEquals("fc73eb8fc73e9UP1", lista.get(0).tripId);
        assertEquals("", lista.get(0).routeId);
    }

    @Test
    public void timestamp_noSeConfundeConCongestionLevel() throws IOException {
        // VehiclePosition.congestion_level es el campo 6 (no se usa); timestamp es el 5. Antes se
        // leía al revés -- ver el bug documentado en UnidadRtWire.
        ByteArrayOutputStream vp = new ByteArrayOutputStream();
        campoMensaje(vp, 1, tripDescriptor("19429", 0));
        campoMensaje(vp, 2, position(19.4f, -99.1f, 0f, 0f));
        campoVarint(vp, 5, 1700000000L);   // timestamp real
        campoVarint(vp, 6, 2L);            // congestion_level -- debe IGNORARSE
        campoMensaje(vp, 8, vehicleDescriptor("1", "1", ""));
        byte[] feed = feedMessage(feedEntity("e1", vp.toByteArray()));

        List<UnidadRtWire.Registro> lista = UnidadRtWire.leerFeed(feed);
        assertEquals(1, lista.size());
        assertEquals(1700000000L, lista.get(0).timestamp);
    }

    @Test
    public void entityConAlert_oTripUpdate_seIgnoraSinTronar() throws IOException {
        // FeedEntity con solo 'alert' (campo 5, otro submensaje) -- no es un vehículo, se ignora.
        ByteArrayOutputStream entity = new ByteArrayOutputStream();
        campoCadena(entity, 1, "e9");
        campoMensaje(entity, 5, new byte[]{0x08, 0x01});   // "alert" ficticio, contenido irrelevante
        byte[] feed = feedMessage(entity.toByteArray());

        assertTrue(UnidadRtWire.leerFeed(feed).isEmpty());
    }
}
