package com.memegrados.GeoMB;

// ============================================================
// CLASE    : Config
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Guarda en un solo lugar todos los "números y direcciones fijas"
// de la app: URLs del servidor, cada cuánto se pide el feed, radios
// para los avisos de llegada, duración de animaciones, etc.
//
// ¿POR QUÉ UNA CLASE DE CONFIGURACIÓN?
//   Tener las constantes juntas evita "números mágicos" regados por
//   el código: si hay que cambiar un valor, se cambia AQUÍ una vez.
//
// Todo es 'static final' (constante) y el constructor es privado:
// no se crean objetos, se usa directo como  Config.POLL_MS.
// ============================================================
/** Configuración central de la app. */
public final class Config {

    private Config() {}

    /** Backend PRINCIPAL: servidor propio en México (AWS mx-central-1). */
    public static final String BASE_URL = "https://geomb.duckdns.org";

    /** Backend de RESPALDO: si el principal no responde, la app cae aquí (Railway) sola. */
    public static final String FALLBACK_URL = "https://web-production-6a6c6.up.railway.app";

    /**
     * Panel admin de afectaciones (aviso manual): puerto y ruta dedicados, protegidos con
     * mTLS (certificado cliente) en vez de token — ver docs/SESION_2026-09_backend.md §7.
     * Solo alcanzable desde el modo personalizado (5 toques al logo en "Acerca de").
     */
    public static final String PANEL_ADMIN_URL = "https://geomb.duckdns.org:8443/admin/afectacion";

    /** Rutas relativas que sirve el backend (se usan con failover, ver Backend). */
    public static final String PATH_VEHICLES = "/data/vehicles.json";
    public static final String PATH_ROUTES = "/data/routes.json";
    /** Estado actual de afectaciones de Mexibús (lo escribe el módulo mexibus_afectaciones.py). */
    public static final String PATH_AFECT_MXB = "/data/afectaciones_mexibus.json";

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

    /**
     * Banner de AdMob (no invasivo, fijo abajo en todas las pantallas): ayuda a cubrir el costo
     * de rss.app (feeds de afectaciones de Mexibús/Mexicable). OJO: este es el ID de anuncio DE
     * PRUEBA que publica Google (no es un secreto) -- hay que cambiarlo por el Ad Unit ID real de
     * la cuenta de AdMob antes de publicar (junto con el App ID en AndroidManifest.xml), o Google
     * puede suspender la cuenta por servir anuncios de prueba en producción.
     */
    public static final String AD_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111";
}
