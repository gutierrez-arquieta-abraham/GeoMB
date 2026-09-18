package com.memegrados.GeoMB;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.google.android.gms.maps.model.LatLng;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reporte formal de irregularidad a Atención a Usuarios de Metrobús.
 *
 * Arma un correo PRELLENADO con los datos verificables (tipo, unidad, estación, línea, sentido,
 * ubicación, descripción y contacto) y lo abre en el cliente de correo del usuario: así el
 * remitente real es su propia cuenta. El usuario revisa y toca "Enviar". NO auto-envía (evita
 * falsos reportes). Adjunta la hoja membretada en PDF ("Enviado mediante GeoMB", vía {@link ReportePdf})
 * y, si hay evidencia, también el archivo (ACTION_SEND_MULTIPLE); sin adjuntos usa mailto:.
 *
 * Competencia (para canalizar bien):
 *   - Unidad y personal -> empresa operadora/concesionario (se identifica por el económico).
 *   - Estación          -> Metrobús administra directo las estaciones integradas no-CETRAM
 *                          (p. ej. Indios Verdes). Excepción: Buenavista (sus plataformas NO
 *                          están integradas a Metrobús como en Indios Verdes).
 */
// ============================================================
// CLASE    : ReporteIrregularidad
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Lógica de a QUIÉN corresponde una queja según lo reportado (para dirigir
// el reporte a la instancia correcta):
//   - Unidad y personal → empresa operadora/concesionario (por el económico).
//   - Estación → Metrobús administra directo las estaciones integradas
//     no-CETRAM (p. ej. Indios Verdes; excepción: Buenavista).
//
// Modela el reporte y su destinatario. Clase de apoyo del ReporteFragment.
// ============================================================
public final class ReporteIrregularidad {
    /** Correo oficial de Atención a Usuarios de Metrobús (destino de los reportes en producción). */
    public static final String DESTINO_OFICIAL = "atencion_usuarios@metrobus.cdmx.gob.mx";
    /** Correo de pruebas (dejar solo para depurar el módulo). */
    public static final String DESTINO_PRUEBAS = "gutierrez.arquieta.abraham@gmail.com";
    /** Destino activo de los reportes. */
    public static final String DESTINO = DESTINO_OFICIAL;

    private ReporteIrregularidad() {}

    /** Compat: reporte simple (sin tipo/descr./contacto/evidencia). */
    public static void abrir(Context ctx, int linea, String estacion, LatLng pos,
                             String unidadEco, String sentido, String correoUsuario) {
        enviar(ctx, 0L, linea, estacion, pos, unidadEco, sentido, null, null, null, null, null, null, correoUsuario, null);
    }

    /**
     * Abre el cliente de correo con el reporte prellenado.
     *
     * @param linea         1..7, o 0 si no aplica
     * @param estacion      estación/ubicación (o null)
     * @param pos           coordenadas aproximadas (o null)
     * @param unidadEco     número económico de la unidad (o null)
     * @param sentido       terminal/dirección de viaje (o null)
     * @param tipo          tipo de irregularidad (unidad/personal · estación)
     * @param descripcion   narración de lo ocurrido (o null)
     * @param nombreContacto nombre para seguimiento (o null)
     * @param telContacto   teléfono para seguimiento (o null)
     * @param correoUsuario correo del login (firma; el remitente real es la cuenta del cliente de correo)
     * @param foto          Uri de una foto de evidencia a adjuntar (o null)
     */
    public static void enviar(Context ctx, long momentoMs, int linea, String estacion, LatLng pos,
                              String unidadEco, String sentido, String tipo, String descripcion,
                              String personalNombre, String personalCargo,
                              String nombreContacto, String telContacto, String correoUsuario, Uri foto) {
        // La hora del hecho es la de apertura del módulo (no la del envío).
        Date cuando = momentoMs > 0 ? new Date(momentoMs) : new Date();
        String fecha = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(cuando);
        boolean hayUnidad = unidadEco != null && !unidadEco.trim().isEmpty();

        String asunto = "Reporte de irregularidad"
                + (linea > 0 ? " · Línea " + linea : "")
                + (hayUnidad ? " · unidad " + unidadEco : "")
                + " · " + fecha;

        StringBuilder b = new StringBuilder();
        b.append("Estimada área de Atención a Usuarios de Metrobús:\n\n");
        b.append("Por medio del presente reporto una irregularidad en el servicio, ");
        b.append("con los siguientes datos verificables:\n\n");
        b.append("• Fecha y hora: ").append(fecha).append('\n');
        if (tipo != null && !tipo.trim().isEmpty())
            b.append("• Tipo de irregularidad: ").append(tipo).append('\n');
        if (linea > 0) b.append("• Línea: ").append(linea).append('\n');
        if (sentido != null && !sentido.trim().isEmpty())
            b.append("• Sentido / dirección: hacia ").append(sentido).append('\n');
        if (hayUnidad) b.append("• Unidad (económico): ").append(unidadEco).append('\n');
        boolean hayCargo = personalCargo != null && !personalCargo.trim().isEmpty();
        boolean hayPersNom = personalNombre != null && !personalNombre.trim().isEmpty();
        if (hayCargo || hayPersNom) {
            b.append("• Personal reportado: ");
            if (hayCargo) b.append(personalCargo.trim());
            if (hayPersNom) b.append(hayCargo ? " · " : "").append("nombre: ").append(personalNombre.trim());
            b.append('\n');
        }
        if (estacion != null && !estacion.trim().isEmpty()) {
            b.append("• Estación / ubicación: ").append(estacion);
            if (pos != null) b.append(String.format(Locale.US, " (%.5f, %.5f)", pos.latitude, pos.longitude));
            b.append('\n');
        }
        b.append("• Descripción: ")
                .append(descripcion != null && !descripcion.trim().isEmpty()
                        ? descripcion.trim() : "[ describe aquí lo ocurrido ]")
                .append('\n');
        if (foto != null) b.append("• Se adjunta evidencia (foto / video / audio) que describe la irregularidad.\n");
        b.append('\n');
        b.append("Solicito la canalización a la instancia u operadora competente ");
        b.append("y el folio de seguimiento.\n\n");
        b.append("Atentamente,\n");
        if (nombreContacto != null && !nombreContacto.trim().isEmpty()) b.append(nombreContacto.trim()).append('\n');
        if (telContacto != null && !telContacto.trim().isEmpty()) b.append("Tel: ").append(telContacto.trim()).append('\n');
        if (correoUsuario != null && !correoUsuario.trim().isEmpty()) b.append(correoUsuario.trim());

        // Hoja membretada (PDF "Enviado mediante GeoMB") + evidencia como adjuntos.
        Uri pdf = ReportePdf.generar(ctx, momentoMs, linea, estacion, pos, unidadEco, tipo, descripcion,
                personalNombre, personalCargo, nombreContacto, correoUsuario);
        java.util.ArrayList<Uri> adjuntos = new java.util.ArrayList<>();
        if (pdf != null) adjuntos.add(pdf);
        if (foto != null) adjuntos.add(foto);

        // --------------------------------------------------------
        // PARTE "REBUSCADA": cómo se manda el correo con adjuntos
        // --------------------------------------------------------
        // NO auto-enviamos: abrimos el cliente de correo del usuario para que
        // el remitente real sea SU cuenta y él toque "Enviar" (evita reportes
        // falsos). El truco está en elegir el Intent correcto según cuántos
        // archivos se adjuntan:
        //
        //   · 2+ adjuntos (PDF + evidencia) -> ACTION_SEND_MULTIPLE + LISTA de Uris.
        //   · 1 adjunto                      -> ACTION_SEND + un solo Uri.
        //   · 0 adjuntos                     -> ACTION_SENDTO con "mailto:" (lo más compatible).
        //
        // Detalles de Android que no son obvios:
        //   - setType("message/rfc822"): SESGA el selector hacia apps de CORREO
        //     (no WhatsApp, etc.).
        //   - FLAG_GRANT_READ_URI_PERMISSION: deja que la app de correo LEA los
        //     adjuntos, que son archivos de OTRA app (la nuestra) — sin esto, no
        //     podría abrirlos por seguridad.
        //   - EXTRA_STREAM lleva UN Uri (ACTION_SEND) o una LISTA (SEND_MULTIPLE);
        //     por eso se ramifica según el tamaño.
        Intent i;
        if (!adjuntos.isEmpty()) {
            i = new Intent(adjuntos.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            i.setType("message/rfc822");                 // sesga a apps de correo
            if (adjuntos.size() > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, adjuntos);
            else i.putExtra(Intent.EXTRA_STREAM, adjuntos.get(0));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        }
        i.putExtra(Intent.EXTRA_EMAIL, new String[]{DESTINO});
        i.putExtra(Intent.EXTRA_SUBJECT, asunto);
        i.putExtra(Intent.EXTRA_TEXT, b.toString());
        try {
            ctx.startActivity(Intent.createChooser(i, "Reportar irregularidad")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            // Sin cliente de correo instalado: no truena.
        }
    }
}
