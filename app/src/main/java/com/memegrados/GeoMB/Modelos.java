package com.memegrados.GeoMB;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Catálogo de MARCA y MODELO por número económico.
 *
 * Los datos NO viven en el código. Se cargan en dos pasos:
 *   1) del CSV empaquetado (assets/modelos.csv) — disponible de inmediato,
 *   2) del CSV remoto en el backend (Config.MODELOS_URL) — colaborativo y en
 *      vivo; cuando llega, reemplaza al empaquetado.
 *
 * Formato del CSV: una línea por unidad  ->  economico,marca,modelo
 * Se ignoran líneas vacías, comentarios (#) y el encabezado. Lo no listado
 * queda "Desconocido".
 */
public final class Modelos {

    public static final String DESCONOCIDO = "Desconocido";
    public static final String SIN_ASIGNAR = "Sin asignar";

    /** económico -> ficha. Se reemplaza al cargar (empaquetado y luego remoto). */
    private static volatile Map<Integer, Ficha> tabla = new HashMap<>();

    private Modelos() {}

    /** Empresa + marca + modelo (+ imagen y créditos) de una unidad, desde el CSV. */
    public static final class Ficha {
        public final String empresa;
        public final String marca;
        public final String modelo;
        public final String imagen;    // URL directa de una foto de la unidad (opcional)
        public final String credito;   // créditos/atribución de la imagen (opcional)

        Ficha(String empresa, String marca, String modelo) {
            this(empresa, marca, modelo, "", "");
        }
        Ficha(String empresa, String marca, String modelo, String imagen, String credito) {
            this.empresa = empresa; this.marca = marca; this.modelo = modelo;
            this.imagen = imagen; this.credito = credito;
        }

        public boolean esDesconocida() {
            return DESCONOCIDO.equals(marca) && DESCONOCIDO.equals(modelo);
        }

        /** "Marca Modelo" listo para mostrar, o "Desconocido" si no hay datos. */
        public String etiqueta() {
            if (esDesconocida()) return DESCONOCIDO;
            if (DESCONOCIDO.equals(modelo)) return marca;
            if (DESCONOCIDO.equals(marca)) return modelo;
            return marca + " " + modelo;
        }
    }

    /** Carga el catálogo. Llamar una sola vez al iniciar la app. */
    public static void init(Context context) {
        Context app = context.getApplicationContext();

        // 1) Empaquetado (sincrónico): queda disponible de inmediato.
        final Map<Integer, Ficha> base = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                app.getAssets().open("modelos.csv"), StandardCharsets.UTF_8))) {
            parse(r, base);
        } catch (Exception ignore) {
            // si falta el archivo, seguimos con lo que traiga el remoto
        }
        if (!base.isEmpty()) tabla = base;

        // 2) Remoto (segundo plano): reemplaza cuando llega.
        new Thread(() -> {
            Map<Integer, Ficha> remoto = descargarRemoto();
            if (remoto != null && !remoto.isEmpty()) {
                Map<Integer, Ficha> combinado = new HashMap<>(base);
                combinado.putAll(remoto);   // el remoto manda
                tabla = combinado;
            }
        }, "modelos-fetch").start();
    }

    private static Map<Integer, Ficha> descargarRemoto() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(Config.MODELOS_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "text/csv");
            if (conn.getResponseCode() / 100 != 2) return null;
            Map<Integer, Ficha> m = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                parse(r, m);
            }
            return m;
        } catch (Exception e) {
            return null;   // sin conexión: nos quedamos con el empaquetado
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Se usa el ENCABEZADO para ubicar las columnas, así el orden puede variar y
    // se pueden añadir columnas nuevas (imagen, credito) sin romper nada. Nombres
    // reconocidos: economico, empresa, marca, modelo, imagen/foto/url, credito(s).
    // Si no hay encabezado, se asume: economico,marca,modelo,empresa,imagen,credito.
    private static void parse(BufferedReader r, Map<Integer, Ficha> out) throws Exception {
        String linea;
        int iEco = 0, iEmp = 3, iMar = 1, iMod = 2, iImg = 4, iCred = 5;  // posicional por defecto
        boolean headerListo = false;
        while ((linea = r.readLine()) != null) {
            String s = linea.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] p = splitCsv(s);
            if (p.length < 2) continue;

            if (!headerListo && soloDigitos(p[0]) == null) {   // fila de encabezado
                iEco = iEmp = iMar = iMod = iImg = iCred = -1;
                for (int i = 0; i < p.length; i++) {
                    String h = normHeader(p[i]);
                    if (h.startsWith("eco")) iEco = i;
                    else if (h.startsWith("empres")) iEmp = i;
                    else if (h.startsWith("marca")) iMar = i;
                    else if (h.startsWith("modelo")) iMod = i;
                    else if (h.startsWith("imag") || h.startsWith("foto") || h.equals("url")) iImg = i;
                    else if (h.startsWith("cred")) iCred = i;
                }
                if (iEco < 0) iEco = 0;
                if (iMar < 0 && iMod < 0) {   // encabezado no reconocido: usa orden posicional
                    iMar = 1; iMod = 2; iEmp = 3; iImg = 4; iCred = 5;
                }
                headerListo = true;
                continue;
            }
            headerListo = true;

            Integer eco = soloDigitos(get(p, iEco));
            if (eco == null) continue;

            String empresa = limpia(get(p, iEmp));
            String marca   = limpia(get(p, iMar));
            String modelo  = limpia(get(p, iMod));
            String imagen  = limpia(get(p, iImg));
            String credito = limpia(get(p, iCred));
            if (empresa.isEmpty()) empresa = SIN_ASIGNAR;
            if (marca.isEmpty()) marca = DESCONOCIDO;
            if (modelo.isEmpty()) modelo = DESCONOCIDO;
            out.put(eco, new Ficha(empresa, marca, modelo, imagen, credito));
        }
    }

    private static String get(String[] p, int i) {
        return (i >= 0 && i < p.length) ? p[i] : "";
    }

    private static String normHeader(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    /** Divide una línea CSV respetando comillas dobles (por URLs con comas). */
    private static String[] splitCsv(String s) {
        java.util.List<String> campos = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean enComillas = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                if (enComillas && i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else enComillas = !enComillas;
            } else if (c == ',' && !enComillas) {
                campos.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        campos.add(cur.toString());
        return campos.toArray(new String[0]);
    }

    /** Quita espacios y comillas de envoltura (CSV de Google Sheets). */
    private static String limpia(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** Mayor económico presente en el catálogo (0 si aún no carga). */
    public static int maxEconomico() {
        int max = 0;
        for (Integer k : tabla.keySet()) if (k != null && k > max) max = k;
        return max;
    }

    /** Devuelve la ficha (marca, modelo) del económico, o "Desconocido". */
    public static Ficha paraEconomico(String economico) {
        Integer n = soloDigitos(economico);
        Ficha f = (n != null) ? tabla.get(n) : null;
        return f != null ? f : new Ficha(SIN_ASIGNAR, DESCONOCIDO, DESCONOCIDO);
    }

    private static Integer soloDigitos(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        if (sb.length() == 0) return null;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
