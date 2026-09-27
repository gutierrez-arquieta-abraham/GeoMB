package com.memegrados.GeoMB;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

/**
 * Reporte de un problema DE LA APP (mala información, proceso incompleto, sugerencia…) — a
 * diferencia de {@link ReporteIrregularidad}, que reporta el SERVICIO de Metrobús a la propia
 * dependencia. Arma un correo PRELLENADO dirigido a los dos correos de contacto/soporte del
 * desarrollador y lo abre en el cliente de correo del usuario: el remitente real es su propia
 * cuenta, y NO se auto-envía (igual criterio que ReporteIrregularidad).
 */
public final class ReporteApp {

    /** Correos de contacto/soporte del desarrollador (ambos reciben el reporte). */
    public static final String[] DESTINO = {
            "agutierreza2303@alumno.ipn.mx",
            "abraham566712@gmail.com",
    };

    private ReporteApp() {}

    /**
     * @param categoria   etiqueta legible ("Mala información" / "Proceso incompleto" / "Otro")
     * @param descripcion narración del problema (o vacía)
     * @param imagen      Uri de una captura de pantalla a adjuntar (o null)
     */
    public static void enviar(Context ctx, String categoria, String descripcion, Uri imagen) {
        String version = "?";
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi.versionName != null) version = pi.versionName;
        } catch (Exception ignore) {}

        String asunto = "Reporte de la app GeoMB"
                + (categoria != null && !categoria.trim().isEmpty() ? " · " + categoria.trim() : "");

        StringBuilder b = new StringBuilder();
        b.append("Hola,\n\n");
        b.append("Quiero reportar lo siguiente sobre la app GeoMB:\n\n");
        if (categoria != null && !categoria.trim().isEmpty())
            b.append("• Categoría: ").append(categoria.trim()).append('\n');
        b.append("• Descripción: ")
                .append(descripcion != null && !descripcion.trim().isEmpty()
                        ? descripcion.trim() : "[ describe aquí lo ocurrido ]")
                .append('\n');
        b.append("• Versión de la app: ").append(version).append('\n');
        b.append("• Android: ").append(Build.VERSION.RELEASE).append(" (").append(Build.MODEL).append(")\n");
        if (imagen != null) b.append("• Se adjunta una captura de pantalla.\n");
        b.append("\nGracias.");

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
        i.putExtra(Intent.EXTRA_TEXT, b.toString());
        try {
            ctx.startActivity(Intent.createChooser(i, asunto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            // Sin cliente de correo instalado: no truena.
        }
    }
}
