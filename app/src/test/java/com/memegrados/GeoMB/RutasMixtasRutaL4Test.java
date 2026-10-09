package com.memegrados.GeoMB;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pruebas REALES de {@link RutasMixtas#rutaL4(String)} para el riesgo de homónimos/nombres
 * compuestos documentado en la auditoría de L4: "Pino Suárez" vs. "Pino Suárez Sur" y "Mercado de
 * Sonora" vs. "Mercado de Sonora Sur". {@code coincide()} (privado, usado internamente por
 * {@code rutaL4()}) compara por subcadena en ambos sentidos
 * ({@code t.equals(norm) || t.contains(norm) || norm.contains(t)}), el mismo patrón de riesgo ya
 * corregido en otros lugares del proyecto -- pero aquí NO se demuestra ningún caso incorrecto: ver
 * el javadoc de {@code rutaL4()} y la tabla de {@code SECUENCIAS}, confirmados por lectura directa
 * (y por un escaneo de subcadenas contra el catálogo completo de 42 estaciones de L4 en
 * {@code estaciones.json}): ambos miembros de cada par ("Pino Suárez"/"Pino Suárez Sur" y "Mercado
 * de Sonora"/"Mercado de Sonora Sur") aparecen ÚNICAMENTE dentro de secuencias etiquetadas como
 * Ruta Sur ({@code L4-RS-*}/{@code L4-AA-*}); ninguna secuencia Norte ({@code L4-RN-*}/
 * {@code L4-HAO-*}) los menciona. Por eso ambos nombres de cada par deben clasificar como Ruta Sur
 * (return 2) hoy -- si alguno de estos tests fallara, sería evidencia de un caso incorrecto real, y
 * solo en ese momento se justificaría tocar {@code coincide()} (Tarea 4: "no cambies la
 * comparación global sin demostrar un caso incorrecto").
 *
 * <p>No se prueba aquí ningún cambio a {@code RutasMixtas.java}: este archivo no fue modificado.
 */
public class RutasMixtasRutaL4Test {

    @Test
    public void pinoSuarez_clasificaComoRutaSur() {
        assertEquals("Pino Suárez (sin sufijo) debe ser Ruta Sur (2)",
                2, RutasMixtas.rutaL4("Pino Suárez"));
    }

    @Test
    public void pinoSuarezSur_clasificaComoRutaSur() {
        assertEquals("Pino Suárez Sur (con sufijo) debe ser Ruta Sur (2), igual que sin sufijo",
                2, RutasMixtas.rutaL4("Pino Suárez Sur"));
    }

    @Test
    public void mercadoDeSonora_clasificaComoRutaSur() {
        assertEquals("Mercado de Sonora (sin sufijo) debe ser Ruta Sur (2)",
                2, RutasMixtas.rutaL4("Mercado de Sonora"));
    }

    @Test
    public void mercadoDeSonoraSur_clasificaComoRutaSur() {
        assertEquals("Mercado de Sonora Sur (con sufijo) debe ser Ruta Sur (2), igual que sin sufijo",
                2, RutasMixtas.rutaL4("Mercado de Sonora Sur"));
    }

    // Control negativo: una estación real exclusiva de Ruta Norte (confirmada en el javadoc de
    // rutaL4()) no debe clasificar como Sur.
    @Test
    public void hidalgo_estacionDeRutaNorte_noClasificaComoSur() {
        assertEquals("Hidalgo es una estación de Ruta Norte: debe ser 1, no Ruta Sur",
                1, RutasMixtas.rutaL4("Hidalgo"));
    }

    // Control: una estación de troncal compartida (ambas rutas) debe salir 0, no 1 ni 2.
    @Test
    public void buenavista_troncalCompartida_claseAmbas() {
        assertEquals("Buenavista es troncal compartida (ambas rutas): debe ser 0",
                0, RutasMixtas.rutaL4("Buenavista"));
    }

    // Variación de mayúsculas/acentos: la normalización interna no debe alterar la clasificación.
    @Test
    public void pinoSuarezSinAcento_siguesSiendoRutaSur() {
        assertEquals(2, RutasMixtas.rutaL4("pino suarez"));
    }
}
