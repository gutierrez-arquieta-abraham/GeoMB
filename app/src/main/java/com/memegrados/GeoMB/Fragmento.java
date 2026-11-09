package com.memegrados.GeoMB;

/**
 * Un pedazo de audio de un aviso de voz del recorrido. Antes, un aviso completo ("Aborda una unidad
 * con destino a Indios Verdes. 5 estaciones.") se sintetizaba/cacheaba como UN SOLO texto; cada
 * combinación de terminal/servicio/número de estaciones generaba una entrada de caché nueva, sin
 * tope real. Ahora un aviso puede ser una SECUENCIA de Fragmento, cada uno cacheado por separado,
 * para que el nombre de una estación o el número de paradas se reusen sin importar en qué aviso o
 * línea vuelvan a aparecer.
 *
 * <p>Tipos:
 * <ul>
 *   <li>{@link #ESTACION}: el nombre de una estación/terminal, YA listo para hablar (ver
 *       {@link Locuciones#nom}). Se cachea en una carpeta GLOBAL (no por línea): el mismo nombre
 *       vale para cualquier línea/sistema donde aparezca.</li>
 *   <li>{@link #COMUN}: un conector fijo o con pocas variantes acotadas (p. ej. "Próxima estación:",
 *       un tip de seguridad, o "5 estaciones" del catálogo 1..N) -- también en carpeta global.</li>
 *   <li>{@link #LEGACY}: el esquema de SIEMPRE (texto completo cacheado por línea+estación) -- lo
 *       siguen usando los avisos que todavía no se migraron a fragmentos finos, sin cambiar su
 *       comportamiento ni su caché en absoluto.</li>
 * </ul>
 */
final class Fragmento {

    static final int LEGACY = 0, ESTACION = 1, COMUN = 2;

    final int tipo;
    final String texto;
    /** Solo relevante para LEGACY (carpeta voz/<voz>/<sistema>/L<línea>/<estación>/, igual que
     *  siempre); ESTACION/COMUN ignoran estos dos campos -- su carpeta no depende de la línea. */
    final int linea;
    final String estacion;

    private Fragmento(int tipo, String texto, int linea, String estacion) {
        this.tipo = tipo;
        this.texto = texto;
        this.linea = linea;
        this.estacion = estacion;
    }

    static Fragmento legacy(String texto, int linea, String estacion) {
        return new Fragmento(LEGACY, texto, linea, estacion);
    }

    /** nombreHablado ya debe venir de {@link Locuciones#nom} (sin 'MXB ', sin paréntesis, respelled). */
    static Fragmento estacion(String nombreHablado) {
        return new Fragmento(ESTACION, nombreHablado, 0, null);
    }

    static Fragmento comun(String texto) {
        return new Fragmento(COMUN, texto, 0, null);
    }
}
