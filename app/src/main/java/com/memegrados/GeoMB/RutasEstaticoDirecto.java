package com.memegrados.GeoMB;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Respaldo de {@link RutasRepository} para el catálogo de rutas: en vez de depender de un JSON
 * curado y congelado (ni el del backend ni el empaquetado en assets/routes.json), baja y parsea
 * el GTFS ESTÁTICO oficial de Sonda (routes.txt dentro del zip que reparte partnerValidation en
 * "urlStatic") -- el manual de Sonda dice que ese zip se regenera TODOS LOS DÍAS A MEDIANOCHE,
 * así que esto sí es un catálogo vivo, no una foto fija de cuando se compiló la app.
 *
 * Mismo criterio de últimísimo recurso que {@link UnidadRtDirecto}: solo se intenta cuando
 * {@link RutasRepository#descargar()} ya agotó el backend (EC2/Railway). No comparte código con
 * UnidadRtDirecto a propósito (mismo patrón que UnidadRtEdge/UnidadRtDirecto: cada nivel de
 * respaldo es independiente) para no arriesgar el feed en vivo, que ya funciona en producción,
 * con un refactor innecesario.
 *
 * Formato real de routes.txt (confirmado contra un ZIP real de Sonda, 2026-11-03):
 *   route_id,agency_id,route_short_name,route_long_name,route_desc,route_type,route_url,
 *   route_color,route_text_color,route_sort_order,continuous_pickup,continuous_drop_off
 * route_short_name = línea (1..7; Sonda solo cubre Metrobús -- Mexibús/Mexicable se agregan
 * aparte vía RutasMixtas, igual que siempre). route_long_name viene en minúsculas con el formato
 * "L##x##-N origen - destino": se le quita el código inicial (hasta el primer espacio) y se
 * separa el resto por el guion. OJO: Sonda es inconsistente con el espacio ANTES del guion (p.
 * ej. "el rosario- dep. 18 de marzo", sin espacio) -- el propio routes.json curado del backend
 * tiene ese caso con el destino vacío por partirlo con " - " literal; aquí se tolera para no
 * heredar ese defecto.
 *
 * De paso arma trip_id -> route_id (trips.txt, mismo zip): TripDescriptor.route_id es OPCIONAL
 * en GTFS-realtime cuando el feed usa trip_id -- confirmado contra el feed real, Sonda deja
 * route_id vacío en una fracción normal de las unidades (en depósito/en vacío/cambiando de
 * turno) y solo manda trip_id. Sin este mapa esas unidades salían "sin línea asignada" aunque
 * sí tuvieran un viaje real asignado -- ver {@link UnidadRtWire#aUnidades}.
 */
final class RutasEstaticoDirecto {

    private RutasEstaticoDirecto() {}

    private static final String URL_VALIDACION =
            "https://metrobus-gtfs.sinopticoplus.com/gtfs-api/partnerValidation";

    private static volatile String urlCache;
    private static volatile long expiraEpoch;

    /** Caché del catálogo YA parseado -- el zip pesa varios MB (incluye stop_times.txt, que se
     *  descarta), no tiene caso bajarlo y descomprimirlo de nuevo si el contenido solo cambia una
     *  vez al día (medianoche, según el manual de Sonda). */
    private static volatile List<Ruta> catalogoCache;
    private static volatile long catalogoExpiraEpoch;
    private static final long CATALOGO_VIGENCIA_SEG = 6 * 3600;

    /** trip_id -> route_id (trips.txt, mismo zip) -- ver {@link #rutaIdDeTripId}. Misma vigencia
     *  que {@link #catalogoCache} (se arman juntos, en la misma pasada del zip). */
    private static volatile Map<String, String> tripIdARouteId = Collections.emptyMap();

    static List<Ruta> obtener() throws Exception {
        if (EstadoServicio.enPausa()) throw new IllegalStateException("servicio en pausa");
        if (BuildConfig.SONDA_USUARIO.isEmpty() || BuildConfig.SONDA_SENHA.isEmpty()) {
            throw new IllegalStateException("sin credenciales locales (sonda.properties)");
        }
        long ahoraSeg = System.currentTimeMillis() / 1000L;
        List<Ruta> cache = catalogoCache;
        if (cache != null && ahoraSeg < catalogoExpiraEpoch) return cache;

        byte[] zip = Backend.descargarBinario(urlVigente());
        List<Ruta> lista = parsearZip(zip);
        catalogoCache = lista;
        catalogoExpiraEpoch = ahoraSeg + CATALOGO_VIGENCIA_SEG;
        return lista;
    }

    /** route_id del trip_id dado (trips.txt), o null si no se conoce -- respaldo para cuando el
     *  feed en vivo no manda route_id (ver {@link UnidadRtWire#aUnidades}). Vacío si este tier
     *  nunca llegó a descargar el zip (backend respondió, o también falló). */
    static String rutaIdDeTripId(String tripId) {
        return (tripId != null && !tripId.isEmpty()) ? tripIdARouteId.get(tripId) : null;
    }

    private static synchronized String urlVigente() throws Exception {
        long ahoraSeg = System.currentTimeMillis() / 1000L;
        if (urlCache != null && ahoraSeg < expiraEpoch) return urlCache;

        JSONObject cuerpo = new JSONObject();
        cuerpo.put("usuario", BuildConfig.SONDA_USUARIO);
        cuerpo.put("senha", BuildConfig.SONDA_SENHA);
        byte[] salida = cuerpo.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_VALIDACION).openConnection();
        String cuerpoResp;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(salida); }
            int status = conn.getResponseCode();
            if (status / 100 != 2) throw new IOException("HTTP " + status);
            cuerpoResp = leer(conn.getInputStream());
        } finally {
            conn.disconnect();
        }

        JSONObject resp = new JSONObject(cuerpoResp);
        String url = resp.optString("urlStatic", "");
        if (url.isEmpty()) throw new IOException("sin urlStatic");
        urlCache = url;
        // Dura 600 s de verdad (manual de Sonda); se deja de usar con margen, mismo criterio que
        // edge_rt_backend.py/UnidadRtDirecto, para nunca intentar con una URL a punto de caducar.
        expiraEpoch = ahoraSeg + (600 - 120);
        return url;
    }

    private static String leer(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        in.close();
        return sb.toString();
    }

    /** Extrae y parsea routes.txt Y trips.txt del zip (una sola pasada) -- el resto (stop_times.txt,
     *  que pesa decenas de MB, shapes.txt, calendar*.txt) se descarta sin guardarlo en memoria. */
    private static List<Ruta> parsearZip(byte[] zip) throws IOException {
        List<Ruta> rutas = null;
        Map<String, String> trips = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if ("routes.txt".equals(entry.getName())) {
                    rutas = parsearRoutesTxt(zin);
                } else if ("trips.txt".equals(entry.getName())) {
                    trips = parsearTripsTxt(zin);
                }
                if (rutas != null && trips != null) break;
            }
        }
        if (rutas == null) throw new IOException("routes.txt no encontrado en el GTFS estático");
        tripIdARouteId = trips != null ? trips : Collections.emptyMap();
        return rutas;
    }

    /** trip_id -> route_id. Columnas reales de trips.txt: route_id(0),service_id(1),trip_id(2),... */
    static Map<String, String> parsearTripsTxt(InputStream in) throws IOException {
        Map<String, String> mapa = new HashMap<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String encabezado = br.readLine();
        if (encabezado == null) return mapa;
        String linea;
        while ((linea = br.readLine()) != null) {
            if (linea.trim().isEmpty()) continue;
            String[] c = linea.split(",", -1);
            if (c.length < 3) continue;
            String routeId = c[0].trim(), tripId = c[2].trim();
            if (!routeId.isEmpty() && !tripId.isEmpty()) mapa.put(tripId, routeId);
        }
        return mapa;
    }

    static List<Ruta> parsearRoutesTxt(InputStream in) throws IOException {
        List<Ruta> lista = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String encabezado = br.readLine();
        if (encabezado == null) return lista;
        String linea;
        while ((linea = br.readLine()) != null) {
            if (linea.trim().isEmpty()) continue;
            Ruta r = parsearFila(linea);
            if (r != null) lista.add(r);
        }
        return lista;
    }

    /** Una fila de routes.txt -> Ruta, o null si no trae ni línea ni recorrido útil. Columnas
     *  reales: route_id(0),agency_id(1),route_short_name(2),route_long_name(3),route_desc(4),
     *  route_type(5),route_url(6),route_color(7),... */
    static Ruta parsearFila(String fila) {
        String[] c = fila.split(",", -1);
        if (c.length < 8) return null;
        String routeId = c[0].trim();
        int linea;
        try { linea = Integer.parseInt(c[2].trim()); } catch (NumberFormatException e) { linea = 0; }
        String[] od = origenDestino(c[3]);
        if (linea == 0 && od[0].isEmpty() && od[1].isEmpty()) return null;
        String color = c[7].trim();
        String colorHex = !color.isEmpty() ? "#" + color : "#D40D0D";
        return new Ruta(routeId, linea, od[0], od[1], colorHex);
    }

    /** "L06g03-2 el rosario- dep. 18 de marzo" -> ["El Rosario", "Dep. 18 De Marzo"]. Quita el
     *  código inicial (hasta el primer espacio) y separa el resto por el guion, tolerando que no
     *  tenga espacio de un lado (el texto de Sonda es inconsistente en eso). */
    static String[] origenDestino(String routeLongName) {
        String s = routeLongName.trim();
        int esp = s.indexOf(' ');
        String resto = esp >= 0 ? s.substring(esp + 1) : "";
        String[] partes = resto.split("\\s*-\\s*", 2);
        String origen = partes.length > 0 ? tituloCorto(partes[0]) : "";
        String destino = partes.length > 1 ? tituloCorto(partes[1]) : "";
        return new String[]{origen, destino};
    }

    /** Mayúscula la primera letra de cada palabra, sin tocar el resto (Sonda manda todo en
     *  minúsculas; coincide con la capitalización del catálogo curado, p. ej. "Villa De Aragón"). */
    static String tituloCorto(String s) {
        s = s.trim();
        if (s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        boolean inicioPalabra = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) {
                inicioPalabra = true;
                out.append(' ');
            } else {
                out.append(inicioPalabra ? Character.toUpperCase(ch) : ch);
                inicioPalabra = false;
            }
        }
        return out.toString();
    }
}
