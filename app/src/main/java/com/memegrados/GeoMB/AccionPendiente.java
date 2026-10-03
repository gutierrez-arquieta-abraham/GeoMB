package com.memegrados.GeoMB;

import org.json.JSONObject;

/**
 * Una acción que Gemini PROPUSO (ver {@link Asistente.Callback#onAccionPendiente}) y que
 * todavía nadie confirmó. Vive únicamente en memoria de {@link ChatAsistenteActivity} -- nunca
 * se persiste en SharedPreferences/Room/archivo: si el usuario sale de la pantalla, se pierde, lo
 * cual es correcto (no hay nada "a medias" que recuperar, ver RecorridoService.detener()).
 *
 * El {@code token} se genera LOCALMENTE (nunca lo manda el backend) y la expiración es de 10
 * minutos desde que se recibió la propuesta -- pasado ese tiempo, nunca se ejecuta.
 */
public final class AccionPendiente {

    private static final long VIGENCIA_MS = 10 * 60 * 1000L;

    public final String accion;
    public final JSONObject parametros;
    public final String resumen;
    public final String token;
    public final long expiraEnMs;

    public AccionPendiente(String accion, JSONObject parametros, String resumen) {
        this.accion = accion;
        this.parametros = parametros != null ? parametros : new JSONObject();
        this.resumen = resumen;
        this.token = java.util.UUID.randomUUID().toString();
        this.expiraEnMs = System.currentTimeMillis() + VIGENCIA_MS;
    }

    public boolean expirada() {
        return System.currentTimeMillis() > expiraEnMs;
    }
}
