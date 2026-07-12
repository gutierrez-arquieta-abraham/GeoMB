package com.memegrados.GeoMB;

/** Configuración central de la app. */
public final class Config {

    private Config() {}

    /** Backend en Railway que sirve el feed GTFS-RT convertido a JSON. */
    public static final String BASE_URL = "https://web-production-6a6c6.up.railway.app";

    /** Posiciones de las unidades en tiempo real. */
    public static final String VEHICLES_URL = BASE_URL + "/data/vehicles.json";

    /** Cada cuánto se refrescan las posiciones (ms). */
    public static final long POLL_MS = 25000;
}
