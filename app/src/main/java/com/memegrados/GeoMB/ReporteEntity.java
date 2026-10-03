package com.memegrados.GeoMB;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Un reporte técnico de un problema DE LA APP (ver {@link ReporteApp}), con el diagnóstico
 * automático de {@link DiagnosticoReporte} ya adjunto. Vive primero en LOCAL (Room) -- funciona
 * sin conexión -- y {@link TelemetriaSync} lo sube a Firestore ({@code reportes/{reportId}}) de
 * forma oportunista, igual que el resto de la telemetría.
 *
 * {@code reportId} se genera una sola vez, offline, en {@link DiagnosticoReporte#generarReportId}
 * y se usa tal cual como PRIMARY KEY local y como ID del documento en Firestore: así un reintento
 * de sincronización nunca crea un reporte duplicado (sobreescribe el mismo documento).
 */
@Entity(tableName = "reportes_app")
public class ReporteEntity {

    @PrimaryKey
    @NonNull
    public String reportId = "";

    // -- identificación --
    public long ts;
    public String estado;
    public String prioridad;
    public String categoria;
    public String descripcion;
    public String versionApp;
    public long versionCode;

    // -- dispositivo --
    public String fabricante;
    public String modelo;
    public String androidRelease;
    public int apiLevel;
    public String arquitectura;

    // -- conectividad --
    public String conectividadTipo;
    public Boolean internetValidado;
    public long ultimaSincronizacionTs;
    public long edadDatoMs = -1;

    // -- GPS / ubicación (NUNCA lat/lon: solo antigüedad del último fix) --
    public Boolean gpsActivado;
    public String permisoUbicacion;
    public String proveedorUbicacion;
    public long ubicacionTs;

    // -- contexto de transporte --
    public int lineaContexto;
    public String sentidoContexto;
    public String estacionActual;
    public String estacionSiguiente;
    public String unidadContexto;
    public String estadoSeguimiento;

    // -- backend --
    public String backendEndpoint;
    public int backendHttpStatus;
    public long backendLatenciaMs = -1;

    // -- correlación de errores cercanos (±5 min), resumen sin stack traces --
    public String erroresCercanosJson = "[]";

    // -- cola local / sincronización (NUNCA se suben a Firestore) --
    public boolean sincronizado;
    public int intentosSync;
    public long ultimoIntentoSyncTs;

    // ---- estados ----
    public static final String ESTADO_NUEVO = "NUEVO";
    public static final String ESTADO_EN_ANALISIS = "EN_ANALISIS";
    public static final String ESTADO_REPRODUCIDO = "REPRODUCIDO";
    public static final String ESTADO_EN_DESARROLLO = "EN_DESARROLLO";
    public static final String ESTADO_EN_PRUEBAS = "EN_PRUEBAS";
    public static final String ESTADO_CORREGIDO = "CORREGIDO";
    public static final String ESTADO_PUBLICADO = "PUBLICADO";
    public static final String ESTADO_CERRADO = "CERRADO";

    // ---- prioridades ----
    public static final String PRIORIDAD_P0_CRITICA = "P0_CRITICA";
    public static final String PRIORIDAD_P1_ALTA = "P1_ALTA";
    public static final String PRIORIDAD_P2_MEDIA = "P2_MEDIA";
    public static final String PRIORIDAD_P3_BAJA = "P3_BAJA";
}
