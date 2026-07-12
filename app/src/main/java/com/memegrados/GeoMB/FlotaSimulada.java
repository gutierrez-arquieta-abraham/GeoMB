package com.memegrados.GeoMB;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Flota simulada: 4 unidades por línea con número identificador.
 * También guarda la selección actual (unidad o línea a mostrar en el mapa).
 */
public final class FlotaSimulada {

    private static FlotaSimulada instancia;

    /** Unidad seleccionada desde el buscador (null si ninguna). */
    public static String unidadSeleccionada = null;
    /** Línea seleccionada desde la lista (-1 si ninguna). */
    public static int lineaSeleccionada = -1;

    private final List<UnidadSimulada> unidades = new ArrayList<>();

    private FlotaSimulada(Context context) {
        Random rnd = new Random(42);
        for (Linea linea : GtfsRepository.getLineas(context)) {
            for (int i = 1; i <= 4; i++) {
                String numero = String.valueOf(linea.numero * 1000 + 100 + i); // 1101, 1102...
                double inicio = rnd.nextDouble() * linea.largoTotal();
                double velocidad = 8 + rnd.nextDouble() * 6; // 8-14 m/s (~29-50 km/h)
                unidades.add(new UnidadSimulada(numero, linea, inicio, velocidad));
            }
        }
    }

    public static synchronized FlotaSimulada get(Context context) {
        if (instancia == null) {
            instancia = new FlotaSimulada(context.getApplicationContext());
        }
        return instancia;
    }

    public List<UnidadSimulada> getUnidades() {
        return unidades;
    }

    /** Avanza toda la flota `segundos` de tiempo. */
    public void tick(double segundos) {
        for (UnidadSimulada u : unidades) u.avanzar(segundos);
    }

    public UnidadSimulada buscar(String numero) {
        for (UnidadSimulada u : unidades) {
            if (u.numero.equals(numero)) return u;
        }
        return null;
    }
}
