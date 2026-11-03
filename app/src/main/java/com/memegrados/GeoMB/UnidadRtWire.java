package com.memegrados.GeoMB;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lector mínimo del formato "wire" de Protobuf, escrito a mano SOLO para los campos de
 * VehiclePosition (GTFS-realtime) que esta app necesita -- no es un runtime de Protobuf
 * genérico ni usa código generado por protoc: así se evita agregar la dependencia completa
 * (y el riesgo de que el plugin de Gradle tenga que descargar el binario de protoc, que en este
 * proyecto no vale la pena solo para 8 campos) y, de paso, un APK decompilado no encuentra clases
 * con nombres reconocibles tipo "VehiclePosition"/"FeedMessage" que delaten de inmediato de qué
 * servicio viene esto ni qué campos lee.
 *
 * Esquema real (gtfs-realtime.proto, oficial -- verificado contra gtfs-realtime-bindings 3.0.0,
 * la MISMA librería generada que usa metrobus_app/app.py del lado del servidor; NO contra la
 * memoria de una versión vieja del estándar, que tenía la numeración de TripDescriptor distinta
 * y causó un bug real -- ver abajo):
 *   FeedMessage.entity[] (campo 2) -> FeedEntity.vehicle (campo 4) ->
 *   VehiclePosition { trip (1), position (2), current_stop_sequence (3), current_status (4),
 *       timestamp (5), congestion_level (6), stop_id (7), vehicle (8), occupancy_status (9) };
 *   TripDescriptor { trip_id (1), start_time (2), start_date (3), schedule_relationship (4),
 *       route_id (5), direction_id (6), modified_trip (7) };
 *   Position { latitude (1), longitude (2), bearing (3), odometer (4), speed (5) };
 *   VehicleDescriptor { id (1), label (2), license_plate (3), wheelchair_accessible (4) }.
 *
 * BUG REAL que tenía esta clase (corregido): por una numeración de TripDescriptor desactualizada,
 * route_id se leía del campo 2 (en realidad start_time, "HH:MM:SS") y direction_id del campo 3
 * (en realidad start_date) -- y timestamp se leía del campo 6 de VehiclePosition (en realidad
 * congestion_level, 0-4) en vez del campo 5 real. Confirmado al comparar contra las bindings
 * reales: el decodificador en sí estaba bien escrito (un TripDescriptor sintético bien formado
 * decodificaba correcto), el problema era la numeración de campos asumida. Efecto visible: casi
 * ninguna unidad resolvía línea por route_id directo (el respaldo por trip_id, agregado antes,
 * terminaba cargando con casi todo el trabajo sin que se notara la causa real), y
 * DiagnosticoReporte.recolectar() marcaba SIEMPRE "datos desactualizados" para una unidad seguida
 * vía este camino (edadSeg = ahora - timestamp, con timestamp siendo en realidad 0-4).
 */
final class UnidadRtWire {

    static final class Registro {
        String id, label, placa, routeId, tripId;
        int directionId = -1;
        float lat = Float.NaN, lon = Float.NaN, bearing, speed;
        long timestamp;
    }

    private UnidadRtWire() {}

    /** Arma la lista de {@link UnidadReal} a partir de los registros crudos ya decodificados --
     *  mismo filtro (fantasmas, coordenadas inválidas) y mismo join con {@link RutasRepository}
     *  (línea/origen/destino por route_id) que usa el servidor hoy antes de mandar el JSON. */
    static java.util.List<UnidadReal> aUnidades(List<Registro> crudos) {
        List<UnidadReal> lista = new ArrayList<>(crudos.size());
        for (Registro c : crudos) {
            if (Float.isNaN(c.lat) || Float.isNaN(c.lon)) continue;

            String numero = (c.label != null && !c.label.isEmpty()) ? c.label
                    : (c.id != null && !c.id.isEmpty() ? c.id : "—");
            String low = numero.toLowerCase();
            if (low.contains("test") || low.contains("maqueta") || low.contains("carraci")) continue;

            // El route_id del TripDescriptor es OPCIONAL en GTFS-realtime cuando el feed usa
            // trip_id -- Sonda deja route_id vacío en una fracción normal de las unidades (en
            // depósito/en vacío/cambiando de turno) y solo manda trip_id. La forma correcta de
            // resolver esos casos es por trips.txt (trip_id -> route_id), no asumir que faltan
            // del catálogo.
            Ruta ruta = RutasRepository.porRouteId(c.routeId);
            if (ruta == null) ruta = RutasRepository.porTripId(c.tripId);
            Integer linea = (ruta != null && ruta.linea != 0) ? ruta.linea : null;
            String destino = (ruta != null && !ruta.destino.isEmpty()) ? ruta.destino : null;
            String origen = (ruta != null && !ruta.origen.isEmpty()) ? ruta.origen : null;

            Modelos.Ficha ficha = Modelos.paraEconomico(numero);
            // Igual que RealtimeRepository.parsear(): el feed manda la velocidad en km/h.
            float velMs = c.speed / 3.6f;

            lista.add(new UnidadReal(numero, linea, destino, origen, c.routeId, c.tripId,
                    ficha.empresa, ficha.marca, ficha.modelo, c.placa != null ? c.placa : "",
                    c.lat, c.lon, c.bearing, velMs, c.timestamp));
        }
        return lista;
    }

    /** Parsea un FeedMessage completo y devuelve un registro por cada FeedEntity con 'vehicle'. */
    static List<Registro> leerFeed(byte[] datos) {
        List<Registro> out = new ArrayList<>();
        Cursor c = new Cursor(datos, 0, datos.length);
        while (c.quedaAlgo()) {
            int tag = c.tag();
            if (c.campo(tag) == 2 && c.tipo(tag) == 2) {           // FeedMessage.entity
                Registro r = leerEntity(c.bytesDelimitados());
                if (r != null) out.add(r);
            } else {
                c.saltar(c.tipo(tag));
            }
        }
        return out;
    }

    private static Registro leerEntity(byte[] datos) {
        Cursor c = new Cursor(datos, 0, datos.length);
        Registro r = null;
        while (c.quedaAlgo()) {
            int tag = c.tag();
            if (c.campo(tag) == 4 && c.tipo(tag) == 2) {           // FeedEntity.vehicle
                r = leerVehiclePosition(c.bytesDelimitados());
            } else {
                c.saltar(c.tipo(tag));
            }
        }
        return r;
    }

    private static Registro leerVehiclePosition(byte[] datos) {
        Registro r = new Registro();
        Cursor c = new Cursor(datos, 0, datos.length);
        while (c.quedaAlgo()) {
            int tag = c.tag();
            int campo = c.campo(tag), tipo = c.tipo(tag);
            switch (campo) {
                case 1: if (tipo == 2) leerTrip(c.bytesDelimitados(), r); else c.saltar(tipo); break;
                case 2: if (tipo == 2) leerPosicion(c.bytesDelimitados(), r); else c.saltar(tipo); break;
                case 5: if (tipo == 0) r.timestamp = c.varint(); else c.saltar(tipo); break;
                case 8: if (tipo == 2) leerVehicleDescriptor(c.bytesDelimitados(), r); else c.saltar(tipo); break;
                default: c.saltar(tipo);
            }
        }
        return r;
    }

    private static void leerTrip(byte[] datos, Registro r) {
        Cursor c = new Cursor(datos, 0, datos.length);
        while (c.quedaAlgo()) {
            int tag = c.tag();
            int campo = c.campo(tag), tipo = c.tipo(tag);
            if (campo == 1 && tipo == 2) r.tripId = c.cadena();
            else if (campo == 5 && tipo == 2) r.routeId = c.cadena();
            else if (campo == 6 && tipo == 0) r.directionId = (int) c.varint();
            else c.saltar(tipo);
        }
    }

    private static void leerPosicion(byte[] datos, Registro r) {
        Cursor c = new Cursor(datos, 0, datos.length);
        while (c.quedaAlgo()) {
            int tag = c.tag();
            int campo = c.campo(tag), tipo = c.tipo(tag);
            if (campo == 1 && tipo == 5) r.lat = c.flotante();
            else if (campo == 2 && tipo == 5) r.lon = c.flotante();
            else if (campo == 3 && tipo == 5) r.bearing = c.flotante();
            else if (campo == 5 && tipo == 5) r.speed = c.flotante();
            else c.saltar(tipo);
        }
    }

    private static void leerVehicleDescriptor(byte[] datos, Registro r) {
        Cursor c = new Cursor(datos, 0, datos.length);
        while (c.quedaAlgo()) {
            int tag = c.tag();
            int campo = c.campo(tag), tipo = c.tipo(tag);
            if (campo == 1 && tipo == 2) r.id = c.cadena();
            else if (campo == 2 && tipo == 2) r.label = c.cadena();
            else if (campo == 3 && tipo == 2) r.placa = c.cadena();
            else c.saltar(tipo);
        }
    }

    /** Cursor sobre el formato wire: varint, 32-bit (float), length-delimited (bytes/string). */
    static final class Cursor {
        private final byte[] buf;
        private int pos;
        private final int fin;

        Cursor(byte[] buf, int off, int fin) { this.buf = buf; this.pos = off; this.fin = fin; }

        boolean quedaAlgo() { return pos < fin; }

        long varint() {
            long resultado = 0; int despl = 0;
            while (true) {
                byte b = buf[pos++];
                resultado |= (long) (b & 0x7F) << despl;
                if ((b & 0x80) == 0) break;
                despl += 7;
                if (despl >= 64) throw new IllegalStateException("varint demasiado largo");
            }
            return resultado;
        }

        int tag() { return (int) varint(); }
        int campo(int tag) { return tag >>> 3; }
        int tipo(int tag) { return tag & 7; }

        void saltar(int tipo) {
            switch (tipo) {
                case 0: varint(); break;                                   // varint
                case 1: pos += 8; break;                                   // 64-bit
                case 2: { int len = (int) varint(); pos += len; break; }   // length-delimited
                // OJO: "pos += (int) varint();" en una sola línea se ve bien pero está MAL --
                // Java evalúa el 'pos' del lado izquierdo ANTES de llamar a varint() (que a su vez
                // mueve 'pos' como efecto secundario al consumir el byte de longitud), así que la
                // suma usa el valor VIEJO de pos y el salto queda corto por los bytes que varint()
                // ya había avanzado. Se separa en 2 statements para que la suma use el pos YA
                // actualizado tras leer la longitud.
                case 5: pos += 4; break;                                   // 32-bit
                default: throw new IllegalStateException("wire type desconocido " + tipo);
            }
        }

        byte[] bytesDelimitados() {
            int len = (int) varint();
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        String cadena() { return new String(bytesDelimitados(), StandardCharsets.UTF_8); }

        float flotante() {
            int bits = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
                    | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }
    }
}
