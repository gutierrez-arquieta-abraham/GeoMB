package com.memegrados.GeoMB;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Catálogo de rutas MIXTAS del Metrobús: servicios que salen en una línea y
 * terminan en otra (comparten vía entre dos corredores). No vienen en el GTFS
 * del backend, así que se definen aquí como fuente única.
 *
 * Se detectan por el PAR (origen, destino) del feed, NO por el número de línea
 * (el feed suele etiquetar la mixta con una sola línea, p. ej. una unidad
 * Rojo Gómez → Dr. Gálvez viene como line="1"). Los nombres del catálogo usan
 * los strings tal como los manda el feed.
 *
 * Se usan para:
 *  - el icono de la unidad (degradado diagonal: arriba línea de salida = origen,
 *    abajo línea de término = destino), vía {@link #tramo(String, String)};
 *  - integrar la unidad en AMBAS líneas que toca (mapa y llegadas), vía
 *    {@link #tocaLinea(String, String, int)};
 *  - aparecer en "Rutas por código" y en los destinos de "Llegadas", vía
 *    {@link #comoRutas()}.
 */
public final class RutasMixtas {

    /** Un recorrido mixto entre el extremo A (línea {@link #lineaA}) y el B ({@link #lineaB}). */
    public static final class Mixta {
        public final String terminalA;
        public final int lineaA;
        public final String terminalB;
        public final int lineaB;

        Mixta(String terminalA, int lineaA, String terminalB, int lineaB) {
            this.terminalA = terminalA;
            this.lineaA = lineaA;
            this.terminalB = terminalB;
            this.lineaB = lineaB;
        }
    }

    /** Resultado de detección: línea de salida (origen) y de término (destino). */
    public static final class Tramo {
        public final int salida;
        public final int termino;
        Tramo(int salida, int termino) { this.salida = salida; this.termino = termino; }
    }

    /** Color oficial por línea (hex), para las rutas sintéticas. */
    private static final String[] COLOR = {
            "#D40D0D", "#8D1A96", "#7A9A01", "#FF9A03", "#141982", "#E44599", "#116633",
    };

    /** Catálogo (nombres tal como los manda el feed). */
    public static final List<Mixta> LISTA = new ArrayList<>();
    static {
        LISTA.add(new Mixta("Indios Verdes", 1, "Pueblo Sta Cruz Atoyac", 3));
        LISTA.add(new Mixta("Dr. Gálvez", 1, "Rojo Gómez", 2));
        LISTA.add(new Mixta("Col. Del Valle", 1, "Tepalcates", 2));
        LISTA.add(new Mixta("Etiopía L3", 3, "Tepalcates", 2));
        LISTA.add(new Mixta("París", 7, "Alameda Tacubaya", 2));
        LISTA.add(new Mixta("Glorieta Cuitláhuac", 7, "Alameda Tacubaya", 2));
    }

    /** Secuencia EXACTA de estaciones de un recorrido, con su línea por estación. */
    public static final class SeqMixta {
        public final String nombre;          // id interno (A31, L4-RutaSur…)
        public final String nombreVisible;   // texto a mostrar ("L4 Ruta Sur") o null si no se muestra
        public final boolean unaVia;         // true = sentido único (se recorre solo hacia adelante)
        public final int[] lineas;
        public final String[] estaciones;
        SeqMixta(String nombre, String nombreVisible, boolean unaVia, int[] lineas, String[] estaciones) {
            this.nombre = nombre; this.nombreVisible = nombreVisible; this.unaVia = unaVia;
            this.lineas = lineas; this.estaciones = estaciones;
        }
    }

    private static final class Seq {
        final String nombre;
        String vis = null;
        boolean uni = false;
        final List<String> est = new ArrayList<>();
        final List<Integer> ln = new ArrayList<>();
        Seq(String nombre) { this.nombre = nombre; }
        Seq visible(String v) { vis = v; return this; }
        Seq unaVia() { uni = true; return this; }
        Seq linea(int l, String... nombres) {
            for (String s : nombres) { est.add(s); ln.add(l); }
            return this;
        }
        SeqMixta build() {
            int[] li = new int[ln.size()];
            for (int i = 0; i < li.length; i++) li[i] = ln.get(i);
            return new SeqMixta(nombre, vis, uni, li, est.toArray(new String[0]));
        }
    }

    /** Recorridos mixtos como secuencia exacta de estaciones (norte→sur / oeste→este). */
    public static final List<SeqMixta> SECUENCIAS = new ArrayList<>();
    static {
        SECUENCIAS.add(new Seq("A31")
                .linea(1, "Indios Verdes", "Deportivo 18 de Marzo", "Euzkaro", "Potrero", "La Raza", "Circuito", "San Simón")
                .linea(3, "Tlatelolco", "Ricardo Flores Magón", "Guerrero", "Mina", "Hidalgo", "Juárez", "Balderas",
                        "Cuauhtémoc", "Jardín Pushkin", "Hospital General", "Dr. Márquez", "Centro Médico", "Obrero Mundial",
                        "Etiopía", "Luz Saviñón", "Eugenia", "División del Norte", "Miguel Laurent", "Pueblo Santa Cruz Atoyac")
                .build());
        SECUENCIAS.add(new Seq("C2")
                .linea(3, "Etiopía")
                .linea(2, "Doctor Vértiz", "Centro SCOP", "Álamos", "Xola", "Las Américas", "Andrés Molina", "La Viga",
                        "Coyuya", "Metro Coyuya", "Canela", "Tlacotal", "Goma", "Iztacalco", "UPIICSA", "El Rodeo",
                        "Río Tecolutla", "Río Mayo", "Rojo Gómez", "Río Frío", "Del Moral", "Leyes de Reforma", "CCH Oriente",
                        "Constitución de Apatzingán", "General Antonio de León", "Canal de San Juan", "Nicolás Bravo", "Tepalcates")
                .build());
        SECUENCIAS.add(new Seq("C3")
                .linea(1, "Colonia del Valle", "Nápoles", "Poliforum", "La Piedad", "Nuevo León")
                .linea(2, "Viaducto", "Amores", "Etiopía", "Doctor Vértiz", "Centro SCOP", "Álamos", "Xola", "Las Américas",
                        "Andrés Molina", "La Viga", "Coyuya", "Metro Coyuya", "Canela", "Tlacotal", "Goma", "Iztacalco",
                        "UPIICSA", "El Rodeo", "Río Tecolutla", "Río Mayo", "Rojo Gómez", "Río Frío", "Del Moral",
                        "Leyes de Reforma", "CCH Oriente", "Constitución de Apatzingán", "General Antonio de León",
                        "Canal de San Juan", "Nicolás Bravo", "Tepalcates")
                .build());
        SECUENCIAS.add(new Seq("C21")
                .linea(1, "Dr. Gálvez", "La Bombilla", "Altavista", "Olivo", "Francia", "José María Velasco",
                        "Teatro de los Insurgentes", "Río Churubusco", "Félix Cuevas", "Parque Hundido", "Ciudad de los Deportes",
                        "Colonia del Valle", "Nápoles", "Poliforum", "La Piedad", "Nuevo León")
                .linea(2, "Viaducto", "Amores", "Etiopía", "Doctor Vértiz", "Centro SCOP", "Álamos", "Xola", "Las Américas",
                        "Andrés Molina", "La Viga", "Coyuya", "Metro Coyuya", "Canela", "Tlacotal", "Goma", "Iztacalco",
                        "UPIICSA", "El Rodeo", "Río Tecolutla", "Río Mayo", "Rojo Gómez")
                .build());
        SECUENCIAS.add(new Seq("H72")
                .linea(2, "Tacubaya", "De la Salle")
                .linea(7, "Chapultepec", "La Diana", "El Ángel", "El Ahuehuete", "Hamburgo", "Reforma", "París", "Amajac",
                        "El Caballito", "Hidalgo", "Glorieta Violeta", "Garibaldi", "Glorieta Cuitláhuac")
                .build());

        // L4 no es una troncal lineal: se modela por sus servicios (nombres reales), y como
        // es lazo de una vía, cada servicio va por SENTIDO (estaciones distintas ida/vuelta).
        SECUENCIAS.add(new Seq("L4-RS-BSL").visible("L4 Ruta Sur").unaVia()   // Buenavista → San Lázaro
                .linea(4, "Buenavista", "Delegación Cuauhtémoc", "México Tenochtitlan", "Plaza de la República",
                        "Amajac", "Defensoría Pública", "Vocacional 5", "Mercados San Juan", "Eje Central", "El Salvador",
                        "Isabel La Católica", "Museo de la Ciudad", "Pino Suárez", "Las Cruces", "La Merced",
                        "Mercado de Sonora", "Cecilio Robelo", "Eduardo Molina", "Moctezuma", "San Lázaro")
                .build());
        SECUENCIAS.add(new Seq("L4-RS-SLB").visible("L4 Ruta Sur").unaVia()   // San Lázaro → Buenavista
                .linea(4, "San Lázaro", "Eduardo Molina", "Hospital Balbuena", "Mercado de Sonora Sur", "San Pablo",
                        "Pino Suárez Sur", "20 de Noviembre", "Isabel La Católica", "El Salvador", "Eje Central",
                        "Mercados San Juan", "Vocacional 5", "Defensoría Pública", "Amajac", "Plaza de la República",
                        "México Tenochtitlan", "Delegación Cuauhtémoc", "Buenavista")
                .build());
        SECUENCIAS.add(new Seq("L4-RN").visible("L4 Ruta Norte")
                .linea(4, "Buenavista", "Delegación Cuauhtémoc", "México Tenochtitlan", "Museo San Carlos", "Hidalgo",
                        "Bellas Artes", "Teatro Blanquita", "República de Chile", "República de Argentina",
                        "Teatro del Pueblo", "Mixcalco", "Ferrocarril de Cintura", "Morelos", "Archivo General de la Nación",
                        "San Lázaro")
                .build());
        SECUENCIAS.add(new Seq("L4-HAO").visible("L4 (Hidalgo–Alameda Oriente)")
                .linea(4, "Hidalgo", "Bellas Artes", "Teatro Blanquita", "República de Chile", "República de Argentina",
                        "Teatro del Pueblo", "Mixcalco", "Ferrocarril de Cintura", "Morelos", "Archivo General de la Nación",
                        "Pantitlán", "Calle 6", "Alameda Oriente")
                .build());
        // Ida hacia el aeropuerto: San Lázaro → Terminal 1 → Terminal 2 (T1 SÍ se incluye).
        SECUENCIAS.add(new Seq("L4-AA-ida").visible("L4 (Aeropuerto–Amajac)").unaVia()
                .linea(4, "Amajac", "Defensoría Pública", "Vocacional 5", "Mercados San Juan", "Eje Central", "El Salvador",
                        "Isabel La Católica", "Museo de la Ciudad", "Pino Suárez", "Las Cruces", "La Merced",
                        "Mercado de Sonora", "Cecilio Robelo", "Eduardo Molina", "Moctezuma", "San Lázaro",
                        "Terminal 1", "Terminal 2")
                .build());
        // Vuelta desde el aeropuerto: Terminal 2 → San Lázaro (T1 NO se incluye).
        SECUENCIAS.add(new Seq("L4-AA-vuelta").visible("L4 (Aeropuerto–Amajac)").unaVia()
                .linea(4, "Terminal 2", "San Lázaro", "Eduardo Molina", "Hospital Balbuena", "Mercado de Sonora Sur",
                        "San Pablo", "Pino Suárez Sur", "20 de Noviembre", "Isabel La Católica", "El Salvador",
                        "Eje Central", "Mercados San Juan", "Vocacional 5", "Defensoría Pública", "Amajac")
                .build());

        // L7 por sus 4 servicios (couplet norte: hacia Campo Marte va por "De los Misterios";
        // hacia el norte va por "Delegación Gustavo A. Madero"). Secuencias en sentido de viaje.
        SECUENCIAS.add(new Seq("L7-IC").visible("L7").unaVia()   // Indios Verdes → Campo Marte (sur por De Los Misterios)
                .linea(7, "Indios Verdes", "De Los Misterios", "Garrido", "Av. Talismán", "Necaxa", "Excélsior",
                        "Robles Domínguez", "Clave", "Misterios", "Mercado Beethoven", "Peralvillo", "Tres Culturas",
                        "Glorieta Cuitláhuac", "Garibaldi", "Glorieta Violeta", "Hidalgo",
                        "El Caballito", "Amajac", "París", "Reforma", "Hamburgo", "El Ahuehuete", "El Ángel", "La Diana",
                        "Chapultepec", "Gandhi", "Antropología", "Auditorio", "Campo Marte")
                .build());
        SECUENCIAS.add(new Seq("L7-CI").visible("L7").unaVia()   // Campo Marte → Indios Verdes (norte por Gustavo A. Madero)
                .linea(7, "Campo Marte", "Auditorio", "Antropología", "Gandhi", "Chapultepec", "La Diana", "El Ángel",
                        "El Ahuehuete", "Hamburgo", "Reforma", "París", "Amajac", "El Caballito", "Hidalgo",
                        "Glorieta Violeta", "Garibaldi", "Glorieta Cuitláhuac", "Tres Culturas",
                        "Peralvillo", "Mercado Beethoven", "Misterios", "Clave", "Robles Domínguez", "Excélsior",
                        "Necaxa", "Av. Talismán", "Garrido", "Gustavo A. Madero", "Indios Verdes")
                .build());
        SECUENCIAS.add(new Seq("L7-HC").visible("L7").unaVia()   // Hospital Infantil La Villa → Campo Marte
                .linea(7, "Hospital Infantil La Villa", "De Los Misterios", "Garrido", "Av. Talismán", "Necaxa",
                        "Excélsior", "Robles Domínguez", "Clave", "Misterios", "Mercado Beethoven", "Peralvillo",
                        "Tres Culturas", "Glorieta Cuitláhuac", "Garibaldi", "Glorieta Violeta",
                        "Hidalgo", "El Caballito", "Amajac", "París", "Reforma", "Hamburgo", "El Ahuehuete", "El Ángel",
                        "La Diana", "Chapultepec", "Gandhi", "Antropología", "Auditorio", "Campo Marte")
                .build());
        SECUENCIAS.add(new Seq("L7-CH").visible("L7").unaVia()   // Campo Marte → Hospital Infantil La Villa
                .linea(7, "Campo Marte", "Auditorio", "Antropología", "Gandhi", "Chapultepec", "La Diana", "El Ángel",
                        "El Ahuehuete", "Hamburgo", "Reforma", "París", "Amajac", "El Caballito", "Hidalgo",
                        "Glorieta Violeta", "Garibaldi", "Glorieta Cuitláhuac", "Tres Culturas",
                        "Peralvillo", "Mercado Beethoven", "Misterios", "Clave", "Robles Domínguez", "Excélsior",
                        "Necaxa", "Av. Talismán", "Garrido", "Gustavo A. Madero", "Hospital Infantil La Villa")
                .build());
    }

    private RutasMixtas() {}

    /**
     * Clasifica una estación de L4 por ruta, para las correspondencias de voz:
     * 0 = ambas rutas / troncal compartida (Buenavista, Delegación Cuauhtémoc,
     * México Tenochtitlan, San Lázaro) → "L4" a secas; 1 = solo Ruta Norte
     * (Museo San Carlos, Hidalgo … Archivo General); 2 = solo Ruta Sur
     * (Plaza de la República, Amajac … Moctezuma).
     */
    public static int rutaL4(String estacion) {
        if (estacion == null) return 0;
        String n = norm(estacion);
        if (n.isEmpty()) return 0;
        boolean norte = false, sur = false;
        for (SeqMixta s : SECUENCIAS) {
            if (s.nombre == null) continue;
            boolean esNorte = s.nombre.startsWith("L4-RN") || s.nombre.startsWith("L4-HAO");
            boolean esSur = s.nombre.startsWith("L4-RS") || s.nombre.startsWith("L4-AA");
            if (!esNorte && !esSur) continue;
            for (String e : s.estaciones) {
                if (coincide(n, e)) { if (esNorte) norte = true; if (esSur) sur = true; break; }
            }
        }
        if (norte && !sur) return 1;
        if (sur && !norte) return 2;
        return 0;   // ambas (o ninguna): sin sufijo de ruta
    }

    /**
     * Si el par (origen, destino) es una ruta mixta, devuelve sus líneas de
     * salida (origen) y término (destino); si no, null.
     */
    public static Tramo tramo(String origen, String destino) {
        if (origen == null || destino == null) return null;
        String o = norm(origen), d = norm(destino);
        if (o.isEmpty() || d.isEmpty()) return null;
        for (Mixta m : LISTA) {
            boolean oA = coincide(o, m.terminalA), oB = coincide(o, m.terminalB);
            boolean dA = coincide(d, m.terminalA), dB = coincide(d, m.terminalB);
            if (oA && dB) return new Tramo(m.lineaA, m.lineaB);  // origen=A, destino=B
            if (oB && dA) return new Tramo(m.lineaB, m.lineaA);  // origen=B, destino=A
        }
        return null;
    }

    /** ¿La ruta mixta (origen,destino) toca la línea {@code linea} (salida o término)? */
    public static boolean tocaLinea(String origen, String destino, int linea) {
        Tramo t = tramo(origen, destino);
        return t != null && (t.salida == linea || t.termino == linea);
    }

    /**
     * Rutas sintéticas (ida y vuelta) de cada recorrido mixto, para CADA una de
     * sus dos líneas. Así el recorrido sale en "Rutas por código" de ambas
     * líneas y sus destinos aparecen en "Llegadas".
     */
    public static List<Ruta> comoRutas() {
        List<Ruta> out = new ArrayList<>();
        int i = 0;
        for (Mixta m : LISTA) {
            i++;
            agregarParDirecciones(out, m, m.lineaA, "MIXA" + i);
            agregarParDirecciones(out, m, m.lineaB, "MIXB" + i);
        }
        return out;
    }

    private static void agregarParDirecciones(List<Ruta> out, Mixta m, int linea, String base) {
        String color = COLOR[Math.max(0, Math.min(6, linea - 1))];
        out.add(new Ruta(base + "-i", linea, m.terminalA, m.terminalB, color));
        out.add(new Ruta(base + "-v", linea, m.terminalB, m.terminalA, color));
    }

    // ---- coincidencia tolerante de nombres ----

    /** true si el nombre normalizado coincide con el terminal (igual o uno contiene al otro). */
    private static boolean coincide(String norm, String terminal) {
        String t = norm(terminal);
        if (t.isEmpty() || norm.isEmpty()) return false;
        return t.equals(norm) || t.contains(norm) || norm.contains(t);
    }

    /** minúsculas, sin acentos, sin puntuación; colapsa espacios. */
    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
