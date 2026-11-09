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

    /** Único backend: servidor propio en México (AWS mx-central-1). El respaldo en Railway que
     *  existía antes se dio de baja (ver Backend). */
    public static final String BASE_URL = "https://geomb.duckdns.org";

    /**
     * Panel admin de afectaciones (aviso manual): puerto y ruta dedicados, protegidos con
     * mTLS (certificado cliente) en vez de token — ver docs/SESION_2026-09_backend.md §7.
     * Solo alcanzable desde el modo personalizado (5 toques al logo en "Acerca de").
     */
    public static final String PANEL_ADMIN_URL = "https://geomb.duckdns.org:8443/admin/afectacion";

    /**
     * Interruptor remoto de "Servicio en pausa" (ver {@link EstadoServicio}). Vive en GitHub y no
     * en el EC2 porque tiene que responder justo cuando el servidor está apagado. Se edita
     * {@code estado_servicio.json} en la rama master del repo GeoMB.
     */
    public static final String ESTADO_SERVICIO_URL =
            "https://raw.githubusercontent.com/gutierrez-arquieta-abraham/GeoMB/master/estado_servicio.json";

    /** Rutas relativas que sirve el backend (ver Backend). */
    public static final String PATH_VEHICLES = "/data/vehicles.json";
    public static final String PATH_ROUTES = "/data/routes.json";
    /** Entrega la URL firmada (S3) del feed en vivo para que la app lo baje directo -- ver
     *  UnidadRtEdge. Nombre de ruta a propósito neutro (edge_rt_backend.py, metrobus_app). */
    public static final String PATH_SYNC = "/api/sync";
    /** Estado actual de afectaciones de Mexibús (lo escribe el módulo mexibus_afectaciones.py). */
    public static final String PATH_AFECT_MXB = "/data/afectaciones_mexibus.json";
    /** Parches de ruta TEMPORALES vigentes (desvíos/servicios de apoyo cargados a mano desde el
     *  panel /admin/trazos del backend) -- ver RutasTemporales. */
    public static final String PATH_RUTAS_TEMP = "/data/rutas_temporales.json";
    /** versionCode mínimo vigente (fijado a mano en /admin/version del backend) -- ver
     *  ActualizacionObligatoria. Exento del gate de versión: siempre responde 200. */
    public static final String PATH_VERSION = "/data/version.json";

    /**
     * Asistente conversacional (Gemini + Function Calling): backend Python aparte
     * ({@code integrations/gemini/} en este mismo repo), detrás de Nginx en el mismo EC2 y
     * dominio que {@link #BASE_URL} — ver integrations/gemini/README.md §Despliegue.
     */
    public static final String ASISTENTE_CHAT_URL = BASE_URL + "/gemini/chat";

    /**
     * Alertas de proximidad de unidades guardadas: endpoints de {@code metrobus_app} (repo
     * aparte, mismo EC2 y dominio que {@link #BASE_URL}), NO del asistente Gemini. Device-only
     * (identificados por X-Device-ID, ver DeviceUtils) — nunca uid de Firebase Auth.
     */
    public static final String DEVICE_TOKEN_URL = BASE_URL + "/device/token";
    public static final String DEVICE_ALERTA_URL = BASE_URL + "/device/alerta";
    public static final String DEVICE_UBICACION_URL = BASE_URL + "/device/ubicacion";

    /**
     * Política de privacidad pública y eliminación de cuenta (cumplimiento de Google Play) —
     * endpoints de {@code metrobus_app} (repo aparte), mismo EC2 y dominio que {@link #BASE_URL}.
     */
    public static final String PRIVACY_URL = BASE_URL + "/privacy";
    public static final String DELETE_ACCOUNT_URL = BASE_URL + "/delete-account";
    public static final String ACCOUNT_DELETE_API_URL = BASE_URL + "/api/account/delete";

    /** Donativo voluntario (PayPal.me del desarrollador) para los costos del servidor -- NO es un
     *  endpoint propio, es un enlace externo que se abre tal cual en el navegador/app de PayPal. */
    public static final String DONACION_URL = "https://paypal.me/AbrahamGeoMB?locale.x=es_XC&country.x=MX";

    /** Cada cuánto manda AlertasUnidadesService la ubicación actual mientras haya al menos una
     *  alerta activa (ms). No tiene sentido más frecuente que el ciclo de push_metrobus.py (60 s,
     *  ver metrobus_app) -- nadie va a leer una ubicación más fresca que eso. */
    public static final long ALERTA_UBICACION_POLL_MS = 60000;

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
     * de rss.app (feeds de afectaciones de Mexibús/Mexicable). Ad Unit ID real de la cuenta de
     * AdMob (GeoMB, Android, unidad tipo banner). DEBE coincidir con
     * res/values/strings.xml#ad_banner_unit_id (el AdView lo necesita como recurso XML, ver
     * activity_main.xml) -- si se cambia aquí, cambiar también allá.
     */
    public static final String AD_BANNER_UNIT_ID = "ca-app-pub-9067864586341510/7340023212";
}
