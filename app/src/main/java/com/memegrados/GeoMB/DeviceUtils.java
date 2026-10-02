package com.memegrados.GeoMB;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

// ============================================================
// CLASE    : DeviceUtils
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Genera y persiste un identificador único por INSTALACIÓN de la app (no por hardware:
// no usamos IMEI/ANDROID_ID para no requerir permisos ni exponer un dato ligado al
// dispositivo físico). Se usa como header X-Device-ID contra el asistente Gemini
// (integrations/gemini/) para repartir la cuota diaria y aislar la sesión de conversación
// de cada usuario — ver integrations/gemini/README.md.
//
// Se regenera si el usuario borra datos de la app o la reinstala: es el comportamiento
// esperado (es "otra instalación" para efectos de cuota).
// ============================================================
public final class DeviceUtils {

    private DeviceUtils() {}

    private static final String PREFS = "geomb";
    private static final String KEY_DEVICE_ID = "device_id";

    /** Devuelve el UUID de esta instalación, generándolo la primera vez que se pide. */
    public static synchronized String idDispositivo(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }
}
