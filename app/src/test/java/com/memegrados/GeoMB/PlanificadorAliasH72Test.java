package com.memegrados.GeoMB;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regresión para Planificador.agruparPorEstacionFisica(): un Match alias de H72 (nombreRuta != null,
 * ver Planificador.ALIAS_H72 -- p. ej. "Alameda Tacubaya" ruteando a "Tacubaya") NUNCA debe fusionarse
 * con otro candidato aunque quede a <400 m de su propia troncal real, para que la carta "¿A qué
 * estación te refieres?" los ofrezca siempre como opciones separadas en vez de resolverlos en
 * silencio -- a pedido explícito del usuario tras confundirse por los íconos de "Alameda Tacubaya"/
 * "Tacubaya".
 */
public class PlanificadorAliasH72Test {

    // "Alameda Tacubaya" (alias H72) y "Tacubaya" (troncal real): ~110 m reales uno del otro, muy
    // por debajo del radio de 400 m que normalmente fusionaría dos candidatos co-ubicados.
    private static final LatLng ALAMEDA_TACUBAYA = new LatLng(19.401473, -99.185882);
    private static final LatLng TACUBAYA = new LatLng(19.40194, -99.18702);

    private static Planificador.Match alias(String nombre, LatLng pos, String nombreRuta) {
        return new Planificador.Match(nombre, 2, pos, "ic_est_2_2", nombreRuta);
    }

    private static Planificador.Match real(String nombre, LatLng pos) {
        return new Planificador.Match(nombre, 2, pos, "ic_est_2_2");
    }

    @Test
    public void aliasH72_nuncaSeFusionaConSuTroncalReal() {
        List<Planificador.Match> cs = Arrays.asList(
                alias("Alameda Tacubaya", ALAMEDA_TACUBAYA, "Tacubaya"),
                real("Tacubaya", TACUBAYA));

        List<List<Planificador.Match>> grupos = Planificador.agruparPorEstacionFisica(cs);

        assertEquals(2, grupos.size());
    }

    @Test
    public void dosAliasH72_nuncaSeFusionanEntreSi() {
        // Los 2 puntos "De la Salle · dirección ..." (ambos alias, misma línea) tampoco deben
        // fusionarse entre sí aunque estén cerca: cada uno es su propia opción en la carta.
        LatLng saleOp = new LatLng(19.4083683433015, -99.18420775806);
        LatLng sale = new LatLng(19.4090200863265, -99.1834667351685);
        List<Planificador.Match> cs = Arrays.asList(
                alias("De la Salle · dirección Alameda Tacubaya", saleOp, "De La Salle"),
                alias("De la Salle · dirección Glorieta Cuitláhuac", sale, "De La Salle"));

        List<List<Planificador.Match>> grupos = Planificador.agruparPorEstacionFisica(cs);

        assertEquals(2, grupos.size());
    }

    @Test
    public void dosCandidatosRealesCoubicados_siSeFusionan() {
        // Control: sin alias de por medio, dos Match reales co-ubicados (<400 m, misma línea base y
        // sistema) deben seguir fusionándose como antes -- el cambio no debe afectar este caso.
        LatLng cerca = new LatLng(TACUBAYA.latitude + 0.0005, TACUBAYA.longitude);
        List<Planificador.Match> cs = Arrays.asList(real("Tacubaya", TACUBAYA), real("Tacubaya", cerca));

        List<List<Planificador.Match>> grupos = Planificador.agruparPorEstacionFisica(cs);

        assertEquals(1, grupos.size());
    }
}
