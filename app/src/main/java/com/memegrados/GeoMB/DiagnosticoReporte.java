package com.memegrados.GeoMB;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Recolecta, de MEJOR ESFUERZO y SIN BLOQUEAR, el diagnóstico técnico automático que acompaña un
 * reporte (ver {@link ReporteApp}/{@link ReporteEntity}): dispositivo, conectividad, GPS y
 * contexto de transporte. No dispara ninguna petición de red ni de ubicación nueva -- solo LEE
 * estado que la app ya mantiene ({@link Red}, {@link RealtimeRepository}, {@link Backend},
 * {@link RecorridoService}) o el último fix de ubicación ya en caché del sistema (nunca pide uno
 * nuevo ni guarda coordenadas). Cualquier dato no disponible de inmediato queda como
 * {@link #NO_DISPONIBLE} (o null/0 según el tipo) en vez de esperar o fallar.
 */
public final class DiagnosticoReporte {

    public static final String NO_DISPONIBLE = "NO_DISPONIBLE";

    // Estados de seguimiento (contexto de transporte en el momento del reporte).
    public static final String SEG_ACTIVO = "SEGUIMIENTO_ACTIVO";
    public static final String SEG_SIN_DATOS = "SIN_DATOS";
    public static final String SEG_DESACTUALIZADO = "DATOS_DESACTUALIZADOS";
    public static final String SEG_UNIDAD_NO_ENCONTRADA = "UNIDAD_NO_ENCONTRADA";
    public static final String SEG_ERROR_API = "ERROR_API";

    /** Una unidad sin reporte del feed hace más de esto se considera desactualizada. */
    private static final long DATO_VIEJO_SEG = 180;

    private DiagnosticoReporte() {}

    /** Snapshot de todo lo recolectado; sus campos se copian 1:1 a {@link ReporteEntity}. */
    public static final class Datos {
        public String versionApp = "?";
        public long versionCode;
        public String fabricante = Build.MANUFACTURER;
        public String modelo = Build.MODEL;
        public String androidRelease = Build.VERSION.RELEASE;
        public int apiLevel = Build.VERSION.SDK_INT;
        public String arquitectura = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : NO_DISPONIBLE;

        public String conectividadTipo = NO_DISPONIBLE;
        public Boolean internetValidado;          // null = NO_DISPONIBLE
        public long ultimaSincronizacionTs;        // 0 = NO_DISPONIBLE
        public long edadDatoMs = -1;                // -1 = NO_DISPONIBLE

        public Boolean gpsActivado;                 // null = NO_DISPONIBLE
        public String permisoUbicacion = "NO_SOLICITADO";
        public String proveedorUbicacion;           // null = NO_DISPONIBLE
        public long ubicacionTs;                     // 0 = NO_DISPONIBLE (solo antigüedad, NUNCA lat/lon)

        public int lineaContexto;                    // 0 = NO_DISPONIBLE
        public String sentidoContexto;
        public String estacionActual;
        public String estacionSiguiente;
        public String unidadContexto;
        public String estadoSeguimiento = SEG_SIN_DATOS;

        public String backendEndpoint;
        public int backendHttpStatus;                // 0 = NO_DISPONIBLE
        public long backendLatenciaMs = -1;
    }

    public static Datos recolectar(Context ctx) {
        Datos d = new Datos();
        Context app = ctx.getApplicationContext();

        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (pi.versionName != null) d.versionApp = pi.versionName;
            d.versionCode = pi.versionCode;
        } catch (Exception ignore) {}

        recolectarConectividad(app, d);
        recolectarGps(app, d);
        recolectarContextoTransporte(d);
        d.backendEndpoint = Backend.ultimoEndpoint;
        d.backendHttpStatus = Backend.ultimoHttpStatus;
        d.backendLatenciaMs = Backend.ultimaLatenciaMs;
        return d;
    }

    private static void recolectarConectividad(Context app, Datos d) {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            if (caps == null) {
                d.conectividadTipo = "SIN_RED";
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                d.conectividadTipo = "WIFI";
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                d.conectividadTipo = "DATOS_MOVILES";
            } else {
                d.conectividadTipo = "OTRA";
            }
            d.internetValidado = Red.hayInternetReal(app);
        } catch (Throwable t) {
            d.conectividadTipo = NO_DISPONIBLE;
        }
        d.ultimaSincronizacionTs = RealtimeRepository.ultimoFetchExitoTs;
        d.edadDatoMs = d.ultimaSincronizacionTs > 0
                ? System.currentTimeMillis() - d.ultimaSincronizacionTs : -1;
    }

    private static void recolectarGps(Context app, Datos d) {
        try {
            boolean concedido = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            d.permisoUbicacion = concedido ? "CONCEDIDO" : "DENEGADO";
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                d.gpsActivado = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                if (concedido) {
                    Location loc = ultimoFixConocido(lm);
                    if (loc != null) {
                        d.ubicacionTs = loc.getTime();     // SOLO la antigüedad -- nunca lat/lon
                        d.proveedorUbicacion = loc.getProvider();
                    }
                }
            }
        } catch (Throwable t) {
            // deja los valores por defecto (NO_DISPONIBLE / null)
        }
    }

    /** Último fix YA EN CACHÉ del sistema (no pide uno nuevo): primero GPS, si no hay, red. */
    private static Location ultimoFixConocido(LocationManager lm) {
        Location loc = null;
        try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException|IllegalArgumentException ignore) {}
        if (loc == null) {
            try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException|IllegalArgumentException ignore) {}
        }
        return loc;
    }

    private static void recolectarContextoTransporte(Datos d) {
        try {
            if (RecorridoService.activo && RecorridoService.paradas != null
                    && RecorridoService.actualIdx >= 0 && RecorridoService.actualIdx < RecorridoService.paradas.size()) {
                // Recorrido guiado en curso: la estación actual/siguiente y el sentido se conocen con certeza.
                Planificador.Parada actual = RecorridoService.paradas.get(RecorridoService.actualIdx);
                d.estacionActual = actual.nombre;
                d.lineaContexto = actual.linea;
                d.sentidoContexto = RecorridoService.terminal;
                int sig = RecorridoService.actualIdx + 1;
                if (sig < RecorridoService.paradas.size()) d.estacionSiguiente = RecorridoService.paradas.get(sig).nombre;
                d.estadoSeguimiento = SEG_ACTIVO;
            } else if (RealtimeRepository.unidadSeleccionada != null) {
                d.unidadContexto = RealtimeRepository.unidadSeleccionada;
                UnidadReal u = RealtimeRepository.get().buscar(RealtimeRepository.unidadSeleccionada);
                if (u == null) {
                    d.estadoSeguimiento = SEG_UNIDAD_NO_ENCONTRADA;
                } else {
                    if (u.linea != null) d.lineaContexto = u.linea;
                    long edadSeg = (System.currentTimeMillis() / 1000) - u.timestamp;
                    d.estadoSeguimiento = (u.timestamp > 0 && edadSeg < DATO_VIEJO_SEG) ? SEG_ACTIVO : SEG_DESACTUALIZADO;
                }
            } else if (RealtimeRepository.lineaSeleccionada >= 1) {
                d.lineaContexto = RealtimeRepository.lineaSeleccionada;
            }
            // Una falla de red en el último fetch pesa más que cualquier otra señal de contexto.
            if (RealtimeRepository.ultimoFetchError) d.estadoSeguimiento = SEG_ERROR_API;
        } catch (Throwable t) {
            // deja SEG_SIN_DATOS por defecto
        }
    }

    /** Longitud en hex del folio -- 8 (32 bits) ya es un riesgo de colisión no despreciable como
     *  ID de documento de Firestore a partir de decenas de miles de reportes (confirmado por
     *  simulación: ~1 colisión esperada cada 100,000 generados, por la paradoja del cumpleaños).
     *  Con 10 hex (40 bits) el mismo cálculo da colisión despreciable hasta varios cientos de
     *  miles de reportes, que es un margen razonable para el volumen esperado de esta app. */
    private static final int LARGO_HEX = 10;

    /**
     * Genera un reportId único, offline y no secuencial: "GMB-" + hex de un hash SHA-256 sobre
     * (id de instalación + hora + un nonce aleatorio). El id de instalación solo se usa aquí como
     * ENTROPÍA para el hash -- nunca se guarda como campo del reporte ni se puede recuperar de él.
     */
    public static String generarReportId(Context ctx) {
        try {
            String base = DeviceUtils.idDispositivo(ctx) + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02X", b));
                if (hex.length() >= LARGO_HEX) break;
            }
            return "GMB-" + hex.substring(0, LARGO_HEX);
        } catch (Exception e) {
            return "GMB-" + Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
        }
    }
}
