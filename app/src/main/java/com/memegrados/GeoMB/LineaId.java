package com.memegrados.GeoMB;

/**
 * Identidad estructurada de una línea: sistema + número + variante, en vez del entero plano
 * que codifica los tres a la vez (ver CLAUDE.md: Metrobús 1-7, Mexibús 10X ordinario/11X
 * ramal/12X exprés, Mexicable 20X). El entero ({@link #codigo()}) SIGUE siendo el único formato
 * de cable (JSON de assets, payload FCM, SharedPreferences) -- esta clase es solo la capa de
 * lectura/escritura de ese entero, para no repetir la aritmética (100+n, n%10, etc.) en cada
 * sitio que necesita razonar sobre sistema/variante en vez de solo comparar el número crudo.
 *
 * <p>Es un AGREGADO sobre el esquema legacy, no un reemplazo: {@link Sistemas}, {@link Linea#numero}
 * y {@link Ruta#linea} siguen existiendo y funcionando igual. En Planificador.java (el motor de
 * rutas) solo se reemplazó la ARITMÉTICA que clasifica sistema/variante (baseLinea, esperaExtra,
 * claveTerminal, noPreferido) -- cada reemplazo se verificó contra la implementación anterior con
 * una prueba dedicada antes y después del cambio. El resto de Planificador (incl. el propio A*)
 * no se tocó.</p>
 *
 * <p>Espejo del {@code LineaId} agregado hoy en el backend ({@code mexibus_afectaciones.py}):
 * misma convención sistema+número+variante, mismo cálculo de {@link #codigo()}.</p>
 */
public final class LineaId {

    public enum Sistema { METROBUS, MEXIBUS, MEXICABLE }
    public enum Variante { ORDINARIO, RAMAL, EXPRES }

    public final Sistema sistema;
    public final int numero;
    public final Variante variante;

    private LineaId(Sistema sistema, int numero, Variante variante) {
        this.sistema = sistema;
        this.numero = numero;
        this.variante = variante;
    }

    public static LineaId de(Sistema sistema, int numero) {
        return new LineaId(sistema, numero, Variante.ORDINARIO);
    }

    public static LineaId de(Sistema sistema, int numero, Variante variante) {
        return new LineaId(sistema, numero, variante);
    }

    /** Parsea el entero legacy a la estructura. Reproduce EXACTO lo que ya hace
     *  {@link Sistemas#sistemaDe(int)} (>=200 Mexicable, >=100 Mexibús, si no Metrobús) y
     *  {@link Sistemas#numeroDeLinea(int)} (número = resto % 10 para Mexibús/Mexicable). */
    public static LineaId desde(int codigo) {
        if (codigo >= 200) {
            return new LineaId(Sistema.MEXICABLE, codigo - 200, Variante.ORDINARIO);
        }
        if (codigo >= 100) {
            int resto = codigo - 100;              // 0-9 ordinario, 10-19 ramal, 20-29 exprés
            Variante v = resto >= 20 ? Variante.EXPRES : resto >= 10 ? Variante.RAMAL : Variante.ORDINARIO;
            return new LineaId(Sistema.MEXIBUS, resto % 10, v);
        }
        return new LineaId(Sistema.METROBUS, codigo, Variante.ORDINARIO);
    }

    /** Inverso exacto de {@link #desde(int)}: para cualquier código legacy real,
     *  {@code LineaId.desde(c).codigo() == c}. */
    public int codigo() {
        if (sistema == Sistema.METROBUS) return numero;
        int base = sistema == Sistema.MEXIBUS ? 100 : 200;
        if (variante == Variante.RAMAL) base += 10;
        else if (variante == Variante.EXPRES) base += 20;
        return base + numero;
    }

    /** Etiqueta corta para mostrar: EXACTAMENTE lo mismo que ya produce hoy
     *  {@code "L" + Planificador.etiquetaLineaCortaPub(codigo())} (ver su uso real en
     *  {@code CartaDesambiguacion}/{@code AfectacionesAdapter}) -- no se reusa ese método directo
     *  porque es privado en Planificador (solo expone el wrapper {@code etiquetaLineaCortaPub}),
     *  pero el resultado debe coincidir siempre (verificado en {@code LineaIdTest}). Ramal agrega
     *  "A" ("L1A"); exprés COLAPSA con su troncal ordinario (sin sufijo -- misma línea física, ver
     *  comentario real en CartaDesambiguacion: "exprés colapsa con su troncal"); Mexicable usa el
     *  mismo "L" + número que Metrobús/Mexibús (así lo hace hoy el código real: no hay sufijo de
     *  sistema en esta etiqueta corta). */
    public String etiqueta() {
        String sufijo = variante == Variante.RAMAL ? "A" : "";
        return "L" + numero + sufijo;
    }

    /** Misma línea física, ignorando la variante (ordinario/ramal/exprés siempre colapsan a
     *  ORDINARIO) -- agrupa "familias" (p. ej. para preferencias de servicio: 101/111/121 son
     *  la misma base). Reproduce exacto lo que ya hace {@code Planificador.baseLinea(int)}. */
    public LineaId base() { return new LineaId(sistema, numero, Variante.ORDINARIO); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineaId)) return false;
        LineaId l = (LineaId) o;
        return numero == l.numero && sistema == l.sistema && variante == l.variante;
    }

    @Override
    public int hashCode() {
        return codigo();
    }

    @Override
    public String toString() {
        return etiqueta();
    }
}
