package com.memegrados.GeoMB;

import android.app.Application;

import com.google.android.gms.ads.MobileAds;

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
        // Inicializa el SDK de AdMob en segundo plano (banner no invasivo, ver MainActivity).
        // Best-effort: sin red o con Play Services desactualizado, MainActivity simplemente no
        // muestra el banner (AdView.loadAd() falla en silencio, ver onAdFailedToLoad()).
        try { MobileAds.initialize(this, i -> {}); } catch (Exception ignore) {}
        // Precarga en segundo plano (hilo de baja prioridad, ver GtfsRepository) los datos que usa
        // el Planificador (lineas.json/mexibus.json/sublíneas), para que el primer "Trazar" no
        // tenga que parsearlos en frío. Cualquier error se registra sin tumbar la app (ver
        // GtfsRepository.precargar()); no hay callback porque aquí nadie espera el resultado.
        GtfsRepository.precargar(this, null);
    }
}
