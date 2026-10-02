package com.memegrados.GeoMB;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reporte de un problema DE LA APP (mala información, proceso incompleto, sugerencia…) — a
 * diferencia de {@link ReporteIrregularidad}, que reporta el SERVICIO de Metrobús a la propia
 * dependencia. Arma un correo PRELLENADO con un DIAGNÓSTICO TÉCNICO automático ({@link
 * DiagnosticoReporte}) dirigido a los dos correos de contacto/soporte del desarrollador y lo abre
 * en el cliente de correo del usuario: el remitente real es su propia cuenta, y NO se auto-envía
 * (igual criterio que ReporteIrregularidad).
 *
 * Además, el reporte queda guardado en LOCAL ({@link ReporteEntity} vía Room, funciona sin
 * conexión) y se sube de forma oportunista a Firestore ({@code reportes/{reportId}}) a través de
 * {@link TelemetriaSync} — igual patrón que el resto de la telemetría — para poder darle
 * seguimiento con el folio {@code reportId} aunque el correo nunca llegue a enviarse.
 */
public final class ReporteApp {

    /** Correos de contacto/soporte del desarrollador (ambos reciben el reporte). */
    public static final String[] DESTINO = {
            "agutierreza2303@alumno.ipn.mx",
            "abraham566712@gmail.com",
    };

    /** Ventana de correlación de errores técnicos cercanos al reporte (ver DiagnosticoReporte). */
    private static final long VENTANA_CORRELACION_MS = 5 * 60 * 1000;
    private static final int MAX_ERRORES_CERCANOS = 20;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ReporteApp() {}

    /**
     * @param categoria    etiqueta legible mostrada en el diálogo ("Mala información" / "Proceso
     *                     incompleto" / "Otro" — o su traducción); se usa tal cual en el correo.
     * @param categoriaPos posición del spinner (0=unidad/mala info, 1=proceso incompleto, 2=otro),
     *                     ESTABLE sin importar el idioma — se usa solo para la heurística de
     *                     prioridad y el registro interno, nunca se muestra.
     * @param descripcion  narración del problema (o vacía)
     * @param imagen       Uri de una captura de pantalla a adjuntar (o null)
     */
    public static void enviar(Context ctx, String categoria, int categoriaPos, String descripcion, Uri imagen) {
        Context app = ctx.getApplicationContext();
        long ts = System.currentTimeMillis();
        String reportId = DiagnosticoReporte.generarReportId(app);
        DiagnosticoReporte.Datos d = DiagnosticoReporte.recolectar(app);
        String prioridad = calcularPrioridad(categoriaPos, d);

        String asunto = "[GeoMB][" + etiquetaCorta(prioridad) + "][" + reportId + "]"
                + (categoria != null && !categoria.trim().isEmpty() ? " " + categoria.trim() : "");

        String cuerpo = construirCuerpo(reportId, ts, categoria, descripcion, d, imagen != null);

        // Mismo patrón que ReporteIrregularidad: con adjunto usa ACTION_SEND (message/rfc822 sesga
        // a apps de correo); sin adjunto, "mailto:" es lo más compatible entre clientes.
        Intent i;
        if (imagen != null) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("message/rfc822");
            i.putExtra(Intent.EXTRA_STREAM, imagen);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        }
        i.putExtra(Intent.EXTRA_EMAIL, DESTINO);
        i.putExtra(Intent.EXTRA_SUBJECT, asunto);
        i.putExtra(Intent.EXTRA_TEXT, cuerpo);
        try {
            ctx.startActivity(Intent.createChooser(i, asunto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            // Sin cliente de correo instalado: no truena -- el reporte igual queda guardado/sincronizado abajo.
        }

        guardarYSincronizar(app, reportId, ts, categoria, descripcion, prioridad, d);
    }

    /** Arma la entidad local (con errores cercanos correlacionados) y la deja lista para Firestore. */
    private static void guardarYSincronizar(Context app, String reportId, long ts, String categoria,
                                             String descripcion, String prioridad, DiagnosticoReporte.Datos d) {
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.get(app);
                ReporteEntity r = new ReporteEntity();
                r.reportId = reportId;
                r.ts = ts;
                r.estado = ReporteEntity.ESTADO_NUEVO;
                r.prioridad = prioridad;
                r.categoria = categoria;
                r.descripcion = descripcion;
                r.versionApp = d.versionApp;
                r.versionCode = d.versionCode;
                r.fabricante = d.fabricante;
                r.modelo = d.modelo;
                r.androidRelease = d.androidRelease;
                r.apiLevel = d.apiLevel;
                r.arquitectura = d.arquitectura;
                r.conectividadTipo = d.conectividadTipo;
                r.internetValidado = d.internetValidado;
                r.ultimaSincronizacionTs = d.ultimaSincronizacionTs;
                r.edadDatoMs = d.edadDatoMs;
                r.gpsActivado = d.gpsActivado;
                r.permisoUbicacion = d.permisoUbicacion;
                r.proveedorUbicacion = d.proveedorUbicacion;
                r.ubicacionTs = d.ubicacionTs;
                r.lineaContexto = d.lineaContexto;
                r.sentidoContexto = d.sentidoContexto;
                r.estacionActual = d.estacionActual;
                r.estacionSiguiente = d.estacionSiguiente;
                r.unidadContexto = d.unidadContexto;
                r.estadoSeguimiento = d.estadoSeguimiento;
                r.backendEndpoint = d.backendEndpoint;
                r.backendHttpStatus = d.backendHttpStatus;
                r.backendLatenciaMs = d.backendLatenciaMs;
                r.erroresCercanosJson = erroresCercanos(db, ts);

                db.reporteDao().insertar(r);
                TelemetriaSync.sincronizar(app);
            } catch (Exception ignore) {
                // Si Room falla por cualquier motivo, el correo ya se abrió -- el reporte no se pierde
                // para el usuario, solo queda sin registro de seguimiento local.
            }
        });
    }

    /** Resumen (SIN stack traces) de los errores técnicos ya registrados por Telemetria en una
     *  ventana de ±5 min alrededor del reporte -- ver ErrorEventoDao.enRango(). */
    private static String erroresCercanos(AppDatabase db, long ts) {
        try {
            List<ErrorEventoEntity> cercanos = db.errorEventoDao()
                    .enRango(ts - VENTANA_CORRELACION_MS, ts + VENTANA_CORRELACION_MS);
            JSONArray arr = new JSONArray();
            int n = Math.min(cercanos.size(), MAX_ERRORES_CERCANOS);
            for (int i = 0; i < n; i++) {
                ErrorEventoEntity e = cercanos.get(i);
                JSONObject o = new JSONObject();
                o.put("tipo", e.tipo);
                o.put("contexto", e.contexto != null ? e.contexto : "");
                String msg = e.mensaje != null ? e.mensaje : "";
                o.put("mensaje", msg.length() > 200 ? msg.substring(0, 200) : msg);
                o.put("ts", e.ts);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String calcularPrioridad(int categoriaPos, DiagnosticoReporte.Datos d) {
        boolean conectadoOk = Boolean.TRUE.equals(d.internetValidado);
        boolean fallaBackend = DiagnosticoReporte.SEG_ERROR_API.equals(d.estadoSeguimiento)
                || DiagnosticoReporte.SEG_UNIDAD_NO_ENCONTRADA.equals(d.estadoSeguimiento);
        if (conectadoOk && fallaBackend) return ReporteEntity.PRIORIDAD_P1_ALTA;
        boolean procesoIncompleto = categoriaPos == 1;   // ver fragment_reporte_app: 0/1/2
        if (procesoIncompleto || DiagnosticoReporte.SEG_DESACTUALIZADO.equals(d.estadoSeguimiento))
            return ReporteEntity.PRIORIDAD_P2_MEDIA;
        return ReporteEntity.PRIORIDAD_P3_BAJA;
    }

    private static String etiquetaCorta(String prioridad) {
        if (ReporteEntity.PRIORIDAD_P0_CRITICA.equals(prioridad)) return "P0";
        if (ReporteEntity.PRIORIDAD_P1_ALTA.equals(prioridad)) return "P1";
        if (ReporteEntity.PRIORIDAD_P2_MEDIA.equals(prioridad)) return "P2";
        return "P3";
    }

    private static String construirCuerpo(String reportId, long ts, String categoria, String descripcion,
                                           DiagnosticoReporte.Datos d, boolean hayImagen) {
        String fecha = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(ts));
        StringBuilder b = new StringBuilder();
        b.append("------------------------------------\n");
        b.append("GEO MB — REPORTE ").append(reportId).append('\n');
        b.append("------------------------------------\n\n");
        b.append("Categoría:\n").append(txt(categoria)).append("\n\n");
        b.append("Descripción:\n")
                .append(descripcion != null && !descripcion.trim().isEmpty()
                        ? descripcion.trim() : "[ describe aquí lo ocurrido ]")
                .append("\n\n");
        b.append("Fecha y hora:\n").append(fecha).append("\n\n");
        b.append("Aplicación:\nGeoMB ").append(txt(d.versionApp))
                .append(" (build ").append(d.versionCode).append(")\n\n");
        b.append("Dispositivo:\n").append(txt(d.fabricante)).append(' ').append(txt(d.modelo)).append("\n\n");
        b.append("Android:\n").append(txt(d.androidRelease)).append(" (API ").append(d.apiLevel).append(")\n\n");
        b.append("Conectividad:\n").append(txt(d.conectividadTipo)).append("\n\n");
        b.append("Internet:\n").append(txtBool(d.internetValidado, "OK", "SIN VALIDAR")).append("\n\n");
        b.append("Última sincronización:\n")
                .append(d.ultimaSincronizacionTs > 0
                        ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(d.ultimaSincronizacionTs))
                        : DiagnosticoReporte.NO_DISPONIBLE)
                .append("\n\n");
        b.append("Edad del dato:\n")
                .append(d.edadDatoMs >= 0 ? (d.edadDatoMs / 1000) + " s" : DiagnosticoReporte.NO_DISPONIBLE)
                .append("\n\n");
        b.append("GPS:\n").append(txtBool(d.gpsActivado, "ACTIVADO", "DESACTIVADO")).append("\n\n");
        b.append("Permiso de ubicación:\n").append(txt(d.permisoUbicacion)).append("\n\n");
        b.append("Línea:\n").append(d.lineaContexto > 0 ? "L" + d.lineaContexto : DiagnosticoReporte.NO_DISPONIBLE).append("\n\n");
        b.append("Sentido:\n").append(txt(d.sentidoContexto)).append("\n\n");
        b.append("Estación:\n").append(txt(d.estacionActual)).append("\n\n");
        b.append("Estación siguiente:\n").append(txt(d.estacionSiguiente)).append("\n\n");
        b.append("Unidad:\n").append(txt(d.unidadContexto)).append("\n\n");
        b.append("Backend:\n").append(d.backendHttpStatus > 0 ? "HTTP " + d.backendHttpStatus : DiagnosticoReporte.NO_DISPONIBLE).append("\n\n");
        b.append("Latencia:\n")
                .append(d.backendLatenciaMs >= 0 ? d.backendLatenciaMs + " ms" : DiagnosticoReporte.NO_DISPONIBLE)
                .append("\n\n");
        b.append("Estado del seguimiento:\n").append(txt(d.estadoSeguimiento)).append("\n");
        if (hayImagen) b.append("\nSe adjunta una captura de pantalla.\n");
        b.append("\n------------------------------------\n");
        b.append("Folio: ").append(reportId).append('\n');
        return b.toString();
    }

    private static String txt(String s) {
        return s != null && !s.trim().isEmpty() ? s : DiagnosticoReporte.NO_DISPONIBLE;
    }

    private static String txtBool(Boolean b, String siTxt, String noTxt) {
        if (b == null) return DiagnosticoReporte.NO_DISPONIBLE;
        return b ? siTxt : noTxt;
    }
}
