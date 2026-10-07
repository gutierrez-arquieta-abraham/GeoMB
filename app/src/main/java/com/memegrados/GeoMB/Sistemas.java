package com.memegrados.GeoMB;

/**
 * Clasifica un número de línea interno en su sistema de transporte (Metrobús/Mexibús/Mexicable),
 * según la numeración de CLAUDE.md: 1-7 Metrobús, 10X/11X/12X Mexibús, 20X Mexicable.
 *
 * <p>Fuente única: antes de esta clase, {@code sistemaDe(int)} estaba duplicado (con la misma
 * lógica) en {@link CartaDesambiguacion}, {@link Locuciones}, {@link RecorridoService} y, con otro
 * nombre ({@code sistema}), en {@link PlanificadorFragment}.
 */
// ============================================================
// CLASE    : Sistemas
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Clasifica un NÚMERO DE LÍNEA en su sistema de transporte (Metrobús /
// Mexibús / Mexicable) por rango numérico. Antes había cuatro copias de
// este mismo cálculo repartidas en distintos archivos; aquí se centraliza.
//
// Clase de UTILIDAD (final + constructor privado + método static).
// ============================================================
public final class Sistemas {

    public static final int METROBUS = 0;
    public static final int MEXIBUS = 1;
    public static final int MEXICABLE = 2;

    private Sistemas() {}

    /** Sistema al que pertenece una línea por su numeración interna (1-7 / 100-199 / 200+). */
    public static int sistemaDe(int numeroLinea) {
        if (numeroLinea >= 200) return MEXICABLE;
        if (numeroLinea >= 100) return MEXIBUS;
        return METROBUS;
    }
}
