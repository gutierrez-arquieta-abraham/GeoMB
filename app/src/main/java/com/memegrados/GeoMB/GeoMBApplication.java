package com.memegrados.GeoMB;

import android.app.Application;

import com.google.firebase.messaging.FirebaseMessaging;

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
        // Alertas de proximidad de unidades guardadas: pide el token FCM actual (cubre el caso
        // en que onNewToken() no se dispare en esta ejecución, p. ej. el token ya existía de
        // antes) y re-declara al backend el estado completo de alertas activas -- ver
        // AlertasBackend.resincronizarTodo(). Idempotente y asíncrono: no bloquea el arranque ni
        // se le muestra nada al usuario si falla (solo queda registrado en Telemetria).
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> AlertasBackend.registrarToken(this, token));
        AlertasBackend.resincronizarTodo(this);
        // El SDK de AdMob (MobileAds.initialize()) NO se inicializa aquí: primero hay que pedir
        // consentimiento (GDPR/UK/US, vía UMP) desde una Activity -- ver MainActivity.gestionarConsentimientoAds().
        // Precarga en segundo plano (hilo de baja prioridad, ver GtfsRepository) los datos que usa
        // el Planificador (lineas.json/mexibus.json/sublíneas), para que el primer "Trazar" no
        // tenga que parsearlos en frío. Cualquier error se registra sin tumbar la app (ver
        // GtfsRepository.precargar()); no hay callback porque aquí nadie espera el resultado.
        GtfsRepository.precargar(this, null);
    }
}
