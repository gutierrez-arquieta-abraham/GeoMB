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

    /** Nombre de carpeta del sistema (sin acentos/espacios), para organizar archivos por sistema
     *  (ver DescargaVoz/RecorridoService -- carpeta de voz voz/<voz>/<sistema>/<línea>/). */
    public static String carpeta(int sistema) {
        switch (sistema) {
            case MEXIBUS: return "Mexibus";
            case MEXICABLE: return "Mexicable";
            default: return "Metrobus";
        }
    }

    /** Número de línea "limpio" (1-7 Metrobús directo; Mexibús/Mexicable ordinario/ramal/exprés
     *  colapsan al mismo número por su último dígito: 101/111/121 -> 1, 104/124 -> 4, 201/202 -> 1/2).
     *  Para la carpeta de voz ("L" + este número) y cualquier otro agrupado por línea física. */
    public static int numeroDeLinea(int numeroLinea) {
        return numeroLinea >= 100 ? numeroLinea % 10 : numeroLinea;
    }
}
