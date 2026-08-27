package com.memegrados.GeoMB;

/** Configuración central de la app. */
public final class Config {

    private Config() {}

    /** Backend PRINCIPAL: servidor propio en México (AWS mx-central-1). */
    public static final String BASE_URL = "https://geomb.duckdns.org";

    /** Backend de RESPALDO: si el principal no responde, la app cae aquí (Railway) sola. */
    public static final String FALLBACK_URL = "https://web-production-6a6c6.up.railway.app";

    /** Rutas relativas que sirve el backend (se usan con failover, ver Backend). */
    public static final String PATH_VEHICLES = "/data/vehicles.json";
    public static final String PATH_ROUTES = "/data/routes.json";

    /**
     * Catálogo colaborativo de marca/modelo por económico.
     * Apunta directo al Google Sheet publicado como CSV (colaborativo y en vivo):
     * al editar la hoja, la app se actualiza en el siguiente arranque.
     * Acepta columnas: economico,marca,modelo  ó  economico,empresa,marca,modelo.
     */
    public static final String MODELOS_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vSzbtEUq4-cocjqJOydQZj5HnWLmD4_oURYbXzNLu2wxvSGZxkUMq3QQ-rwVb2_5KB1GyYLs0ddpydR/pub?gid=0&single=true&output=csv";


    /** Cada cuánto se pide el feed al backend (ms) — movimiento de las unidades. */
    public static final long POLL_MS = 10000;

    /** Velocidad promedio de referencia para estimar llegadas (m/s ≈ 18 km/h). */
    public static final float LLEGADA_VEL_MS = 5.0f;

    /** Umbral para avisar que una unidad está por llegar a una parada (m). */
    public static final float LLEGADA_AVISO_M = 900f;

    /** Cada cuánto revisa el servicio de avisos de llegada (ms). */
    public static final long LLEGADA_POLL_MS = 10000;

    /** Duración de la animación al deslizar una unidad a su nueva posición (ms). */
    public static final long ANIM_MS = 1200;

    /** Primer aviso "ya viene" y tope de la barra de progreso (m). */
    public static final float SEGUIR_LEJOS_M = 5000f;

    /** Segundo aviso "está por llegar" (m). */
    public static final float SEGUIR_CERCA_M = 800f;

    /** Umbrales para re-armar cada aviso al alejarse (m). */
    public static final float SEGUIR_REARME_LEJOS_M = 5500f;
    public static final float SEGUIR_REARME_CERCA_M = 1200f;

    /** Cada cuánto revisa el servicio de seguimiento (ms). */
    public static final long SEGUIR_POLL_MS = 10000;
}
