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

    @Test
    public void setTransbordoH72L2_soloContieneLaTroncalReal() {
        // Invariante de la que depende la ORIENTACIÓN del ícono diagonal en los 4 lugares que la
        // consultan (MapFragment, EstacionesLineaFragment, PlanificadorFragment y CartaEstacion): solo
        // "Tacubaya"/"De La Salle" (la troncal real de L2) deben estar en este set. Si alguna vez se
        // agrega aquí "Alameda Tacubaya" (p. ej. pensando en "igual hay que incluir el alias nuevo"),
        // los 3 puntos propios de H72 pasarían a dibujarse con la orientación de la troncal -- al
        // revés de lo que el usuario pidió explícitamente al distinguirlos visualmente.
        assertTrue(Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm("Tacubaya")));
        assertTrue(Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm("De La Salle")));
        assertFalse(Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(Planificador.norm("Alameda Tacubaya")));
        assertFalse(Iconos.ESTACIONES_TRANSBORDO_H72_L2.contains(
                Planificador.norm("De la Salle · dirección Alameda Tacubaya")));
        assertEquals(2, Iconos.ESTACIONES_TRANSBORDO_H72_L2.size());
    }

    @Test
    public void nombreRutaDeAlias_deAlias_devuelveElNombreRealDeLaTroncal() {
        // Usado por desambiguar() (PlanificadorFragment) para reconocer un campo sin editar que quedó
        // mostrando el nombre del alias (p. ej. tras elegirlo en la carta) sin volver a preguntar.
        // "Alameda Tacubaya" rutea contra SÍ MISMA: desde que RutasMixtas.H72 la trae como su propia
        // parada real (ver Seq#punto), ya es un nodo genuino del grafo -- ya no se traduce a "Tacubaya".
        assertEquals("Alameda Tacubaya", Planificador.nombreRutaDeAlias("Alameda Tacubaya"));
        assertEquals("De La Salle", Planificador.nombreRutaDeAlias("De la Salle · dirección Alameda Tacubaya"));
        assertEquals("De La Salle", Planificador.nombreRutaDeAlias("De la Salle · dirección Glorieta Cuitláhuac"));
    }

    @Test
    public void nombreRutaDeAlias_deNombreQueNoEsAlias_loDevuelveSinCambios() {
        assertEquals("Tacubaya", Planificador.nombreRutaDeAlias("Tacubaya"));
        assertEquals("Indios Verdes", Planificador.nombreRutaDeAlias("Indios Verdes"));
        assertNull(Planificador.nombreRutaDeAlias(null));
    }

    @Test
    public void h72_traeSuPropioPuntoRealParaAlamedaTacubaya() {
        // RutasMixtas.SECUENCIAS no necesita Context (son literales estáticos): confirma que H72
        // usa su PROPIO punto real (RutasMixtas.Seq#punto) para "Alameda Tacubaya" en vez de resolverla
        // contra el catálogo de L2 (que terminaría en la troncal, no en el punto real donde en verdad
        // termina el servicio) -- la causa raíz de que elegir "Alameda Tacubaya" en el desambiguador
        // trazara una ruta igual a la de "Tacubaya" a secas, sin pasar necesariamente por H72.
        RutasMixtas.SeqMixta h72 = null;
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) if ("H72".equals(sm.nombre)) { h72 = sm; break; }
        assertNotNull("RutasMixtas.SECUENCIAS debe traer H72", h72);

        int idx = -1;
        for (int k = 0; k < h72.estaciones.length; k++) {
            if ("Alameda Tacubaya".equals(h72.estaciones[k])) { idx = k; break; }
        }
        assertTrue("H72 debe traer 'Alameda Tacubaya' como su propia parada", idx >= 0);
        assertEquals(2, h72.lineas[idx]);
        assertNotNull("debe tener posición PROPIA (no se resuelve contra el catálogo de L2)",
                h72.posiciones[idx]);
        assertEquals(19.401473, h72.posiciones[idx].latitude, 1e-6);
        assertEquals(-99.185882, h72.posiciones[idx].longitude, 1e-6);
        assertEquals("ic_est_2_2", h72.iconos[idx]);
    }

    @Test
    public void corrManual_alamedaTacubayaH72_conTacubayaTroncal_siCorresponde() {
        // Transbordo real (~110 m) entre el punto propio de H72 y la troncal de L2: sin esta fila, un
        // camino que de verdad necesite seguir más allá de Tacubaya en L2 (viniendo de H72) se quedaría
        // sin cruce, porque los nombres normalizados no coinciden entre sí.
        assertTrue(Planificador.corrManual(2, Planificador.norm("Alameda Tacubaya"), 2, Planificador.norm("Tacubaya")));
        assertTrue(Planificador.corrManual(2, Planificador.norm("Tacubaya"), 2, Planificador.norm("Alameda Tacubaya")));
    }
}
