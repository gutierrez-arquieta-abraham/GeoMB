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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aviso de llegada por dirección: eliges línea, estación y sentido (destino),
 * ves las próximas llegadas estimadas en vivo y puedes activar una notificación
 * que te avisa cuando una unidad se acerque a esa parada.
 */
public class LlegadasFragment extends Fragment {

    private Spinner spLinea, spEstacion, spSentido;
    private MaterialButton btnAvisar;
    private TextView vacio;
    private LlegadasAdapter adapter;

    private TextView afectTitulo;
    private RecyclerView rvAfect;
    private final AfectacionesAdapter afectAdapter = new AfectacionesAdapter();
    private long afectVer = -1;

    // Tabla de estado del servicio (Metrobús + Mexibús), alimentada por las afectaciones de AWS.
    private android.widget.LinearLayout llEstadoMetrobus, llEstadoMexibus;
    private View txtMexibusTitulo;
    private static final int VERDE_OK = 0xFF2E7D32;   // "Servicio regular"

    private final List<Estacion> estaciones = new ArrayList<>();
    private final List<String> sentidos = new ArrayList<>();
    private final List<Ruta> rutasLinea = new ArrayList<>();
    private int lineaSel = 0;   // 0 = sin línea (spinner en "Seleccionar")

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> permisoNotif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> arrancarAviso());

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override public void onData(List<UnidadReal> u) { if (isAdded()) refrescar(); }
                @Override public void onError(String m) { }
            });
            refrescarAfectaciones();
            handler.postDelayed(this, Red.intervalo(getContext(), Config.LLEGADA_POLL_MS));
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_llegadas, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        spLinea = view.findViewById(R.id.spinner_linea);
        spEstacion = view.findViewById(R.id.spinner_estacion);
        spSentido = view.findViewById(R.id.spinner_sentido);
        btnAvisar = view.findViewById(R.id.btn_avisar);
        vacio = view.findViewById(R.id.txt_llegadas_vacio);

        RecyclerView rv = view.findViewById(R.id.recycler_llegadas);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new LlegadasAdapter();
        rv.setAdapter(adapter);

        // Tabla de estado del servicio (arriba) + panel de elevadores/otras afectaciones (abajo)
        llEstadoMetrobus = view.findViewById(R.id.ll_estado_metrobus);
        llEstadoMexibus = view.findViewById(R.id.ll_estado_mexibus);
        txtMexibusTitulo = view.findViewById(R.id.txt_estado_mexibus_titulo);
        afectTitulo = view.findViewById(R.id.txt_afect_titulo);
        rvAfect = view.findViewById(R.id.recycler_afectaciones);
        rvAfect.setLayoutManager(new LinearLayoutManager(requireContext(),
                RecyclerView.HORIZONTAL, false));
        rvAfect.setAdapter(afectAdapter);
        refrescarAfectaciones();

        // Spinner de líneas 1..7
        List<String> lineas = new ArrayList<>();
        for (int i = 1; i <= 7; i++) lineas.add(getString(R.string.linea_formato, i));
        spLinea.setAdapter(simpleSel(lineas));
        spLinea.setOnItemSelectedListener(new Sel() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                lineaSel = pos;   // 0 = "Seleccionar"
                if (pos == 0) limpiarSeleccion();
                else poblarLinea(lineaSel);
                refrescar();
            }
        });

        spEstacion.setOnItemSelectedListener(new Sel() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                filtrarSentidos(estacionSel());
                refrescar(); actualizarBoton();
            }
        });
        spSentido.setOnItemSelectedListener(new Sel() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refrescar();
            }
        });

        btnAvisar.setOnClickListener(v -> {
            Estacion e = estacionSel();
            if (e != null && e.nombre.equals(LlegadaService.paradaSeguida)) {
                detenerAviso();
            } else {
                intentarAvisar();
            }
        });

        // arranca en "Seleccionar": estación y sentido vacíos hasta elegir línea
        spEstacion.setAdapter(simpleSel(new ArrayList<>()));
        spSentido.setAdapter(simpleSel(new ArrayList<>()));
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private void limpiarSeleccion() {
        rutasLinea.clear();
        estaciones.clear();
        spEstacion.setAdapter(simpleSel(new ArrayList<>()));
        sentidos.clear();
        spSentido.setAdapter(simpleSel(new ArrayList<>()));
        actualizarBoton();
    }

    private void poblarLinea(int linea) {
        Linea l = GtfsRepository.porNumero(requireContext(), linea);
        rutasLinea.clear();
        rutasLinea.addAll(RutasRepository.deLinea(linea));

        estaciones.clear();
        List<String> nombres = new ArrayList<>();
        if (l != null) {
            for (Estacion e : l.estaciones) { estaciones.add(e); nombres.add(e.nombre); }
        }
        spEstacion.setAdapter(simpleSel(nombres));

        filtrarSentidos(null);   // hasta elegir estación, sentido en "Seleccionar"
        actualizarBoton();
    }

    /**
     * Sentidos = destinos alcanzables DESDE la estación {@code e}: solo las rutas
     * cuyo tramo servido (entre su origen y su destino) incluye a la estación.
     * Así, p. ej., en Potrero no aparece un destino como "Buenavista" que solo
     * dan las unidades del tramo sur. Si un origen/destino no se puede ubicar en
     * el trazado, ese destino se incluye (mejor de más que ocultar uno válido).
     */
    private void filtrarSentidos(Estacion e) {
        if (e == null) {   // sin estación elegida: sentido en "Seleccionar"
            sentidos.clear();
            spSentido.setAdapter(simpleSel(new ArrayList<>()));
            return;
        }
        Linea l = GtfsRepository.porNumero(requireContext(), lineaSel);
        Set<String> dst = new LinkedHashSet<>();
        final double TOL = 200; // metros de tolerancia
        if (l != null && e != null) {
            double dE = l.distanciaEn(e.posicion);
            for (Ruta r : rutasLinea) {
                if (r.destino == null || r.destino.isEmpty()) continue;
                Double dO = distanciaDe(l, r.origen);
                Double dD = distanciaDe(l, r.destino);
                if (dO == null || dD == null) { dst.add(r.destino); continue; }
                double lo = Math.min(dO, dD) - TOL, hi = Math.max(dO, dD) + TOL;
                if (dE >= lo && dE <= hi && Math.abs(dE - dD) > TOL) dst.add(r.destino);
            }
        }
        if (dst.isEmpty() && l != null && !l.estaciones.isEmpty()) {
            dst.add(l.estaciones.get(0).nombre);
            dst.add(l.estaciones.get(l.estaciones.size() - 1).nombre);
        }
        sentidos.clear();
        sentidos.addAll(dst);
        spSentido.setAdapter(simpleSel(sentidos));
    }

    /** Distancia (m) de una estación (por nombre) a lo largo del trazado, o null. */
    private Double distanciaDe(Linea l, String nombre) {
        if (nombre == null || nombre.isEmpty()) return null;
        for (Estacion e : l.estaciones) {
            if (e.nombre != null && e.nombre.equalsIgnoreCase(nombre)) {
                return l.distanciaEn(e.posicion);
            }
        }
        return null;
    }

    private void refrescar() {
        if (!isAdded()) return;
        Estacion e = estacionSel();
        String sentido = sentidoSel();
        if (e == null) { mostrarVacio(getString(R.string.llegadas_elige)); return; }

        List<Llegadas.Prox> prox = Llegadas.proximas(requireContext(), lineaSel,
                e.posicion, sentido, RealtimeRepository.get().getUltimo(), false);
        adapter.set(prox);
        if (prox.isEmpty()) mostrarVacio(getString(R.string.llegadas_sin));
        else vacio.setVisibility(View.GONE);
    }

    private void mostrarVacio(String texto) {
        vacio.setText(texto);
        vacio.setVisibility(View.VISIBLE);
        adapter.set(new ArrayList<>());
    }

    /**
     * Refresca AMBAS secciones cuando cambian las afectaciones de AWS:
     * la tabla de estado por línea (arriba) y el panel de elevadores/otras (abajo).
     */
    private void refrescarAfectaciones() {
        if (!isAdded() || rvAfect == null) return;
        if (Manifestaciones.actualizado() == afectVer) return;   // sin cambios
        afectVer = Manifestaciones.actualizado();

        refrescarEstado();   // tabla de estado del servicio

        // Panel inferior: SOLO elevadores y mantenimiento/otras (el estado por línea va en la tabla).
        boolean elevadores = Perfil.muestraElevadores(requireContext());
        List<Manifestaciones.Afectacion> vis = new ArrayList<>();
        for (Manifestaciones.Afectacion a : Manifestaciones.lista()) {
            if (a.categoria == Manifestaciones.C_ESTADO) continue;   // estado → tabla de arriba
            if (a.elevador && !elevadores) continue;
            vis.add(a);
        }
        afectAdapter.set(vis);
        int v = vis.isEmpty() ? View.GONE : View.VISIBLE;
        afectTitulo.setVisibility(v);
        rvAfect.setVisibility(v);
    }

    /** Construye la tabla de estado: una fila por línea (Metrobús 1..7 y Mexibús ordinarias) con su situación. */
    private void refrescarEstado() {
        if (!isAdded() || llEstadoMetrobus == null) return;
        // Agrupa las afectaciones de ESTADO por número de línea (concatena si hay varias).
        java.util.Map<Integer, String> estado = new java.util.HashMap<>();
        for (Manifestaciones.Afectacion a : Manifestaciones.lista()) {
            if (a.categoria != Manifestaciones.C_ESTADO || a.lineaNum <= 0) continue;
            String txt = !a.estado.isEmpty() ? a.estado : a.lugar;
            String prev = estado.get(a.lineaNum);
            estado.put(a.lineaNum, prev == null ? txt : prev + " · " + txt);
        }
        // Metrobús L1..L7
        llEstadoMetrobus.removeAllViews();
        for (int i = 1; i <= 7; i++) {
            Linea l = GtfsRepository.porNumero(requireContext(), i);
            int color = l != null ? l.color : 0xFFC8103E;
            String nombre = l != null ? l.nombre : getString(R.string.linea_formato, i);
            llEstadoMetrobus.addView(filaEstado(String.valueOf(i), color, nombre, estado.get(i)));
        }
        // Mexibús: solo las ordinarias (numero 101..110), no exprés (12x) ni Mexicable (20x).
        // Respeta el ajuste "mostrar Mexibús": si está apagado, no se muestra su estado de servicio.
        llEstadoMexibus.removeAllViews();
        int mostradas = 0;
        if (Modos.mostrarMexibus(requireContext())) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                if (l.numero < 101 || l.numero > 110) continue;
                String et = estado.get(l.numero);
                llEstadoMexibus.addView(filaEstado(String.valueOf(l.numero - 100), l.color, l.nombre, et));
                mostradas++;
            }
        }
        int vis = mostradas > 0 ? View.VISIBLE : View.GONE;
        if (txtMexibusTitulo != null) txtMexibusTitulo.setVisibility(vis);
        llEstadoMexibus.setVisibility(vis);
    }

    /** Una fila de la tabla: badge de color con el número, nombre de la línea y su estado a la derecha. */
    private View filaEstado(String num, int color, String nombre, String afectacion) {
        float d = getResources().getDisplayMetrics().density;
        android.util.TypedValue tvp = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvp, true);
        int colTxt = ContextCompat.getColor(requireContext(), tvp.resourceId);

        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pv = Math.round(6 * d);
        row.setPadding(0, pv, 0, pv);

        TextView badge = new TextView(requireContext());
        int sz = Math.round(26 * d);
        badge.setLayoutParams(new android.widget.LinearLayout.LayoutParams(sz, sz));
        badge.setText(num);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(13f);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        badge.setBackground(g);
        row.addView(badge);

        TextView nom = new TextView(requireContext());
        android.widget.LinearLayout.LayoutParams np = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = Math.round(10 * d);
        nom.setLayoutParams(np);
        nom.setText(nombre);
        nom.setTextSize(14f);
        nom.setTextColor(colTxt);
        row.addView(nom);

        TextView est = new TextView(requireContext());
        boolean afect = afectacion != null && !afectacion.trim().isEmpty();
        est.setText(afect ? afectacion : getString(R.string.estado_regular));
        est.setTextSize(13f);
        est.setTextColor(afect ? 0xFFC8103E : VERDE_OK);
        est.setGravity(android.view.Gravity.END);
        est.setMaxLines(2);
        android.widget.LinearLayout.LayoutParams ep = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        ep.leftMargin = Math.round(8 * d);
        est.setLayoutParams(ep);
        row.addView(est);
        return row;
    }

    // ---- aviso (notificación) ----

    private void intentarAvisar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoNotif.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarAviso();
    }

    private void arrancarAviso() {
        Estacion e = estacionSel();
        if (!isAdded() || e == null) return;
        Intent i = new Intent(requireContext(), LlegadaService.class)
                .putExtra(LlegadaService.EXTRA_LINEA, lineaSel)
                .putExtra(LlegadaService.EXTRA_ESTACION, e.nombre)
                .putExtra(LlegadaService.EXTRA_LAT, e.posicion.latitude)
                .putExtra(LlegadaService.EXTRA_LON, e.posicion.longitude)
                .putExtra(LlegadaService.EXTRA_SENTIDO, sentidoSel());
        ContextCompat.startForegroundService(requireContext(), i);
        LlegadaService.paradaSeguida = e.nombre;
        actualizarBoton();
    }

    private void detenerAviso() {
        Intent i = new Intent(requireContext(), LlegadaService.class)
                .setAction(LlegadaService.ACCION_DETENER);
        requireContext().startService(i);
        LlegadaService.paradaSeguida = null;
        actualizarBoton();
    }

    private void actualizarBoton() {
        if (btnAvisar == null) return;
        Estacion e = estacionSel();
        boolean avisando = e != null && e.nombre.equals(LlegadaService.paradaSeguida);
        btnAvisar.setText(avisando ? R.string.llegada_dejar : R.string.llegada_avisar);
    }

    private Estacion estacionSel() {
        int pos = spEstacion.getSelectedItemPosition();   // 0 = "Seleccionar"
        return (pos >= 1 && pos - 1 < estaciones.size()) ? estaciones.get(pos - 1) : null;
    }

    private String sentidoSel() {
        int pos = spSentido.getSelectedItemPosition();   // 0 = "Seleccionar"
        return (pos >= 1 && pos - 1 < sentidos.size()) ? sentidos.get(pos - 1) : null;
    }

    private ArrayAdapter<String> simple(List<String> datos) {
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, datos);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /** Adaptador con "Seleccionar" como primer elemento. */
    private ArrayAdapter<String> simpleSel(List<String> datos) {
        List<String> d = new ArrayList<>();
        d.add(getString(R.string.seleccionar));
        d.addAll(datos);
        return simple(d);
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarBoton();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(poll);
        super.onDestroyView();
    }

    /** OnItemSelectedListener con onNothingSelected vacío. */
    private abstract static class Sel implements AdapterView.OnItemSelectedListener {
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
