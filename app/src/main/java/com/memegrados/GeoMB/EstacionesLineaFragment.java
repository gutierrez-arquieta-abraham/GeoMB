package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Estaciones de una línea (pestaña Líneas). L4 se muestra por rutas (orden real);
 * L2/L6/L7 se muestran por dirección (encabezado "Dirección &lt;terminal&gt;").
 */
public class EstacionesLineaFragment extends Fragment {

    private static final String ARG_LINEA = "linea";

    public static EstacionesLineaFragment nueva(int linea) {
        EstacionesLineaFragment f = new EstacionesLineaFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_LINEA, linea);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_estaciones, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        int num = getArguments() != null ? getArguments().getInt(ARG_LINEA, 1) : 1;
        Linea l = GtfsRepository.porNumero(requireContext(), num);

        TextView titulo = view.findViewById(R.id.txt_titulo);
        RecyclerView rv = view.findViewById(R.id.recycler_estaciones);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        EstacionesAdapter adapter = new EstacionesAdapter();
        rv.setAdapter(adapter);

        if (l == null) return;
        titulo.setText(getString(R.string.linea_formato, l.numero) + " · " + l.nombre);

        List<EstacionesAdapter.Item> items = new ArrayList<>();
        if (num == 4) construirPorRutas(items, "L4", 0);
        else if (num == 7) construirPorRutas(items, "L7", 7);   // incluye H72 y demás que tocan L7
        else if (num == 2 || num == 6) construirDireccional(items, l);
        else construirPlano(items, l);
        adapter.set(items);
    }

    /** Línea normal (L1/L3/L5): lista simple de estaciones. */
    private void construirPlano(List<EstacionesAdapter.Item> items, Linea l) {
        // Una sola entrada por estación: en los datos algunas traen sus 2 andenes (p. ej. Indios
        // Verdes, Deportivo 18 de Marzo) y no deben aparecer duplicadas en el listado.
        java.util.Set<String> vistas = new java.util.HashSet<>();
        for (Estacion e : l.estaciones) {
            if (!vistas.add(Planificador.norm(e.nombre))) continue;
            items.add(EstacionesAdapter.Item.estacion(e.nombre, "", e.icono, l.color));
        }
    }

    /** L2/L6/L7: dos secciones por dirección, con encabezado "Dirección &lt;terminal&gt;". */
    private void construirDireccional(List<EstacionesAdapter.Item> items, Linea l) {
        List<Estacion> est = l.estaciones;
        if (est.size() < 2) { construirPlano(items, l); return; }
        // Terminales reales (p. ej. L2 = Tacubaya/Tepalcates, no "Parque Lira").
        String[] term = Planificador.terminales(l.numero);
        String termIni = term != null ? term[0] : est.get(0).nombre;             // terminal al inicio (vuelta)
        String termFin = term != null ? term[1] : est.get(est.size() - 1).nombre; // terminal al final (ida)

        // Cada sentido oculta las estaciones que solo se sirven en el sentido contrario (couplet).
        java.util.Set<String> exFin = Planificador.excluidasSentido(l.numero, true);   // hacia final
        java.util.Set<String> exIni = Planificador.excluidasSentido(l.numero, false);  // hacia inicio

        items.add(EstacionesAdapter.Item.header(getString(R.string.direccion_fmt, termFin), l.color));
        for (int k = 0; k < est.size(); k++)
            if (!exFin.contains(Planificador.norm(est.get(k).nombre)))
                items.add(EstacionesAdapter.Item.estacion(est.get(k).nombre, "", est.get(k).icono, l.color));

        items.add(EstacionesAdapter.Item.header(getString(R.string.direccion_fmt, termIni), l.color));
        for (int k = est.size() - 1; k >= 0; k--)
            if (!exIni.contains(Planificador.norm(est.get(k).nombre)))
                items.add(EstacionesAdapter.Item.estacion(est.get(k).nombre, "", est.get(k).icono, l.color));
    }

    /**
     * L4/L7: por rutas (RutasMixtas), en el orden real de cada servicio. Incluye las
     * secuencias con ese prefijo y, si {@code lineaTocada} &gt; 0, también las que pasan
     * por esa línea (p. ej. H72 en L7).
     */
    private void construirPorRutas(List<EstacionesAdapter.Item> items, String prefijo, int lineaTocada) {
        for (RutasMixtas.SeqMixta sm : RutasMixtas.SECUENCIAS) {
            boolean incluir = sm.nombre.startsWith(prefijo) || (lineaTocada > 0 && toca(sm, lineaTocada));
            if (!incluir || sm.estaciones.length == 0) continue;
            // Sin nombre visible (A31, C2, C3…): NO se muestra la clave, sino las líneas que conecta
            // en formato "L# ↔ L#" (flecha bidireccional).
            String visible = sm.nombreVisible != null ? sm.nombreVisible : lineasLabel(sm);
            String cabecera = visible + " · " + sm.estaciones[0] + " → "
                    + sm.estaciones[sm.estaciones.length - 1];
            int color = colorDe(sm.lineas.length > 0 ? sm.lineas[0] : 4);
            items.add(EstacionesAdapter.Item.header(cabecera, color));
            for (int k = 0; k < sm.estaciones.length; k++) {
                Estacion e = buscar(sm.lineas[k], sm.estaciones[k]);
                String icono = e != null ? e.icono : "";
                items.add(EstacionesAdapter.Item.estacion(sm.estaciones[k], "", icono, colorDe(sm.lineas[k])));
            }
        }
    }

    /**
     * Etiqueta de un recorrido mixto por las líneas que conecta, sin su clave: "L1 ↔ L3".
     * Toma las líneas distintas en el orden en que aparecen (consecutivas iguales se colapsan).
     */
    private static String lineasLabel(RutasMixtas.SeqMixta sm) {
        StringBuilder b = new StringBuilder();
        int prev = -1;
        for (int ln : sm.lineas) {
            if (ln != prev) {
                if (b.length() > 0) b.append(" ↔ ");
                b.append("L").append(ln);
                prev = ln;
            }
        }
        return b.length() > 0 ? b.toString() : sm.nombre;
    }

    /** ¿La secuencia pasa por la línea indicada? */
    private static boolean toca(RutasMixtas.SeqMixta sm, int lineaNum) {
        for (int x : sm.lineas) if (x == lineaNum) return true;
        return false;
    }

    private int colorDe(int lineaNum) {
        Linea l = GtfsRepository.porNumero(requireContext(), lineaNum);
        return l != null ? l.color : 0xFFFF9A03;
    }

    /** Busca la estación (por nombre) dentro de una línea, para tomar su pictograma. */
    private Estacion buscar(int lineaNum, String nombre) {
        Linea l = GtfsRepository.porNumero(requireContext(), lineaNum);
        if (l == null) return null;
        String q = Planificador.norm(nombre);
        for (Estacion e : l.estaciones) {
            String nn = Planificador.norm(e.nombre);
            if (nn.equals(q) || nn.contains(q) || q.contains(nn)) return e;
        }
        return null;
    }
}
