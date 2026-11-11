package com.memegrados.GeoMB;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estilo CITI: "Estimaciones de llegadas" (FragmentTiempos / fragment_tiempos.xml de la app de
 * Indra). Al abrir y con "Refrescar" descarga el feed; muestra las próximas unidades de todas las
 * rutas de la estación (cada línea con su andén), ordenadas por tiempo.
 */
public class CitiTiemposFragment extends CitiPantalla {

    private static final String A_NOMBRE = "nombre", A_ICONO = "icono", A_LINEA = "linea",
            A_COLOR = "color", A_LAT = "lat", A_LON = "lon";

    private CitiDatos.Est est;
    private boolean avisoMostrado;   // el original daba el "Aviso" solo la primera vez
    private View resultado;
    private ListView lista;
    private TextView btnAvisar;
    private int lineaAviso;   // línea elegida para "Avisarme" mientras se pide el permiso
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Como LlegadasFragment: mientras la pantalla está abierta los tiempos se actualizan solos.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override public void onData(List<UnidadReal> u) { if (isAdded()) llenar(u, false); }
                @Override public void onError(String m) { }
            });
            handler.postDelayed(this, Red.intervalo(getContext(), Config.LLEGADA_POLL_MS));
        }
    };
    private final ActivityResultLauncher<String> permisoNotif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> arrancarAviso(lineaAviso));

    public static CitiTiemposFragment de(CitiDatos.Est e) {
        Bundle b = new Bundle();
        b.putString(A_NOMBRE, e.nombre);
        b.putString(A_ICONO, e.icono);
        b.putInt(A_LINEA, e.linea);
        b.putInt(A_COLOR, e.color);
        b.putDouble(A_LAT, e.pos.latitude);
        b.putDouble(A_LON, e.pos.longitude);
        CitiTiemposFragment f = new CitiTiemposFragment();
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Bundle a = requireArguments();
        est = new CitiDatos.Est(a.getString(A_NOMBRE), a.getString(A_ICONO), a.getInt(A_LINEA),
                a.getInt(A_COLOR), a.getDouble(A_LAT), a.getDouble(A_LON));
        View v = inflater.inflate(R.layout.tiempos_citi, container, false);
        barra(v, R.string.citi_estimaciones);
        iconoEstacion(v.findViewById(R.id.citi_tiempos_icono), est, 32);
        ((TextView) v.findViewById(R.id.citi_tiempos_estacion)).setText(est.nombre);
        resultado = v.findViewById(R.id.citi_tiempos_resultado);
        lista = v.findViewById(R.id.citi_tiempos_lista);
        v.findViewById(R.id.citi_tiempos_refrescar).setOnClickListener(x -> descargar());
        btnAvisar = v.findViewById(R.id.citi_tiempos_avisar);
        btnAvisar.setOnClickListener(x -> alternarAviso());
        actualizarBotonAviso();
        descargar();   // muestraTiempos() del original siempre descargaba al abrir
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarBotonAviso();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, Red.intervalo(getContext(), Config.LLEGADA_POLL_MS));
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    // ---- "Avisarme": notificación cuando una unidad se acerque a esta estación (LlegadaService) ----

    private boolean avisandoAqui() {
        return est.nombre.equals(LlegadaService.paradaSeguida);
    }

    private void actualizarBotonAviso() {
        if (btnAvisar != null) btnAvisar.setText(avisandoAqui() ? R.string.llegada_dejar : R.string.llegada_avisar);
    }

    /** Con varias rutas en la estación pregunta de cuál avisar (cada una con su andén). */
    private void alternarAviso() {
        if (avisandoAqui()) {
            try {
                requireContext().startService(new Intent(requireContext(), LlegadaService.class)
                        .setAction(LlegadaService.ACCION_DETENER));
            } catch (IllegalStateException | SecurityException ignore) {}   // el SO puede negarlo
            LlegadaService.paradaSeguida = null;
            actualizarBotonAviso();
            return;
        }
        java.util.TreeMap<Integer, CitiDatos.Est> lineas = CitiDatos.lineasDe(requireContext(), est);
        if (lineas.size() <= 1) { pedirAviso(est.linea); return; }
        List<CitiActivity.Opcion> op = new ArrayList<>();
        for (Integer ln : lineas.keySet())
            op.add(new CitiActivity.Opcion(CitiDatos.etiquetaSistema(ln), () -> { if (!isAdded()) return; citi().cerrarMenu(); pedirAviso(ln); }));
        citi().abrirMenu(op, true);
    }

    private void pedirAviso(int linea) {
        lineaAviso = linea;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permisoNotif.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarAviso(linea);
    }

    private void arrancarAviso(int linea) {
        if (!isAdded()) return;
        CitiDatos.Est anden = CitiDatos.lineasDe(requireContext(), est).get(linea);
        if (anden == null) anden = est;
        Intent i = new Intent(requireContext(), LlegadaService.class)
                .putExtra(LlegadaService.EXTRA_LINEA, linea)
                .putExtra(LlegadaService.EXTRA_ESTACION, est.nombre)
                .putExtra(LlegadaService.EXTRA_LAT, anden.pos.latitude)
                .putExtra(LlegadaService.EXTRA_LON, anden.pos.longitude)
                .putExtra(LlegadaService.EXTRA_AMBOS, true);   // como la lista: ambos sentidos
        try {
            ContextCompat.startForegroundService(requireContext(), i);
        } catch (IllegalStateException | SecurityException ex) {
            Telemetria.registrarError(requireContext(), Telemetria.ERR_EXCEPCION, "CitiTiempos.arrancarAviso", String.valueOf(ex));
            return;
        }
        LlegadaService.paradaSeguida = est.nombre;
        actualizarBotonAviso();
        citi().aviso(getString(R.string.citi_aviso_activado, CitiDatos.etiquetaSistema(linea)));
    }

    @Override
    public void onDestroyView() {
        if (getActivity() instanceof CitiActivity) ((CitiActivity) getActivity()).mostrarDescargando(false);
        handler.removeCallbacks(poll);
        resultado = null;
        lista = null;
        btnAvisar = null;
        super.onDestroyView();
    }

    private void descargar() {
        citi().mostrarDescargando(true);
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> unidades) {
                if (!isAdded()) return;
                citi().mostrarDescargando(false);
                llenar(unidades, true);
            }
            @Override public void onError(String mensaje) {
                if (!isAdded()) return;
                citi().mostrarDescargando(false);
                if (resultado != null) resultado.setVisibility(View.INVISIBLE);
                citi().aviso(getString(R.string.citi_error_conexion));
            }
        });
    }

    /** {@code avisar}: si no hay estimaciones, decirlo con el diálogo (solo al descargar a mano o al
     *  abrir; el refresco automático no debe abrir diálogos cada pocos segundos). */
    private void llenar(List<UnidadReal> unidades, boolean avisar) {
        if (lista == null) return;
        int primera = lista.getFirstVisiblePosition();
        // {linea, "Origen-Destino", minutos, etaSeg, económico}
        final List<Object[]> filas = new ArrayList<>();
        for (Map.Entry<Integer, CitiDatos.Est> e : CitiDatos.lineasDe(requireContext(), est).entrySet()) {
            for (Llegadas.Prox p : Llegadas.proximas(requireContext(), e.getKey(), e.getValue().pos, null,
                    unidades != null ? unidades : new ArrayList<>(), true)) {
                UnidadReal u = RealtimeRepository.get().buscar(p.eco);
                String ruta = (u != null && u.origen != null && u.destino != null)
                        ? u.origen + "-" + u.destino : (p.destino != null ? p.destino : "");
                filas.add(new Object[]{e.getKey(), ruta, Math.round(p.etaSeg / 60f), p.etaSeg, p.eco});
            }
        }
        Collections.sort(filas, (x, y) -> Integer.compare((Integer) x[3], (Integer) y[3]));
        // Una ruta mixta cuenta en sus 2 líneas: cada unidad una sola vez (la más próxima).
        Set<String> vistos = new HashSet<>();
        for (Iterator<Object[]> it = filas.iterator(); it.hasNext(); ) if (!vistos.add((String) it.next()[4])) it.remove();

        lista.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return filas.size(); }
            @Override public Object getItem(int i) { return filas.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_tiempo_citi, parent, false);
                Object[] f = filas.get(i);
                int ln = (Integer) f[0];
                TextView cod = v.findViewById(R.id.citi_tiempo_linea);
                cod.setBackground(cuadroRuta(ln));
                cod.setText(CitiDatos.etiqueta(ln));
                ((TextView) v.findViewById(R.id.citi_tiempo_destino)).setText((String) f[1]);
                ((TextView) v.findViewById(R.id.citi_tiempo_min)).setText(getString(R.string.citi_min, (Integer) f[2]));
                return v;
            }
        });
        if (filas.isEmpty()) {
            resultado.setVisibility(View.INVISIBLE);
            if (avisar) citi().aviso(getString(R.string.citi_sin_estimaciones));
        } else {
            resultado.setVisibility(View.VISIBLE);
            if (avisar) lista.setSelectionAfterHeaderView();
            else lista.setSelection(Math.min(primera, filas.size() - 1));   // el refresco no te mueve la lista
            mostrarAvisos();
        }
    }

    /** Project.avisos de la app original: con los tiempos llegaban los avisos de servicio y se
     *  mostraban una vez en un diálogo "Aviso" con "- …" por línea. Aquí salen de las afectaciones
     *  que ya junta GeoMB (Metrobús, Mexibús y avisos manuales) para las rutas de esta estación. */
    private void mostrarAvisos() {
        if (avisoMostrado) return;
        Set<Integer> lineas = CitiDatos.lineasDe(requireContext(), est).keySet();
        StringBuilder sb = new StringBuilder();
        for (Manifestaciones.Afectacion a : Manifestaciones.lista()) {
            if (a.categoria != Manifestaciones.C_ESTADO || !lineas.contains(a.lineaNum)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(CitiDatos.etiquetaSistema(a.lineaNum)).append(": ").append(a.estado);
            if (a.lugar != null && !a.lugar.isEmpty()) sb.append(" (").append(a.lugar).append(')');
            if (a.info != null && !a.info.isEmpty()) sb.append(". ").append(a.info);
        }
        if (sb.length() == 0) return;
        avisoMostrado = true;
        citi().aviso(getString(R.string.citi_aviso), sb.toString());
    }
}
