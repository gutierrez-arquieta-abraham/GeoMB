package com.memegrados.GeoMB;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Recibe notificaciones push (FCM). Dos tipos, según el campo "tipo" del data:
 *  - "afectacion": muestra la tarjeta de afectación (mismos campos que el monitoreo local).
 *  - "actualizacion": avisa que hay una nueva versión y abre la ficha de la tienda.
 * Con esto los avisos llegan como en WhatsApp (sin servicio en primer plano) cuando el
 * backend envíe el push; el envío lo hace el servidor (Railway), no el teléfono.
 */
public class MensajesService extends FirebaseMessagingService {

    private static final String CANAL = "avisos_push";
    private static final String CANAL_ACT = "actualizaciones";
    private static final int ID_ACT = 4501;
    private static final int ID_BASE = 4510;

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        crearCanales();
        Map<String, String> d = msg.getData();
        String tipo = d.get("tipo");

        if ("actualizacion".equals(tipo)) {
            notificarActualizacion(d.get("titulo"), d.get("texto"), largo(d.get("version_code")));
            return;
        }
        // Por defecto: afectación al servicio.
        notificarAfectacion(d);
    }

    @Override
    public void onNewToken(String token) {
        // El backend difunde por temas (subscribeToTopic), así que no hace falta subir el token.
    }

    /** Notificación de afectación: texto plano y limpio (tipografía del sistema) + logo de línea. */
    private void notificarAfectacion(Map<String, String> d) {
        if (!Modos.notifAfectaciones(this)) return;   // el usuario desactivó las afectaciones
        String estado = valor(d.get("estado"), getString(R.string.manifest_generico));
        String lugar = valor(d.get("lugar"), "");
        String info = valor(d.get("info"), "");
        int lineaNum = entero(d.get("linea"));
        if (lineaNum > 0 && !Modos.notifLinea(this, lineaNum)) return;   // control maestro: línea apagada

        // Dedup en el cliente: si ESTA misma afectación (línea|estado|lugar) ya se mostró, no repetir
        // (FCM puede reentregar el mismo push, o coincidir con el monitor local). El "restablecido"
        // sí se muestra y limpia las afectaciones previas de esa línea+lugar, para que si reaparece,
        // vuelva a avisar.
        String clave = lineaNum + "|" + estado.toLowerCase() + "|" + lugar.toLowerCase();
        boolean restablecido = estado.toLowerCase().contains("restablec");
        SharedPreferences prefs = getSharedPreferences("geomb", MODE_PRIVATE);
        Set<String> shown = new HashSet<>(prefs.getStringSet("push_shown", new HashSet<>()));
        if (!restablecido && shown.contains(clave)) return;   // ya se avisó: no saturar

        String lineaLabel = lineaNum > 0 ? getString(R.string.manifest_linea_fmt, String.valueOf(lineaNum)) : "";

        // TEXTO PLANO + logo de línea (sin layout personalizado).
        StringBuilder texto = new StringBuilder();
        if (!lineaLabel.isEmpty()) texto.append(lineaLabel);
        if (!lugar.isEmpty()) texto.append(texto.length() > 0 ? " · " : "").append(lugar);
        if (!info.isEmpty()) texto.append(texto.length() > 0 ? "\n" : "").append(info);

        // ID por AFECTACIÓN (línea + estaciones), no por línea: así conviven varias afectaciones
        // de la misma línea y el "Servicio restablecido" (misma línea + estaciones) reemplaza
        // exactamente la suya en vez de tapar otra que siga activa.
        final int id = ID_BASE + (((lineaNum + "|" + lugar).hashCode() & 0x7fffffff) % 100000);

        // Actualiza el registro de lo ya mostrado (síncrono, antes de la traducción asíncrona).
        if (restablecido) {
            // Al restablecerse, olvida las afectaciones de esa línea+lugar (para que si vuelve, avise).
            String pre = lineaNum + "|", suf = "|" + lugar.toLowerCase();
            for (Iterator<String> it = shown.iterator(); it.hasNext(); ) {
                String c = it.next();
                if (c.startsWith(pre) && c.endsWith(suf)) it.remove();
            }
        } else {
            if (shown.size() > 300) shown.clear();   // tope de seguridad
            shown.add(clave);
        }
        prefs.edit().putStringSet("push_shown", shown).apply();

        // Contenido dinámico del backend (español): se traduce al idioma efectivo con el motor de
        // Google (ML Kit). Para es/náhuatl/no soportado queda en español. Al terminar, notifica.
        final String cuerpoEs = texto.toString();
        final int lineaFin = lineaNum;
        Traductor.traducirTexto(this, estado, tituloT ->
                Traductor.traducirTexto(this, cuerpoEs, cuerpoT ->
                        emitirAfectacion(tituloT, cuerpoT, lineaFin, id)));
    }

    /** Arma y lanza la notificación de afectación con textos ya resueltos (traducidos o no). */
    private void emitirAfectacion(String titulo, String cuerpo, int lineaNum, int id) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(titulo)
                .setContentText(cuerpo.replace('\n', ' '))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(piApp())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (lineaNum > 0) {
            Linea l = GtfsRepository.porNumero(this, lineaNum);
            int color = l != null ? l.color : 0xFFD40D0D;
            b.setColor(color);
            Bitmap logo = Tipografia.logoLinea(this, color, String.valueOf(lineaNum));
            if (logo != null) b.setLargeIcon(logo);
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, b.build());
    }

    /** Aviso de que hay una actualización; al tocar abre la ficha en la tienda. */
    private void notificarActualizacion(String titulo, String texto, long versionRemota) {
        if (!Modos.notifActualizaciones(this)) return;   // el usuario desactivó los avisos de actualización
        // Solo avisa si el dispositivo está desactualizado (versionCode local < remoto).
        if (versionRemota > 0) {
            try {
                long propia = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                        getPackageManager().getPackageInfo(getPackageName(), 0));
                if (propia >= versionRemota) return;
            } catch (Exception ignore) {}
        }
        Intent tienda = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 2, tienda,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final String tit = valor(titulo, getString(R.string.push_actualizar_titulo));
        final String cue = valor(texto, getString(R.string.push_actualizar_texto));
        final PendingIntent piFin = pi;
        // Contenido dinámico: traduce al idioma efectivo (ML Kit) y luego notifica.
        Traductor.traducirTexto(this, tit, titT ->
                Traductor.traducirTexto(this, cue, cueT -> {
                    Notification n = new NotificationCompat.Builder(this, CANAL_ACT)
                            .setSmallIcon(R.drawable.ic_bus)
                            .setContentTitle(titT)
                            .setContentText(cueT)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(cueT))
                            .setAutoCancel(true)
                            .setContentIntent(piFin)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build();
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.notify(ID_ACT, n);
                }));
    }

    private PendingIntent piApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CANAL,
                    getString(R.string.canal_manifestaciones_avisos), NotificationManager.IMPORTANCE_DEFAULT));
            nm.createNotificationChannel(new NotificationChannel(CANAL_ACT,
                    getString(R.string.canal_actualizaciones), NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private static String valor(String s, String porDefecto) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : porDefecto;
    }

    private static int entero(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static long largo(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }
}
