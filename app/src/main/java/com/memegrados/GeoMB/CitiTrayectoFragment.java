package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Estilo CITI: "Mi trayecto" con el planificador de GeoMB (origen → destino, tramos, ver en mapa y
 * recorrido con voz), armado con las piezas visuales de la app de Indra. Conserva abajo el "Mi
 * trayecto" original: seguir una unidad por su número económico.
 */
public class CitiTrayectoFragment extends CitiPantalla {

    private static final String A_DESTINO = "destino", A_LINEA_D = "lineaD";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private View raiz;
    private CitiDatos.Est origen, destino;
    private List<CitiDatos.Est> estaciones;
    private List<String> textos;

    /** Con el destino ya puesto ("Cómo llegar aquí" de una estación). */
    public static CitiTrayectoFragment hacia(@Nullable CitiDatos.Est destino) {
        CitiTrayectoFragment f = new CitiTrayectoFragment();
        if (destino != null) {
            Bundle b = new Bundle();
            b.putString(A_DESTINO, destino.canon);
            b.putInt(A_LINEA_D, destino.linea);
            f.setArguments(b);
        }
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        raiz = inflater.inflate(R.layout.trayecto_citi, container, false);
        barra(raiz, R.string.citi_mi_trayecto);
        estaciones = CitiDatos.unicas(requireContext());
        textos = new ArrayList<>();
        for (CitiDatos.Est e : estaciones) textos.add(e.nombre + " (" + sistemaDe(e.linea) + ")");

        AutoCompleteTextView campoO = raiz.findViewById(R.id.citi_trayecto_origen);
        AutoCompleteTextView campoD = raiz.findViewById(R.id.citi_trayecto_destino);
        campoO.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, textos));
        campoD.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, textos));
        campoO.setOnItemClickListener((p, v, i, id) -> origen = deTexto((String) p.getItemAtPosition(i)));
        campoD.setOnItemClickListener((p, v, i, id) -> destino = deTexto((String) p.getItemAtPosition(i)));

        Bundle a = getArguments();
        if (a != null && a.getString(A_DESTINO) != null) {
            for (int i = 0; i < estaciones.size(); i++) {
                CitiDatos.Est e = estaciones.get(i);
                if (e.linea == a.getInt(A_LINEA_D) && e.canon.equals(a.getString(A_DESTINO))) {
                    destino = e;
                    campoD.setText(textos.get(i), false);
                    break;
                }
            }
        }

        raiz.findViewById(R.id.citi_trayecto_ubicacion).setOnClickListener(v -> usarUbicacion(campoO));
        raiz.findViewById(R.id.citi_trayecto_trazar).setOnClickListener(v -> trazar());
        raiz.findViewById(R.id.citi_trayecto_bus).setOnClickListener(v -> citi().dialogoMiTrayecto());
        raiz.findViewById(R.id.citi_trayecto_mapa).setOnClickListener(v -> citi().verTrayectoEnMapa());
        raiz.findViewById(R.id.citi_trayecto_recorrido).setOnClickListener(v -> alternarRecorrido());

        // Al volver a esta pantalla con un trayecto ya trazado, se muestra de nuevo.
        if (CitiActivity.trayecto != null) {
            try { mostrar(CitiActivity.trayecto); }
            catch (Throwable e) { CitiActivity.trayecto = null; }
        }
        return raiz;
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarBotonRecorrido();
    }

    @Override
    public void onDestroyView() {
        raiz = null;
        super.onDestroyView();
    }

    private CitiDatos.Est deTexto(String t) {
        int k = textos.indexOf(t);
        return k >= 0 ? estaciones.get(k) : null;
    }

    /** "Mi ubicación": la estación más cercana como origen. */
    @SuppressLint("MissingPermission")
    private void usarUbicacion(AutoCompleteTextView campo) {
        boolean ok = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!ok) { citi().conUbicacion(() -> { if (isAdded()) usarUbicacion(campo); }); return; }
        citi().mostrarDescargando(true);
        LocationServices.getFusedLocationProviderClient(requireActivity()).getLastLocation()
                .addOnCompleteListener(t -> {
                    if (!isAdded()) return;
                    citi().mostrarDescargando(false);
                    android.location.Location loc = t.isSuccessful() ? t.getResult() : null;
                    List<Object[]> c = loc == null ? null : CitiDatos.cercanas(requireContext(),
                            new LatLng(loc.getLatitude(), loc.getLongitude()), 1);
                    if (c == null || c.isEmpty()) { citi().aviso(getString(R.string.citi_sin_ubicacion)); return; }
                    // cercanas() arma sus propias instancias: se busca la equivalente en la lista del
                    // autocompletar (antes indexOf daba -1 y la app se cerraba).
                    CitiDatos.Est cerca = (CitiDatos.Est) c.get(0)[0];
                    int k = -1;
                    for (int i = 0; i < estaciones.size(); i++) {
                        CitiDatos.Est e = estaciones.get(i);
                        if (e.linea == cerca.linea && e.canon.equals(cerca.canon)) { k = i; break; }
                    }
                    origen = k >= 0 ? estaciones.get(k) : cerca;
                    campo.setText(k >= 0 ? textos.get(k) : cerca.nombre, false);
                });
    }

    /** Calcula en segundo plano con el planificador de GeoMB (A*, exprés, afectaciones, horarios). */
    private void trazar() {
        View foco = requireActivity().getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && foco != null) imm.hideSoftInputFromWindow(foco.getWindowToken(), 0);
        if (origen == null || destino == null) { citi().aviso(getString(R.string.citi_elige_estaciones)); return; }
        final CitiDatos.Est o = origen, d = destino;
        // Igual que MapFragment.trazarResueltoRuta(): primer cálculo con la preferencia sembrada por
        // el servicio de origen/destino; si la ruta usa una troncal Mexibús con exprés, se pregunta
        // Ordinario/Exprés por CADA una y se recalcula con esa elección.
        java.util.Map<Integer, Boolean> pref = new java.util.HashMap<>();
        sembrarPref(pref, o.linea);
        sembrarPref(pref, d.linea);
        MapFragment.forzarOrdinarioSiExpresBloqueadoRuta(pref);
        calcular(o, d, pref, true);
    }

    private static void sembrarPref(java.util.Map<Integer, Boolean> pref, int linea) {
        if (linea < 101 || linea > 124 || (linea >= 111 && linea <= 119)) return;
        pref.put(Servicios.base(linea), linea >= 121);
    }

    /** {@code preguntar}: tras calcular, pregunta el servicio de cada troncal con exprés (primer
     *  cálculo); si no, muestra el resultado (cálculo final con la preferencia ya elegida). */
    private void calcular(CitiDatos.Est o, CitiDatos.Est d, java.util.Map<Integer, Boolean> pref, boolean preguntar) {
        final android.content.Context app = requireContext().getApplicationContext();
        citi().mostrarDescargando(true);
        EXEC.execute(() -> {
            Planificador.Ruta r;
            try { r = Planificador.calcular(app, o.canon, d.canon, o.linea, d.linea, pref); }
            catch (Throwable e) {
                r = null;
                Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "CitiTrayecto.calcular", String.valueOf(e));
            }
            final Planificador.Ruta ruta = r;
            main.post(() -> {
                if (!isAdded()) return;
                citi().mostrarDescargando(false);
                if (ruta == null || ruta.pasos.isEmpty()) {
                    citi().aviso(getString(R.string.ruta_sin_ruta, d.nombre));
                    return;
                }
                // Igual que MapFragment.calcularRutaAsync: un fallo al pintar el resultado no debe
                // cerrar la app; se avisa y se registra.
                try {
                    if (preguntar) {
                        java.util.List<Integer> bases = MapFragment.basesTroncalesConExpressRuta(ruta);
                        if (!bases.isEmpty()) {
                            java.util.Map<Integer, Boolean> elegido = new java.util.HashMap<>();
                            MapFragment.forzarOrdinarioSiExpresBloqueadoRuta(elegido);
                            preguntarServicio(o, d, bases, elegido, 0);
                            return;
                        }
                    }
                    CitiActivity.trayecto = ruta;
                    mostrar(ruta);
                } catch (Throwable e) {
                    Telemetria.registrarError(app, Telemetria.ERR_EXCEPCION, "CitiTrayecto.mostrar", String.valueOf(e));
                    citi().aviso(getString(R.string.ruta_sin_ruta, d.nombre));
                }
            });
        });
    }

    /** Pregunta Ordinario/Exprés por cada base troncal, en orden; al terminar recalcula y muestra.
     *  "Cancelar" deja la búsqueda sin trazar. */
    private void preguntarServicio(CitiDatos.Est o, CitiDatos.Est d, java.util.List<Integer> bases,
                                   java.util.Map<Integer, Boolean> pref, int idx) {
        if (!isAdded()) return;
        if (idx >= bases.size()) { calcular(o, d, pref, false); return; }
        int base = bases.get(idx);
        String linea = CitiDatos.etiquetaSistema(base);
        List<CitiActivity.Opcion> op = new ArrayList<>();
        op.add(new CitiActivity.Opcion(linea + " " + getString(R.string.servicio_ordinario), () -> {
            if (!isAdded()) return;
            citi().cerrarMenu();
            pref.put(base, false);
            preguntarServicio(o, d, bases, pref, idx + 1);
        }));
        op.add(new CitiActivity.Opcion(linea + " " + getString(R.string.servicio_express), () -> {
            if (!isAdded()) return;
            citi().cerrarMenu();
            pref.put(base, true);
            preguntarServicio(o, d, bases, pref, idx + 1);
        }));
        citi().abrirMenu(op, true);
    }

    /** Resumen, aviso de ruta alterna y un renglón por tramo (item_linea: cuadro de ruta + texto). */
    private void mostrar(Planificador.Ruta r) {
        if (raiz == null) return;
        ((TextView) raiz.findViewById(R.id.citi_trayecto_resumen))
                .setText(getString(R.string.ruta_resumen, r.paradas, r.transbordos, r.minutos));
        TextView aviso = raiz.findViewById(R.id.citi_trayecto_aviso);
        boolean alterna = MapFragment.rutaTieneAfectacionPlan(r);
        aviso.setVisibility(alterna ? View.VISIBLE : View.GONE);
        if (alterna) aviso.setText(R.string.ruta_alterna);

        LinearLayout tramos = raiz.findViewById(R.id.citi_trayecto_tramos);
        tramos.removeAllViews();
        boolean conDireccion = r.instrucciones != null && r.instrucciones.size() == r.pasos.size();
        for (int i = 0; i < r.pasos.size(); i++) {
            Planificador.Paso p = r.pasos.get(i);
            View fila = getLayoutInflater().inflate(R.layout.item_linea_citi, tramos, false);
            TextView cod = fila.findViewById(R.id.citi_linea_codigo);
            cod.setBackground(cuadroRuta(p.linea));
            cod.setText(CitiDatos.etiqueta(p.linea));
            String de = Planificador.nombreMostrar(requireContext(), p.origen, p.linea);
            String a = Planificador.nombreMostrar(requireContext(), p.destino, p.linea);
            String terminal = conDireccion ? r.instrucciones.get(i).terminal : null;
            String txt = terminal != null
                    ? getString(R.string.citi_tramo, de, a, p.paradas,
                        Planificador.nombreMostrar(requireContext(), terminal, p.linea))
                    : getString(R.string.citi_tramo_sin_dir, de, a, p.paradas);
            ((TextView) fila.findViewById(R.id.citi_linea_nombre)).setText(txt);
            if (i > 0) {   // divisor entre tramos, como el de las listas
                View div = new View(requireContext());
                div.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.citi_lista_divisor));
                tramos.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, (int) (0.67f * getResources().getDisplayMetrics().density))));
            }
            tramos.addView(fila);
        }
        raiz.findViewById(R.id.citi_trayecto_resultado).setVisibility(View.VISIBLE);
        actualizarBotonRecorrido();
    }

    private void alternarRecorrido() {
        if (RecorridoService.activo) {
            RecorridoService.detener(requireContext());
            actualizarBotonRecorrido();
            return;
        }
        Planificador.Ruta r = CitiActivity.trayecto;
        if (r == null || r.secuencia.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            citi().aviso(getString(R.string.recorrido_sin_permiso));
            return;
        }
        // Igual que MapFragment.arrancarRecorrido(): avisos con voz (Ximena en estilo CITI) y el
        // mapa siguiendo al usuario sobre el trayecto.
        RecorridoService.servicioTexto = null;
        List<String> terms = new ArrayList<>();
        if (r.instrucciones != null) for (Planificador.Instruccion ins : r.instrucciones) terms.add(ins.terminal);
        RecorridoService.terminalesTramo = terms;
        RecorridoService.iniciar(requireContext(), r.secuencia, r.secuencia.get(r.secuencia.size() - 1).nombre);
        citi().verTrayectoEnMapa();
    }

    private void actualizarBotonRecorrido() {
        if (raiz == null) return;
        ((TextView) raiz.findViewById(R.id.citi_trayecto_recorrido)).setText(
                RecorridoService.activo ? R.string.recorrido_detener : R.string.recorrido_iniciar);
    }
}
