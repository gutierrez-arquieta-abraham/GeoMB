package com.memegrados.GeoMB;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Ruta óptima (menor tiempo estimado) sobre una red de RUTAS: las 7 líneas físicas
 * y los recorridos mixtos (A31, C2, C3, C21, H72), cada uno con su secuencia exacta
 * de estaciones. Viajar en una ruta no cuesta transbordo; cambiar de ruta sí.
 */
public final class Planificador {

    private static final double SEG_PARADA = 100.0;
    private static final double SEG_TRANSBORDO = 300.0;
    private static final double VEL_MS = 6.0;      // velocidad media del BRT (~21.6 km/h) para el costo por distancia
    private static final double DWELL_S = 20.0;    // parada en estación (subir/bajar)

    /** Costo (s) de viajar un tramo entre 2 estaciones POR DISTANCIA real (kilometraje) + parada. */
    private static double costoTramo(LatLng a, LatLng b) {
        return Linea.distancia(a, b) / VEL_MS + DWELL_S;
    }

    /**
     * Espera extra (segundos) al abordar una línea, por su frecuencia. El Metrobús pasa seguido
     * (0 extra); las troncales del Mexibús algo menos, y los RAMALES (1A/2A/3A) bastante menos
     * seguido, así que abordarlos "tarda más en mandar una unidad" (p. ej. L2A > L4).
     */
    private static double esperaExtra(int linea) {
        if (linea >= 200) return 60.0;                    // Mexicable (cablebús): pasa muy seguido
        if (linea >= 121 && linea <= 129) return 240.0;   // exprés Mexibús (menos frecuente, pero salta paradas)
        if (linea >= 111) return 420.0;   // ramales Mexibús (baja frecuencia): +7 min aprox
        if (linea >= 100) return 120.0;   // troncales Mexibús: +2 min aprox
        return 0.0;                        // Metrobús: sin penalización
    }
    private static final double RADIO_CORRESP = 800.0;   // transbordo por CERCANÍA: hasta 800 m se camina

    /**
     * Penalización extra (s) al ABORDAR cierta línea en cierta estación cuando el acceso real es más
     * complicado de lo que sugiere la distancia. En Indios Verdes es bastante más fácil llegar a la L1
     * del Metrobús que a la L7 (estela elevada), así que se penaliza abordar la L7 ahí.
     */
    private static double penalConexion(Stop s) {
        if (s.linea == 7 && s.nn.contains("indios verdes")) return 180.0;
        return 0.0;
    }

    /** Tiempo de caminata (s) para un transbordo a pie de {@code metros} (~1.4 m/s). */
    private static double caminata(double metros) {
        return metros / 1.4;
    }

    /** Nombre para MOSTRAR: quita el prefijo interno "MXB "/"MXC " (Mexibús/Mexicable). */
    public static String sinMxb(String nombre) {
        if (nombre == null) return null;
        String s = (nombre.startsWith("MXB ") || nombre.startsWith("MXC ")) ? nombre.substring(4) : nombre;
        int p = s.indexOf(" (");   // quita el paréntesis "(conexión Metrobús L…)": es solo informativo y se ve mal en el planificador
        return p > 0 ? s.substring(0, p) : s;
    }

    // Nombres (normalizados, sin MXB) que existen en MÁS DE UNA línea → se muestran con su número.
    private static java.util.Set<String> nombresAmbiguos;
    private static boolean ambiguosMxb;
    /** Línea "base" (familia): agrupa ordinario/ramal/exprés (101/111/121→101); Mexicable 201/202 aparte. */
    private static int baseLinea(int n) {
        if (n >= 200) return 200 + (n % 10);
        return n >= 100 ? 100 + (n % 10) : n;
    }

    private static synchronized java.util.Set<String> ambiguos(Context ctx) {
        boolean mxb = Modos.mostrarMexibus(ctx);
        if (nombresAmbiguos == null || ambiguosMxb != mxb) {
            java.util.Map<String, Integer> primera = new java.util.HashMap<>();
            java.util.Set<String> amb = new java.util.HashSet<>();
            for (Linea l : GtfsRepository.getRuteables(ctx))
                for (Estacion e : l.estaciones) {
                    String k = norm(sinMxb(e.nombre));
                    int base = baseLinea(l.numero);   // ordinario/ramal/exprés de una misma línea NO desambiguan entre sí
                    Integer ln = primera.get(k);
                    if (ln == null) primera.put(k, base);
                    else if (ln != base) amb.add(k);
                }
            nombresAmbiguos = amb; ambiguosMxb = mxb;
        }
        return nombresAmbiguos;
    }

    /** Versión pública de {@link #etiquetaLineaCorta(int)} para la UI de desambiguación. */
    public static String etiquetaLineaCortaPub(int n) { return etiquetaLineaCorta(n); }

    /** Etiqueta corta de línea para desambiguar: Metrobús "1".."7"; Mexibús "1".."4", "1A/2A/3A". */
    private static String etiquetaLineaCorta(int n) {
        if (n == 111) return "1A";
        if (n == 112) return "2A";
        if (n == 113) return "3A";
        if (n >= 121 && n <= 124) return String.valueOf(n - 120);   // exprés: número de su línea
        if (n >= 200) return String.valueOf(n - 200);               // Mexicable: 201→"1", 202→"2"
        return n >= 100 ? String.valueOf(n - 100) : String.valueOf(n);
    }

    /**
     * Nombre para mostrar (sin "MXB "); si ese nombre existe en varias líneas, añade su número
     * para distinguirlo (p. ej. "1o de Mayo 2" = el 1 de Mayo del Mexibús Línea 2).
     */
    public static String nombreMostrar(Context ctx, String nombre, int linea) {
        String clean = sinMxb(nombre);
        // El Metrobús no tiene estaciones homónimas reales dentro de su sistema: su nombre va siempre
        // limpio (p. ej. "Indios Verdes", no "Indios Verdes 1"). El sufijo solo distingue Mexibús/Mexicable.
        if (linea > 0 && linea < 100) return clean;
        return ambiguos(ctx).contains(norm(clean)) ? clean + " " + etiquetaLineaCorta(linea) : clean;
    }
    /**
     * Correspondencias MANUALES: transbordos reales que quedan a MÁS de {@link #RADIO_CORRESP}
     * (800 m) y aun así se caminan. Se declaran por (línea, nombre normalizado) en pares.
     */
    private static final String[][] CORRESP_MANUAL = {
            {"7", "paris",                 "1", "reforma"},
            {"4", "delegacion cuauhtemoc", "1", "el chopo"},
            {"1", "revolucion",            "4", "mexico tenochtitlan"},
            {"101", "mxb 1 de mayo",       "102", "mxb las americas"},   // transbordo real Mexibús L1 (1° de Mayo) – L2 (Las Américas), 288 m
            {"101", "mxb 1 de mayo",       "112", "mxb las americas"},   // transbordo real Mexibús L1 (1° de Mayo) – L2A (Las Américas)
    };

    /** ¿El par (línea, nombre) forma una correspondencia manual declarada (en cualquier orden)? */
    private static boolean corrManual(int la, String na, int lb, String nb) {
        for (String[] p : CORRESP_MANUAL) {
            int l1 = Integer.parseInt(p[0]), l2 = Integer.parseInt(p[2]);
            if ((la == l1 && lb == l2 && na.equals(p[1]) && nb.equals(p[3]))
                    || (la == l2 && lb == l1 && na.equals(p[3]) && nb.equals(p[1]))) return true;
        }
        return false;
    }

    private Planificador() {}

    public static final class Paso {
        public final int linea, color;
        public final String origen, destino;
        public final int paradas;
        public final List<LatLng> puntos;
        public final boolean mixta;
        Paso(int linea, int color, String origen, String destino, int paradas, List<LatLng> puntos, boolean mixta) {
            this.linea = linea; this.color = color; this.origen = origen; this.destino = destino;
            this.paradas = paradas; this.puntos = puntos; this.mixta = mixta;
        }
    }

    public static final class Instruccion {
        public final String terminal;
        public final String ruta;    // nombre del servicio a mostrar ("L4 Ruta Sur") o null
        public final int linea, color, paradas;
        public final boolean transbordoAntes;
        Instruccion(String terminal, String ruta, int linea, int color, int paradas, boolean transbordoAntes) {
            this.terminal = terminal; this.ruta = ruta; this.linea = linea; this.color = color;
            this.paradas = paradas; this.transbordoAntes = transbordoAntes;
        }
    }

    public static final class Parada {
        public final String nombre;
        public final int linea, color;
        public final boolean transbordo;
        public final LatLng pos;
        public final String icono;
        Parada(String nombre, int linea, int color, boolean transbordo, LatLng pos, String icono) {
            this.nombre = nombre; this.linea = linea; this.color = color;
            this.transbordo = transbordo; this.pos = pos; this.icono = icono;
        }
    }

    public static final class Ruta {
        public final List<Paso> pasos;
        public final List<Instruccion> instrucciones;
        public final int paradas, transbordos, minutos;
        public final List<LatLng> trazo;
        public final List<Parada> secuencia;
        Ruta(List<Paso> pasos, List<Instruccion> instrucciones, int paradas, int transbordos,
             int minutos, List<LatLng> trazo, List<Parada> secuencia) {
            this.pasos = pasos; this.instrucciones = instrucciones; this.paradas = paradas;
            this.transbordos = transbordos; this.minutos = minutos; this.trazo = trazo; this.secuencia = secuencia;
        }
    }

    // ---- red interna ----

    private static final class Stop {
        final String nombre, nn, icono;
        final LatLng pos;
        final int linea, color;
        Stop(String nombre, String nn, String icono, LatLng pos, int linea, int color) {
            this.nombre = nombre; this.nn = nn; this.icono = icono; this.pos = pos;
            this.linea = linea; this.color = color;
        }
    }

    private static final class Route {
        final String id;
        final String nombreVisible;   // servicio a mostrar (o null)
        final boolean mixta;
        final boolean unaVia;         // solo se recorre hacia adelante (sentido único)
        final Linea linea;            // línea física (null si es un servicio por secuencia)
        final int color;
        final List<Stop> stops = new ArrayList<>();
        Route(String id, String nombreVisible, boolean mixta, boolean unaVia, Linea linea, int color) {
            this.id = id; this.nombreVisible = nombreVisible; this.mixta = mixta;
            this.unaVia = unaVia; this.linea = linea; this.color = color;
        }
    }

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Sinónimos de estaciones: normaliza lo que escribe el usuario al nombre canónico (el de
     * los datos). La estación se llama "Instituto Politécnico Nacional", así que "IPN" y sus
     * variantes deben redirigir a ese nombre (si no, por cercanía caía en "Mina").
     */
    private static String alias(String q) {
        if (q.equals("ipn") || q.equals("i p n") || q.equals("poli") || q.equals("politecnico")
                || q.contains("politecnico") || q.contains("instituto politecnico"))
            return "instituto politecnico nacional";
        // CEDA = Central de Abastos (varias líneas: L1/L4). El usuario puede teclear su abreviatura.
        if (q.equals("ceda") || q.equals("c e d a")) return "central de abastos";
        return q;
    }

    public static String estacionParecida(Context ctx, String texto) {
        String q = alias(norm(texto));
        if (q.length() < 2) return null;
        String mejor = null;
        int mejorPunt = Integer.MIN_VALUE;
        for (Linea l : GtfsRepository.getRuteables(ctx)) {
            for (Estacion e : l.estaciones) {
                if (e.soloMapa) continue;   // 2º andén a ras: solo mapa, no rutea
                int p = puntaje(norm(e.nombre), q);
                if (p > mejorPunt) { mejorPunt = p; mejor = e.nombre; }
            }
        }
        return mejorPunt >= 100 ? mejor : null;
    }

    /** Un andén candidato: nombre canónico + su línea + posición, para desambiguar estaciones homónimas. */
    public static final class Match {
        public final String nombre; public final int linea; public final LatLng pos;
        Match(String n, int l, LatLng p) { nombre = n; linea = l; pos = p; }
    }

    /**
     * Andenes (nombre canónico + línea) cuyo nombre coincide EXACTAMENTE con la mejor estación para
     * el texto tecleado. Sirve para decidir si hay ambigüedad: mismo nombre en varias líneas
     * (Las Américas L1/L2) o en varios sistemas (Buenavista Metrobús/Mexibús). Una entrada por línea.
     */
    public static java.util.List<Match> candidatos(Context ctx, String texto) {
        java.util.List<Match> res = new java.util.ArrayList<>();
        String canon = estacionParecida(ctx, texto);
        if (canon == null) return res;
        String cn = norm(sinMxb(canon));   // nombre LIMPIO: agrupa homónimas entre sistemas aunque el nombre completo difiera (p. ej. "Indios Verdes" Metrobús ↔ Mexibús)
        java.util.Set<Integer> vistas = new java.util.HashSet<>();
        for (Linea l : GtfsRepository.getRuteables(ctx)) {
            if (vistas.contains(l.numero)) continue;
            for (Estacion e : l.estaciones) {
                if (e.soloMapa) continue;
                if (norm(sinMxb(e.nombre)).equals(cn)) { res.add(new Match(e.nombre, l.numero, e.posicion)); vistas.add(l.numero); break; }
            }
        }
        return res;
    }

    /** ¿La estación (por nombre limpio) existe en la línea {@code numero}? (para ofrecer Ordinario/Express). */
    public static boolean estacionEnLinea(Context ctx, String nombre, int numero) {
        Linea l = GtfsRepository.porNumero(ctx, numero);
        if (l == null) return false;
        String cn = norm(sinMxb(nombre));
        for (Estacion e : l.estaciones)
            if (!e.soloMapa && norm(sinMxb(e.nombre)).equals(cn)) return true;
        return false;
    }

    private static int puntaje(String nn, String q) {
        if (nn.equals(q)) return 1000;
        if (nn.startsWith(q)) return 800 - (nn.length() - q.length());
        if (q.length() >= 3 && nn.contains(q)) return 600 - (nn.length() - q.length());
        return 300 - edicion(nn, q) * 50;
    }

    private static int edicion(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + costo);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    public static Estacion masCercana(Context ctx, LatLng p) {
        Estacion mejor = null;
        double best = Double.MAX_VALUE;
        for (Linea l : GtfsRepository.getRuteables(ctx)) {
            for (Estacion e : l.estaciones) {
                if (e.soloMapa) continue;   // 2º andén a ras: solo mapa
                double d = Linea.distancia(e.posicion, p);
                if (d < best) { best = d; mejor = e; }
            }
        }
        return mejor;
    }

    /**
     * Estación de una línea que mejor coincide con {@code name}. Si la estación tiene 2 andenes
     * (couplet unidireccional, p. ej. L4/L7: cada andén es un sentido y OTRO cobro), se elige el
     * andén del sentido en curso = el más cercano a la parada previa ({@code ref}); así cada
     * dirección usa su propio andén, sin fusionarlos.
     */
    private static Estacion enLinea(Context ctx, int linea, String name, LatLng ref) {
        Linea l = GtfsRepository.porNumero(ctx, linea);
        if (l == null) return null;
        String q = norm(name);
        Estacion mejor = null;
        int mejorPunt = Integer.MIN_VALUE;
        List<Estacion> exactas = new ArrayList<>();   // andenes con nombre idéntico (couplet)
        for (Estacion e : l.estaciones) {
            if (e.soloMapa) continue;   // 2º andén a ras (L1): solo mapa, no rutea
            String nn = norm(e.nombre);
            int p = puntaje(nn, q);
            if (p > mejorPunt) { mejorPunt = p; mejor = e; }
            if (nn.equals(q)) exactas.add(e);
        }
        if (exactas.size() >= 2 && ref != null) {
            Estacion cercano = exactas.get(0);
            double best = Double.MAX_VALUE;
            for (Estacion e : exactas) {
                double d = Linea.distancia(e.posicion, ref);
                if (d < best) { best = d; cercano = e; }
            }
            return cercano;
        }
        return mejorPunt >= 100 ? mejor : null;
    }

    // Couplets de una vía (estaciones servidas solo en un sentido), por norm de nombre.
    // Poniente: hacia Tacubaya se sirve Parque Lira; hacia Tepalcates, Antonio Maceo.
    private static final Set<String> L2_IDA = set("rio frio", "general antonio de leon", "parque lira", "coyuya");   // fuera hacia Tepalcates (Coyuya es de una vía, hacia Tacubaya)
    private static final Set<String> L2_VUELTA = set("del moral", "canal de san juan", "nicolas bravo", "antonio maceo"); // fuera hacia Tacubaya
    private static final Set<String> L6_IDA = set("de los misterios", "hospital infantil la villa",
            "416 poniente", "deportivo los galeana", "ampliacion providencia", "volcan de fuego");    // fuera hacia Villa de Aragón
    private static final Set<String> L6_VUELTA = set("482", "414", "416 oriente");                    // fuera hacia El Rosario
    private static final Set<String> L7_IDA = set("hospital infantil la villa");   // Indios→sur va por De los Misterios

    private static Set<String> set(String... xs) {
        return new java.util.HashSet<>(java.util.Arrays.asList(xs));
    }

    /**
     * Estaciones de DOS PLATAFORMAS (una por sentido): para tomar el sentido contrario hay que
     * SALIR y pagar otro pasaje, así que la "vuelta" (ida↔vuelta de la misma línea) NO se ofrece
     * ahí. En las demás (una sola plataforma, bidireccionales) la vuelta es GRATIS: tomas el otro
     * sentido sin salir. L4 y L7 no aplican (estelas: el cobro va dentro de la unidad).
     * Ampliar/ajustar con pruebas. L3 no lleva lista: todas sus estaciones son bidireccionales.
     */
    private static final Map<Integer, Set<String>> DOS_PLATAFORMAS = new HashMap<>();
    static {
        DOS_PLATAFORMAS.put(1, set("indios verdes", "deportivo 18 de marzo", "euzkaro", "potrero", "la raza",
                "insurgentes"));
        DOS_PLATAFORMAS.put(2, set("tacubaya", "parque lira", "antonio maceo", "rio frio", "del moral",
                "canal de san juan", "nicolas bravo", "gral antonio de leon", "general antonio de leon",
                "tepalcates"));
        DOS_PLATAFORMAS.put(5, set("rio de los remedios", "314 memorial new s divine", "5 de mayo",
                "vasco de quiroga", "el coyol", "preparatoria 3", "san juan de aragon", "rio de guadalupe",
                "talisman", "victoria", "oriente 101", "rio santa coleta", "rio consulado", "canal del norte"));
        DOS_PLATAFORMAS.put(6, set("de los misterios", "hospital infantil la villa", "482", "414",
                "416 oriente", "416 poniente", "volcan de fuego", "ampliacion providencia",
                "deportivo los galeana", "deportivo galeana"));
        // Mexibús L4 Indios Verdes: 2 andenes (sur/norte) SEPARADOS — cambiar de sentido NO es gratis
        // (se paga otro pasaje). Aplica al ordinario (104) y al exprés (124).
        DOS_PLATAFORMAS.put(104, set("indios verdes conexion metrobus l1 y l7"));
        DOS_PLATAFORMAS.put(124, set("indios verdes conexion metrobus l1 y l7"));
    }

    /** ¿La estación tiene 2 plataformas? (cruzar de sentido cuesta salir y pagar otro pasaje). */
    private static boolean dosPlataformas(int linea, String nn) {
        Set<String> s = DOS_PLATAFORMAS.get(linea);
        return s != null && s.contains(nn);
    }

    /** Terminales canónicas {inicio, final} de las líneas direccionales (L2/L6/L7). */
    public static String[] terminales(int linea) {
        switch (linea) {
            case 2: return new String[]{"Tacubaya", "Tepalcates"};
            case 6: return new String[]{"El Rosario", "Villa de Aragón"};
            case 7: return new String[]{"Indios Verdes", "Campo Marte"};
            // Mexibús (ordinarios y ramales)
            case 101: return new String[]{"MXB Ojo de Agua", "MXB Ciudad Azteca"};                            // L1
            case 111: return new String[]{"MXB Ojo de Agua", "MXB Terminal de Pasajeros"};                    // L1A (AIFA)
            case 102: return new String[]{"MXB La Quebrada", "MXB Las Américas"};                             // L2
            case 103: return new String[]{"Pantitlán (conexión Metrobús L4)", "MXB Chimalhuacán"};           // L3
            case 113: return new String[]{"MXB Acuitlapilco", "MXB Central de Abastos Chicoloapan"};           // L3A (circuito; retorno en CEDA Chicoloapan)
            case 104: return new String[]{"MXB La Raza", "MXB Universidad Mexiquense del Bicentenario"};      // L4
            // Mexibús exprés (span principal; algunos servicios tienen terminal intermedia)
            case 121: return new String[]{"MXB Ojo de Agua", "MXB Ciudad Azteca"};                            // L1 Exprés (TR3/TR4; TR4 sale de Central de Abastos)
            case 122: return new String[]{"MXB Ecatepec", "MXB Lechería Express"};                            // L2 Exprés (ERO)
            case 123: return new String[]{"Pantitlán (conexión Metrobús L4)", "MXB Chimalhuacán"};           // L3 Exprés
            case 124: return new String[]{"Indios Verdes", "MXB Universidad Mexiquense del Bicentenario"};    // L4 Exprés (sur en Indios Verdes)
            default: return null;
        }
    }

    /**
     * Estaciones (nombre normalizado) que NO se sirven en un sentido de una línea direccional.
     * haciaFinal=true → sentido hacia la terminal final (ida); false → hacia el inicio (vuelta).
     */
    public static Set<String> excluidasSentido(int linea, boolean haciaFinal) {
        if (linea == 2) return haciaFinal ? L2_IDA : L2_VUELTA;
        if (linea == 6) return haciaFinal ? L6_IDA : L6_VUELTA;
        return java.util.Collections.emptySet();
    }

    /** Terminal canónica hacia donde va cada ruta dirigida (por su id), o null. */
    private static String terminalCanonico(String id) {
        if (id == null) return null;
        switch (id) {
            case "L2>": return "Tepalcates";
            case "L2<": return "Tacubaya";
            case "L6>": return "Villa de Aragón";
            case "L6<": return "El Rosario";
            case "L7>": return "Campo Marte";
            case "L7<": return "Indios Verdes";
            default: return null;
        }
    }

    /** Ruta de una línea física en un sentido (couplet), excluyendo las paradas de la otra vía. */
    private static Route dirRoute(Linea l, String id, boolean ida, Set<String> excluir) {
        // Mexibús (numero >= 100) muestra su nombre ("Mexibús L1") en vez de "L101".
        String visible = l.numero >= 100 ? l.nombre : null;
        Route r = new Route(id, visible, false, true, l, l.color);
        int n = l.estaciones.size();
        for (int k = 0; k < n; k++) {
            Estacion e = l.estaciones.get(ida ? k : n - 1 - k);
            if (e.soloMapa) continue;   // 2º andén a ras: solo mapa, no rutea
            String nn = norm(e.nombre);
            if (excluir.contains(nn)) continue;
            r.stops.add(new Stop(e.nombre, nn, e.icono, e.posicion, l.numero, l.color));
        }
        return r;
    }

    /** Arma un recorrido mixto como ruta de UNA VÍA (opcionalmente invertido para el otro sentido). */
    private static Route construirMixta(Context ctx, RutasMixtas.SeqMixta sm, boolean invertir) {
        int n = sm.estaciones.length;
        Route r = null;
        LatLng prev = null;   // parada previa: para elegir el andén del sentido en curso (couplet L4/L7)
        for (int t = 0; t < n; t++) {
            int k = invertir ? n - 1 - t : t;
            Estacion e = enLinea(ctx, sm.lineas[k], sm.estaciones[k], prev);
            if (e == null) continue;
            Linea lin = GtfsRepository.porNumero(ctx, sm.lineas[k]);
            int col = lin != null ? lin.color : 0;
            if (r == null) r = new Route(sm.nombre + (invertir ? "<" : ""), sm.nombreVisible, true, true, null, col);
            r.stops.add(new Stop(e.nombre, norm(e.nombre), e.icono, e.posicion, sm.lineas[k], col));
            prev = e.posicion;
        }
        return (r != null && r.stops.size() >= 2) ? r : null;
    }

    /** Grafo del planificador (rutas + nodos + aristas), cacheado entre búsquedas. */
    private static final class Grafo {
        final List<Route> rutas;
        final List<int[]> node;
        final int[][] rango;
        final List<List<double[]>> adj;
        Grafo(List<Route> rutas, List<int[]> node, int[][] rango, List<List<double[]>> adj) {
            this.rutas = rutas; this.node = node; this.rango = rango; this.adj = adj;
        }
    }
    private static Grafo grafoCache;
    private static boolean grafoAero;
    private static boolean grafoMexibus;
    private static String grafoCortes = null;

    // Motivo del último calcular() que devolvió null (para dar un toast útil al usuario).
    public static final int MOTIVO_OK = 0, MOTIVO_ESTACION_CERRADA = 1, MOTIVO_SIN_RUTA = 2;
    public static volatile int motivoFallo = MOTIVO_OK;
    /** Estación (nombre canónico) que quedó fuera de servicio, cuando MOTIVO_ESTACION_CERRADA. */
    public static volatile String estacionCerrada = null;

    public static Ruta calcular(Context ctx, String origen, String destino) {
        return calcular(ctx, origen, destino, 0, 0);
    }

    /**
     * Como {@link #calcular(Context, String, String)}, pero fijando la LÍNEA de origen y/o destino
     * (0 = cualquiera). Se usa cuando el usuario desambigua una estación homónima (p. ej. "Las Américas"
     * existe en L1 y L2 sin ser correspondencia, o "Buenavista" en Metrobús y Mexibús): sólo los andenes
     * de la línea elegida son origen/destino válidos.
     */
    public static Ruta calcular(Context ctx, String origen, String destino, int lineaO, int lineaD) {
        // El servicio AICM (Aeropuerto–Amajac, ~$30) solo se usa si el viaje toca T1/T2;
        // si no, la troncal (~$6). Como T1/T2 solo existen en ese servicio, basta con
        // incluirlo únicamente cuando origen o destino sea una Terminal.
        boolean aeropuerto = norm(origen).contains("terminal") || norm(destino).contains("terminal");

        // Grafo cacheado (rutas + nodos + aristas): es estático salvo por si incluye el servicio de
        // aeropuerto y por los cortes de línea (que parten la topología). Se reconstruye SOLO cuando
        // eso cambia; las afectaciones normales NO lo invalidan (solo cambian qué nodos son origen/
        // destino válidos, lo cual se checa por consulta en el Dijkstra de abajo). Esto evita rehacer
        // el bucle de transbordos O(n²) en cada búsqueda.
        String cortesKey = Manifestaciones.cortesClave();
        boolean mexibus = Modos.mostrarMexibus(ctx);   // el grafo cambia si se prende/apaga el Mexibús
        List<Route> rutas; List<int[]> node; int[][] rango; List<List<double[]>> adj; int n;
        if (grafoCache != null && grafoAero == aeropuerto && grafoMexibus == mexibus && cortesKey.equals(grafoCortes)) {
            rutas = grafoCache.rutas; node = grafoCache.node; rango = grafoCache.rango; adj = grafoCache.adj;
            n = node.size();
        } else {
        // 1. Construir rutas: 7 líneas físicas + recorridos mixtos con su secuencia.
        rutas = new ArrayList<>();
        for (Linea l : GtfsRepository.getRuteables(ctx)) {
            if (l.numero == 4 || l.numero == 7) continue;   // L4 y L7 se rutean por sus servicios (RutasMixtas)
            if (l.numero == 2) {           // L2: el este es couplet de una vía → dos rutas dirigidas
                rutas.add(dirRoute(l, "L2>", true, L2_IDA));    // Tacubaya → Tepalcates
                rutas.add(dirRoute(l, "L2<", false, L2_VUELTA)); // Tepalcates → Tacubaya
                continue;
            }
            if (l.numero == 6) {           // L6: couplet de una vía en el tramo oriente
                rutas.add(dirRoute(l, "L6>", true, L6_IDA));    // El Rosario → Villa de Aragón
                rutas.add(dirRoute(l, "L6<", false, L6_VUELTA)); // Villa de Aragón → El Rosario
                continue;
            }
            if (l.numero == 112 || l.numero == 113) {   // L2A/L3A: CIRCUITO de una sola vía (datos en orden del bucle)
                rutas.add(dirRoute(l, "L" + l.numero + "o", true, java.util.Collections.<String>emptySet()));
                continue;
            }
            // L1/L3/L5: dos rutas DIRIGIDAS (ida/vuelta) para poder bloquear y desviar por sentido.
            // L3 excluye ramales terminales que se desvían de la troncal.
            Set<String> excl = l.numero == 3 ? set("la raza", "buenavista")
                    : java.util.Collections.<String>emptySet();
            rutas.add(dirRoute(l, "L" + l.numero + ">", true, excl));
            rutas.add(dirRoute(l, "L" + l.numero + "<", false, excl));
        }
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            if (!aeropuerto && sm.nombre.startsWith("L4-AA")) continue;   // AICM solo si vas a T1/T2
            // Cada recorrido mixto se arma como ruta de UNA VÍA (sentido bien definido). Si es
            // bidireccional, se agrega también el sentido contrario. Así el bloqueo por sentido
            // atrapa correctamente a los mixtos (C2/C3/C21/A31) que pasan por una estación afectada.
            Route fwd = construirMixta(ctx, sm, false);
            if (fwd != null) rutas.add(fwd);
            if (!sm.unaVia) {
                Route rev = construirMixta(ctx, sm, true);
                if (rev != null) rutas.add(rev);
            }
        }

        // 2. Nodos (ruta, parada) + aristas.
        node = new ArrayList<>();   // {rutaIdx, stopIdx}
        for (int ri = 0; ri < rutas.size(); ri++) {
            for (int si = 0; si < rutas.get(ri).stops.size(); si++) node.add(new int[]{ri, si});
        }
        n = node.size();
        rango = new int[rutas.size()][2];   // primer nodo de cada ruta
        int idx = 0;
        for (int ri = 0; ri < rutas.size(); ri++) { rango[ri][0] = idx; idx += rutas.get(ri).stops.size(); rango[ri][1] = idx; }

        adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        // viajar dentro de la ruta (una vía = solo hacia adelante). tipo 0 = viaje (pasa de largo).
        for (int ri = 0; ri < rutas.size(); ri++) {
            boolean uni = rutas.get(ri).unaVia;
            for (int i = rango[ri][0]; i + 1 < rango[ri][1]; i++) {
                Stop sa = stopDe(rutas, node.get(i)), sb = stopDe(rutas, node.get(i + 1));
                // Tramo CORTADO: no se puede pasar por ahí (parte la línea en dos). Se elimina la arista.
                if (sa.linea == sb.linea && Manifestaciones.corte(sa.linea, sa.nn, sb.nn)) continue;
                double costo = costoTramo(sa.pos, sb.pos);   // por KILOMETRAJE (distancia real), no por nº de paradas
                adj.get(i).add(new double[]{i + 1, costo, 0});
                if (!uni) adj.get(i + 1).add(new double[]{i, costo, 0});
            }
            // Cierre del CIRCUITO (L2A/L3A): del último andén de vuelta al primero, para seguir el bucle.
            String rid = rutas.get(ri).id;
            if (rid != null && (rid.equals("L112o") || rid.equals("L113o"))) {
                int first = rango[ri][0], last = rango[ri][1] - 1;
                if (last > first) {
                    Stop sl = stopDe(rutas, node.get(last)), sf = stopDe(rutas, node.get(first));
                    if (!Manifestaciones.corte(sl.linea, sl.nn, sf.nn))
                        adj.get(last).add(new double[]{first, costoTramo(sl.pos, sf.pos), 0});
                }
            }
        }
        // transbordo: nodos co-ubicados de distinta ruta. tipo 1 = transbordo (requiere estación abierta).
        for (int a = 0; a < n; a++) {
            Stop sa = stopDe(rutas, node.get(a));
            for (int b = a + 1; b < n; b++) {
                if (node.get(a)[0] == node.get(b)[0]) continue;
                Stop sb = stopDe(rutas, node.get(b));
                // Correspondencia por CERCANÍA: dos andenes de distinta ruta a <= 800 m se caminan
                // (o una correspondencia manual declarada más lejana).
                double dpar = Linea.distancia(sa.pos, sb.pos);
                boolean liga = dpar <= RADIO_CORRESP || corrManual(sa.linea, sa.nn, sb.linea, sb.nn);
                if (!liga) continue;
                // Vuelta (ida↔vuelta de la MISMA línea): GRATIS solo en estaciones de una sola
                // plataforma (bidireccionales), donde tomas el otro sentido sin salir. En las de
                // 2 plataformas hay que salir y pagar OTRO pasaje → ahí NO se ofrece la vuelta.
                Route ra = rutas.get(node.get(a)[0]), rb = rutas.get(node.get(b)[0]);
                if (ra.linea != null && ra.linea == rb.linea
                        && dosPlataformas(ra.linea.numero, sa.nn)) continue;
                // L7 (estela, cobro a bordo): los 2 andenes de una estación son de sentidos opuestos
                // y cada uno es OTRO cobro; no hay vuelta gratis entre ellos. (L4 no: tiene 1 andén
                // por estación y sus servicios sí requieren transbordo entre sí.)
                if (sa.linea == sb.linea && sa.linea == 7 && sa.nn.equals(sb.nn)) continue;
                // Costo del transbordo = overhead base + caminata (según distancia, hasta 800 m) +
                // espera por frecuencia de la línea que se ABORDA (ramales Mexibús pasan menos seguido).
                double cam = caminata(dpar);
                adj.get(a).add(new double[]{b, SEG_TRANSBORDO + cam + esperaExtra(sb.linea) + penalConexion(sb), 1});
                adj.get(b).add(new double[]{a, SEG_TRANSBORDO + cam + esperaExtra(sa.linea) + penalConexion(sa), 1});
            }
        }
            grafoCache = new Grafo(rutas, node, rango, adj);
            grafoAero = aeropuerto; grafoMexibus = mexibus; grafoCortes = cortesKey;
        }

        // 3. Dijkstra desde cualquier nodo del origen a cualquiera del destino.
        String on = norm(origen), dn = norm(destino);
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] esDest = new boolean[n];
        boolean hayDest = false, haySrc = false;
        // Un destino cerrado EN SU SENTIDO no es válido (no puedes bajar ahí); pasar de largo sí.
        for (int i = 0; i < n; i++) { dist[i] = Double.MAX_VALUE; prev[i] = -1; Stop sd = stopDe(rutas, node.get(i)); if (sd.nn.equals(dn) && (lineaD == 0 || sd.linea == lineaD) && !nodoBloqueado(ctx, rutas, node.get(i))) { esDest[i] = true; hayDest = true; } }
        PriorityQueue<double[]> pq = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (int i = 0; i < n; i++) {
            Stop s = stopDe(rutas, node.get(i));
            // Espera inicial por abordar en origen: 0 para Metrobús, más para líneas de baja frecuencia.
            if (s.nn.equals(on) && (lineaO == 0 || s.linea == lineaO) && !nodoBloqueado(ctx, rutas, node.get(i))) {
                double e0 = esperaExtra(s.linea);
                dist[i] = e0; pq.add(new double[]{e0, i}); haySrc = true;
            }
        }
        if (!haySrc || !hayDest) {
            // El origen o el destino no tienen ningún andén abierto: la estación está fuera de servicio.
            motivoFallo = MOTIVO_ESTACION_CERRADA;
            estacionCerrada = !haySrc ? origen : destino;
            return null;
        }

        int fin = -1;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) continue;
            if (esDest[u]) { fin = u; break; }
            for (double[] ar : adj.get(u)) {
                int v = (int) ar[0];
                boolean transbordo = ar.length > 2 && ar[2] == 1;
                // Estación cerrada: NO se puede transbordar ahí (ni de entrada ni de salida),
                // pero SÍ se puede pasar de largo en un viaje (tipo 0). Así una estación
                // sin servicio no te desvía salvo que sea destino o punto de transbordo.
                if (transbordo && (nodoBloqueado(ctx, rutas, node.get(u))
                        || nodoBloqueado(ctx, rutas, node.get(v)))) continue;
                double nd = dist[u] + ar[1];
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; pq.add(new double[]{nd, v}); }
            }
        }
        if (fin < 0) {
            // Endpoints válidos pero no hay camino: línea partida por un corte/manifestación.
            motivoFallo = MOTIVO_SIN_RUTA;
            estacionCerrada = null;
            return null;
        }

        List<Integer> camino = new ArrayList<>();
        for (int u = fin; u != -1; u = prev[u]) camino.add(u);
        java.util.Collections.reverse(camino);

        // 4. Tramos por ruta.
        List<Paso> pasos = new ArrayList<>();
        List<Instruccion> instrucciones = new ArrayList<>();
        List<LatLng> trazo = new ArrayList<>();
        List<Parada> secuencia = new ArrayList<>();
        int paradasTot = 0;

        // secuencia (deslizador): ⇄ cuando cambia de ruta
        for (int k = 0; k < camino.size(); k++) {
            Stop s = stopDe(rutas, node.get(camino.get(k)));
            boolean trans = k > 0 && node.get(camino.get(k))[0] != node.get(camino.get(k - 1))[0];
            secuencia.add(new Parada(s.nombre, s.linea, s.color, trans, s.pos, s.icono));
        }

        int i = 0;
        while (i < camino.size()) {
            int ri = node.get(camino.get(i))[0];
            int j = i;
            while (j + 1 < camino.size() && node.get(camino.get(j + 1))[0] == ri) j++;
            Route r = rutas.get(ri);
            Stop a = stopDe(rutas, node.get(camino.get(i))), b = stopDe(rutas, node.get(camino.get(j)));
            int siA = node.get(camino.get(i))[1], siB = node.get(camino.get(j))[1];

            List<LatLng> pts;
            // Estaciones del tramo en orden de viaje (respaldo si no hay geometría).
            List<LatLng> paradasPos = new ArrayList<>();
            if (siA <= siB) for (int k = siA; k <= siB; k++) paradasPos.add(r.stops.get(k).pos);
            else for (int k = siA; k >= siB; k--) paradasPos.add(r.stops.get(k).pos);

            if (r.mixta) {
                // Recorrido mixto: geometría real de cada línea que atraviesa (L7/L4 por servicio).
                pts = trazoMixto(ctx, r, siA, siB);
            } else if (r.linea != null) {
                // Rebana el shape del SENTIDO (la sublínea del GTFS que de verdad se dibuja). La
                // cobertura se checa contra ESA geometría, NO la ruta base de lineas.json (que puede
                // estar desfasada: p. ej. L3 llega a Tenayuca solo en la sublínea, no en la ruta base,
                // y eso hacía caer TODO el tramo a líneas rectas). Si no cubre, va por las estaciones.
                List<LatLng> geo = geomSentido(ctx, r.linea.numero, a.pos, b.pos);
                pts = (geo != null && geo.size() >= 2 && cercaDeRuta(geo, a.pos) && cercaDeRuta(geo, b.pos))
                        ? subRuta(geo, a.pos, b.pos) : paradasPos;
            } else {
                pts = paradasPos;                            // sin geometría: por las estaciones
            }
            trazo.addAll(pts);

            int paradas = Math.abs(siB - siA);
            paradasTot += paradas;
            String terminal = (siB >= siA ? r.stops.get(r.stops.size() - 1) : r.stops.get(0)).nombre;
            String tc = terminalCanonico(r.id);   // L2/L6/L7: terminal real (p. ej. Tacubaya, no Parque Lira)
            if (tc != null) terminal = tc;
            pasos.add(new Paso(a.linea, r.color, a.nombre, b.nombre, paradas, pts, r.mixta));
            instrucciones.add(new Instruccion(terminal, r.nombreVisible, a.linea, r.color, paradas, i > 0));
            i = j + 1;
        }

        int transbordos = instrucciones.size() - 1;
        int minutos = (int) Math.round(dist[fin] / 60.0);
        motivoFallo = MOTIVO_OK; estacionCerrada = null;
        return new Ruta(pasos, instrucciones, paradasTot, transbordos, minutos, trazo, secuencia);
    }

    private static Stop stopDe(List<Route> rutas, int[] nd) {
        return rutas.get(nd[0]).stops.get(nd[1]);
    }

    /**
     * ¿El nodo está bloqueado para subir/bajar/transbordar EN SU SENTIDO de viaje? El sentido se
     * decide por GEOMETRÍA (hacia qué terminal avanza este nodo, mirando su parada siguiente), NO
     * por la terminal final de la ruta. Así el bloqueo también atrapa a los recorridos mixtos
     * (C2/C3/C21, L7…) que pasan por la estación aunque su terminal sea otra.
     */
    private static boolean nodoBloqueado(Context ctx, List<Route> rutas, int[] nd) {
        Route r = rutas.get(nd[0]);
        int idx = nd[1];
        Stop s = r.stops.get(idx);
        Set<String> sentidos = Manifestaciones.sentidosBloqueados(s.nn, Perfil.movilidadReducida(ctx));
        if (sentidos.isEmpty()) return false;
        if (sentidos.contains(Manifestaciones.AMBOS)) return true;
        // Referencia de dirección: la parada SIGUIENTE (o la anterior si es fin de ruta).
        boolean haciaAdelante = idx + 1 < r.stops.size();
        LatLng ref = haciaAdelante ? r.stops.get(idx + 1).pos
                : (idx > 0 ? r.stops.get(idx - 1).pos : null);
        if (ref == null) return true;                 // ruta de una sola parada: conservador
        for (String term : sentidos) {
            LatLng tp = posEstacion(ctx, term);
            if (tp == null) return true;              // no ubico la terminal: conservador
            double dS = Linea.distancia(s.pos, tp), dRef = Linea.distancia(ref, tp);
            boolean haciaT = haciaAdelante ? (dRef < dS) : (dS < dRef);
            if (haciaT) return true;                  // este nodo avanza hacia la terminal bloqueada
        }
        return false;
    }

    private static Map<String, LatLng> POS_EST;
    /** Posición de una estación por nombre normalizado (cacheada), para ubicar terminales. */
    private static LatLng posEstacion(Context ctx, String nn) {
        if (POS_EST == null) {
            POS_EST = new HashMap<>();
            for (Linea l : GtfsRepository.getRuteables(ctx))
                for (Estacion e : l.estaciones) if (!e.soloMapa) POS_EST.put(norm(e.nombre), e.posicion);
        }
        return POS_EST.get(nn);
    }

    /**
     * Trazo de un recorrido MIXTO: recorre sus estaciones en orden y, por cada tramo de la
     * misma línea, usa la geometría real de esa línea (o la sublínea por sentido en L7) para
     * seguir la calle y caer en cada estación. Si una línea no tiene trazo cercano, ese tramo
     * va por las estaciones (recto), sin desviarse.
     */
    private static List<LatLng> trazoMixto(Context ctx, Route r, int siA, int siB) {
        // Shape propio del recorrido mixto POR SENTIDO (A31/H72 son couplet: cada sentido va por
        // calles distintas). Incluye los conectores entre líneas (A31 por Eje 2 Norte, etc.).
        String base = claveMixta(r.id);
        List<LatLng> propio = elegirSentido(
                GtfsRepository.sublinea(ctx, base + "-ida"),
                GtfsRepository.sublinea(ctx, base + "-vuelta"),
                r.stops.get(siA).pos, r.stops.get(siB).pos);
        if (propio != null && propio.size() >= 2)
            return subRuta(propio, r.stops.get(siA).pos, r.stops.get(siB).pos);

        List<Integer> orden = new ArrayList<>();
        if (siA <= siB) for (int k = siA; k <= siB; k++) orden.add(k);
        else for (int k = siA; k >= siB; k--) orden.add(k);

        List<LatLng> out = new ArrayList<>();
        int i = 0;
        while (i < orden.size()) {
            int linea = r.stops.get(orden.get(i)).linea;
            int j = i;
            while (j + 1 < orden.size() && r.stops.get(orden.get(j + 1)).linea == linea) j++;
            List<LatLng> grupo = new ArrayList<>();
            for (int t = i; t <= j; t++) grupo.add(r.stops.get(orden.get(t)).pos);

            // Se dibuja por PARES consecutivos: así el trazo pasa por CADA estación intermedia
            // (p. ej. Terminal 1 entre San Lázaro y Terminal 2) y no se salta ninguna al rebanar.
            if (grupo.size() >= 2) {
                for (int t = 0; t + 1 < grupo.size(); t++) {
                    LatLng pa = grupo.get(t), pb = grupo.get(t + 1);
                    List<LatLng> par = java.util.Arrays.asList(pa, pb);
                    List<LatLng> geo = geomLinea(ctx, linea, r.id, par);
                    List<LatLng> sub = (geo != null && geo.size() >= 2) ? subRuta(geo, pa, pb) : null;
                    // El ramal del aeropuerto (L4-aero) es geometría dedicada y correcta aunque sea
                    // larga (el bus rodea de T1 a T2 ~4.7 km): ahí NO se aplica la guarda de longitud.
                    // En el resto, si la rebanada se va por el lado largo del lazo, mejor recto.
                    boolean aero = (linea == 4 && enZonaAeropuerto(ctx, par));
                    if (sub != null && sub.size() >= 2
                            && (aero || longitud(sub) <= 2.2 * Linea.distancia(pa, pb) + 400))
                        out.addAll(sub);
                    else { out.add(pa); out.add(pb); }
                }
            } else {
                out.addAll(grupo);
            }
            i = j + 1;
        }
        return out;
    }

    /** Longitud total de una polilínea (m). */
    private static double longitud(List<LatLng> pts) {
        double d = 0;
        for (int i = 0; i + 1 < pts.size(); i++) d += Linea.distancia(pts.get(i), pts.get(i + 1));
        return d;
    }

    /** Clave del shape propio de un recorrido mixto en sublineas.json (A31/H72). */
    private static String claveMixta(String id) {
        if (id == null) return null;
        return "MX-" + (id.endsWith("<") ? id.substring(0, id.length() - 1) : id);
    }

    /** Geometría real de una línea para un tramo mixto (L7: sublínea por sentido del couplet). */
    private static List<LatLng> geomLinea(Context ctx, int linea, String seqId, List<LatLng> grupo) {
        if (linea == 7) return GtfsRepository.sublinea(ctx, claveSublineaL7(seqId, grupo));
        // L4 al aeropuerto: la ruta base es un lazo y al rebanar San Lázaro→T2 se salta la
        // Terminal 1; usar el ramal dedicado San Lázaro→T1→T2 cuando el tramo toca las terminales.
        if (linea == 4) {
            if (enZonaAeropuerto(ctx, grupo)) {
                // Ida: San Lázaro→T1→T2 (pasa por T1). Vuelta: T2→San Lázaro DIRECTO (sin T1).
                boolean vuelta = seqId != null && seqId.toLowerCase().contains("vuelta");
                List<LatLng> aero = GtfsRepository.sublinea(ctx, vuelta ? "L4-aero-vuelta" : "L4-aero-ida");
                if (aero != null && aero.size() >= 2) return aero;
            }
            // Cada servicio de L4 tiene su shape del GTFS (Ruta Norte/Sur, Hidalgo–Alameda Oriente).
            // Se quita el sufijo "<" del sentido invertido (p. ej. "L4-RN<") para hallar la sublínea;
            // subRuta ya orienta el trazo, así que el mismo shape sirve para ambos sentidos.
            String sk = seqId != null && seqId.endsWith("<") ? seqId.substring(0, seqId.length() - 1) : seqId;
            List<LatLng> serv = sk != null ? GtfsRepository.sublinea(ctx, sk) : null;
            if (serv != null && serv.size() >= 2) return serv;
        }
        return geomSentido(ctx, linea, grupo.get(0), grupo.get(grupo.size() - 1));
    }

    /** ¿El tramo pasa por la zona del aeropuerto (cerca de Terminal 1/2)? */
    private static boolean enZonaAeropuerto(Context ctx, List<LatLng> grupo) {
        LatLng t1 = posEstacion(ctx, "terminal 1"), t2 = posEstacion(ctx, "terminal 2");
        for (LatLng p : grupo) {
            if (t1 != null && Linea.distancia(p, t1) < 2500) return true;
            if (t2 != null && Linea.distancia(p, t2) < 2500) return true;
        }
        return false;
    }

    /**
     * Trazo del SENTIDO de viaje de una línea (carril ida/vuelta correcto). Elige el sentido
     * cuyo inicio queda más cerca del origen del tramo; si no hay sublíneas, usa la ruta base.
     */
    private static List<LatLng> geomSentido(Context ctx, int linea, LatLng desde, LatLng hasta) {
        List<LatLng> ida = GtfsRepository.sublinea(ctx, "L" + linea + "-ida");
        List<LatLng> vuelta = GtfsRepository.sublinea(ctx, "L" + linea + "-vuelta");
        if (ida == null && vuelta == null) {
            Linea l = GtfsRepository.porNumero(ctx, linea);
            return l != null ? l.ruta : null;
        }
        return elegirSentido(ida, vuelta, desde, hasta);
    }

    /**
     * Elige entre ida/vuelta el shape que va en la dirección origen→destino: aquel donde 'desde'
     * se proyecta ANTES que 'hasta' (robusto también para tramos a media ruta). Empata por extremos.
     */
    private static List<LatLng> elegirSentido(List<LatLng> ida, List<LatLng> vuelta, LatLng desde, LatLng hasta) {
        if (ida == null) return vuelta;
        if (vuelta == null) return ida;
        boolean idaOk = idxCercano(ida, desde) <= idxCercano(ida, hasta);
        boolean vueOk = idxCercano(vuelta, desde) <= idxCercano(vuelta, hasta);
        if (idaOk != vueOk) return idaOk ? ida : vuelta;
        double dIda = Linea.distancia(ida.get(0), desde) + Linea.distancia(ida.get(ida.size() - 1), hasta);
        double dVue = Linea.distancia(vuelta.get(0), desde) + Linea.distancia(vuelta.get(vuelta.size() - 1), hasta);
        return dVue < dIda ? vuelta : ida;
    }

    /** Sentido del couplet L7: hacia Campo Marte (sur) por De los Misterios; al norte por GAM. */
    private static String claveSublineaL7(String seqId, List<LatLng> grupo) {
        if ("L7-IC".equals(seqId) || "L7-HC".equals(seqId)) return "L7-sur";      // hacia Campo Marte
        if ("L7-CI".equals(seqId) || "L7-CH".equals(seqId)) return "L7-norte";     // hacia Indios Verdes
        // Otros mixtos que tocan L7 (fuera del couplet ambos trazos coinciden): por rumbo.
        if (grupo.size() >= 2 && grupo.get(grupo.size() - 1).longitude < grupo.get(0).longitude)
            return "L7-sur";
        return "L7-norte";
    }

    /** ¿El trazado pasa a menos de ~350 m del punto? (si no, no sirve para ese tramo). */
    private static boolean cercaDeRuta(List<LatLng> ruta, LatLng p) {
        for (LatLng v : ruta) if (Linea.distancia(v, p) <= 350.0) return true;
        return false;
    }

    /**
     * Rebana la geometría entre a y b: PROYECTA cada extremo sobre el segmento más cercano de la
     * ruta (así el trazo cae exactamente sobre la línea a la altura de la estación y NO se
     * extiende de más), y agrega los vértices intermedios en orden.
     */
    private static List<LatLng> subRuta(List<LatLng> ruta, LatLng a, LatLng b) {
        List<LatLng> out = new ArrayList<>();
        if (ruta == null || ruta.size() < 2) { out.add(a); out.add(b); return out; }
        int sa = segCercano(ruta, a), sb = segCercano(ruta, b);
        LatLng pa = proyecta(ruta.get(sa), ruta.get(sa + 1), a);
        LatLng pb = proyecta(ruta.get(sb), ruta.get(sb + 1), b);
        out.add(pa);
        if (sa < sb) for (int k = sa + 1; k <= sb; k++) out.add(ruta.get(k));
        else if (sa > sb) for (int k = sa; k >= sb + 1; k--) out.add(ruta.get(k));
        out.add(pb);
        return out;
    }

    /** Índice del segmento [i,i+1] de la ruta más cercano al punto p. */
    private static int segCercano(List<LatLng> ruta, LatLng p) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i + 1 < ruta.size(); i++) {
            double d = Linea.distancia(proyecta(ruta.get(i), ruta.get(i + 1), p), p);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Punto más cercano a p sobre el segmento a–b (proyección acotada al segmento). */
    private static LatLng proyecta(LatLng a, LatLng b, LatLng p) {
        double ax = a.longitude, ay = a.latitude, dx = b.longitude - ax, dy = b.latitude - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((p.longitude - ax) * dx + (p.latitude - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return new LatLng(ay + t * dy, ax + t * dx);
    }

    private static int idxCercano(List<LatLng> ruta, LatLng p) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < ruta.size(); i++) {
            double d = Linea.distancia(ruta.get(i), p);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }
}
