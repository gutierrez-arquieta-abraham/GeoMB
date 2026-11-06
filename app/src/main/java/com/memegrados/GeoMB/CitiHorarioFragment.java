package com.memegrados.GeoMB;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Estilo CITI: "Horario" del menú de ruta (el botón butHorario de fragment_menu_linea.xml de la app
 * de Indra). Con los horarios de GeoMB (assets/horarios.json): un renglón por servicio de la línea
 * con su primera y última salida de hoy en cada sentido.
 */
public class CitiHorarioFragment extends CitiPantalla {

    private static final String A_LINEA = "linea", A_ORIGEN = "origen", A_DESTINO = "destino";

    public static CitiHorarioFragment de(int linea) {
        return de(linea, null, null);
    }

    /** Solo el servicio {@code origen}-{@code destino} (null = todos los de la línea). */
    public static CitiHorarioFragment de(int linea, @Nullable String origen, @Nullable String destino) {
        Bundle b = new Bundle();
        b.putInt(A_LINEA, linea);
        if (origen != null) { b.putString(A_ORIGEN, origen); b.putString(A_DESTINO, destino); }
        CitiHorarioFragment f = new CitiHorarioFragment();
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.lista_citi, container, false);
        barra(v, R.string.citi_horario);
        int linea = requireArguments().getInt(A_LINEA);
        Calendar hoy = Calendar.getInstance();
        final List<String[]> filas = new ArrayList<>();   // {"Origen-Destino (variante)", "Ida … · Regreso …"}
        String soloO = requireArguments().getString(A_ORIGEN), soloD = requireArguments().getString(A_DESTINO);
        for (Horarios.Ruta r : Horarios.deLinea(requireContext(), linea)) {
            if (soloO != null && !(r.origen.equals(soloO) && r.destino.equals(soloD))) continue;
            String t = Horarios.textoHoy(r, hoy);
            String nombre = r.origen + "-" + r.destino + (r.variante != null ? " (" + r.variante + ")" : "");
            filas.add(new String[]{nombre, t != null ? t : getString(R.string.citi_no_circula_hoy)});
        }
        ListView lista = v.findViewById(R.id.citi_lista);
        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return filas.size(); }
            @Override public Object getItem(int i) { return filas.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View fila, ViewGroup parent) {
                if (fila == null) fila = getLayoutInflater().inflate(R.layout.item_linea_citi, parent, false);
                TextView cod = fila.findViewById(R.id.citi_linea_codigo);
                cod.setBackground(cuadroRuta(linea));
                cod.setText(CitiDatos.etiqueta(linea));
                TextView txt = fila.findViewById(R.id.citi_linea_nombre);
                txt.setMaxLines(3);
                txt.setText(filas.get(i)[0] + "\n" + filas.get(i)[1]);
                return fila;
            }
        });
        if (filas.isEmpty()) citi().aviso(getString(R.string.citi_sin_horario));
        return v;
    }
}
