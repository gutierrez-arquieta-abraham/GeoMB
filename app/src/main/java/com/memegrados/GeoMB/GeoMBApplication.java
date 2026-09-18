package com.memegrados.GeoMB;

import android.app.Application;

/** Contexto global de la app: le da a {@link RealtimeRepository} (y a cualquier otra clase sin
 *  Context propio) un Context de aplicación para registrar telemetría, e instala la captura de
 *  excepciones no manejadas al arrancar. */
public class GeoMBApplication extends Application {

    private static volatile GeoMBApplication instancia;

    public static GeoMBApplication get() { return instancia; }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        Telemetria.instalarCapturaErrores(this);
    }
}
