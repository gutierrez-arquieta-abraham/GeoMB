package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla dedicada de planeación de viaje: en su propio mapa traza (ilumina) la
 * ruta óptima entre dos estaciones, con paradas, transbordos, tiempo estimado y
 * aviso cuando la unidad de tu primer tramo está por llegar a tu estación de origen.
 */
public class PlanificadorFragment extends Fragment {

    public static final String ARG_DESTINO = "destino";

    private GoogleMap mapa;
    private FusedLocationProviderClient loc;
    private EditText inOrigen, inDestino;
    private View panelResultado, panelEstaciones, panelOrigen;
    private TextView resResumen, resPasos, resAviso, resEstado;
    private MaterialButton btnRecorrido;
    private RecyclerView rvEstaciones;
    private final EstacionRutaAdapter sliderAdapter = new EstacionRutaAdapter();

    private final List<Polyline> trazo = new ArrayList<>();
    private Planificador.Ruta rutaActiva;
    // Ruta recordada entre módulos: al volver al planificador se re-traza sola.
    private static String ultOrigen, ultDestino;
    // Nombre CANÓNICO (con "MXB " si es Mexibús) de cada campo; el campo MUESTRA el nombre sin prefijo.
    private String origenCanon, destinoCanon;
    private int origenLinea, destinoLinea;   // línea FIJADA por desambiguación (0 = cualquiera)
    private LatLng origenPos;
    private boolean avisoUnidad = false;
    private boolean autoTrazar = false;
    private boolean recorrido = false;
    private final java.util.Map<String, com.google.android.gms.maps.model.Marker> marcadoresUnidad = new java.util.HashMap<>();
    private final java.util.Map<String, Long> animToken = new java.util.HashMap<>();   // anima el desplazamiento de cada unidad
    private long animSeq = 0;
    private static final double RADIO_ESTACION_M = 50;   // metros a la redonda del trazo para mostrar unidades
    private static final double PASO_TRAZO_M = 12;       // separación de los puntos finos del trazo (progreso suave)
    private com.google.android.gms.maps.model.Marker mkUsuario;   // puntero de ubicación (icono norte, pegado al trazo)
    private Polyline progresoLine;                 // overlay que opaca lo ya recorrido
    private java.util.List<LatLng> rutaPuntos;     // todos los puntos del trazo en orden (para el progreso)
    private java.util.List<LatLng> anclasZona;     // puntos densificados del trazo (filtro de unidades + puntero)
    private long ultimaManifest = 0;   // versión de afectaciones aplicada a la ruta mostrada

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
                @Override public void onData(List<UnidadReal> unidades) { revisarUnidad(unidades); dibujarUnidades(unidades); }
                @Override public void onError(String m) {}
            });
            if (recorrido) refrescarRecorridoUI();
            // La ruta NO se re-traza sola (antes se re-ruteaba al cambiar afectaciones y eso podía
            // cambiar el trazo, p. ej. mandar toda la vuelta del circuito L2A). Solo se retraza cuando
            // el usuario toca el botón "Trazar". Aquí únicamente se refresca el aviso de afectación.
            if (Manifestaciones.actualizado() != ultimaManifest) {
                ultimaManifest = Manifestaciones.actualizado();
                if (rutaActiva != null && resAviso != null) {
                    boolean afect = Manifestaciones.hay();
                    resAviso.setVisibility(afect ? View.VISIBLE : View.GONE);
                    if (afect) resAviso.setText(getString(R.string.ruta_alterna));
                }
            }
            handler.postDelayed(this, Red.intervalo(getContext(), Modos.mapaRefrescoMs(getContext())));
        }
    };

    // Refresca el trazo de progreso y el puntero cada segundo durante el recorrido (sin red).
    private static final long TICK_RECORRIDO_MS = 1000L;
    private final Runnable tickRecorrido = new Runnable() {
        @Override public void run() {
            if (recorrido) refrescarRecorridoUI();
            handler.postDelayed(this, TICK_RECORRIDO_MS);
        }
    };

    public static PlanificadorFragment nuevo(String destino) {
        PlanificadorFragment f = new PlanificadorFragment();
        Bundle b = new Bundle();
        b.putString(ARG_DESTINO, destino);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_planificador, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loc = LocationServices.getFusedLocationProviderClient(requireContext());

        inOrigen = view.findViewById(R.id.input_origen);
        inDestino = view.findViewById(R.id.input_destino);
        panelResultado = view.findViewById(R.id.panel_resultado);
        panelEstaciones = view.findViewById(R.id.panel_estaciones);
        panelOrigen = view.findViewById(R.id.panel_origen_destino);
        resResumen = view.findViewById(R.id.res_resumen);
        resPasos = view.findViewById(R.id.res_pasos);
        resAviso = view.findViewById(R.id.res_aviso);
        resEstado = view.findViewById(R.id.res_estado);
        btnRecorrido = view.findViewById(R.id.btn_recorrido);

        rvEstaciones = view.findViewById(R.id.rv_estaciones);
        rvEstaciones.setLayoutManager(new LinearLayoutManager(requireContext(),
                RecyclerView.HORIZONTAL, false));
        rvEstaciones.setAdapter(sliderAdapter);

        String destino = getArguments() != null ? getArguments().getString(ARG_DESTINO) : null;
        if (destino != null) {
            // El usuario pidió expresamente una ruta ("ver ruta a X"): esa sí se traza sola.
            setDestino(destino); autoTrazar = true;
        } else if (ultDestino != null) {
            // Al volver al módulo se RECUERDAN origen y destino en los campos, pero NO se re-traza:
            // la ruta se vuelve a trazar solo cuando el usuario toca el botón "Trazar".
            if (ultOrigen != null) setOrigen(ultOrigen);
            setDestino(ultDestino);
        }

        view.findViewById(R.id.btn_trazar).setOnClickListener(v -> trazar());
        view.findViewById(R.id.btn_location).setOnClickListener(v -> usarUbicacionOrigen());
        view.findViewById(R.id.btn_intercambiar).setOnClickListener(v -> {
            String oc = origenCanon, dc = destinoCanon;               // intercambia también los canónicos
            CharSequence o = inOrigen.getText(), d = inDestino.getText();
            if (dc != null) setOrigen(dc); else inOrigen.setText(d);
            if (oc != null) setDestino(oc); else inDestino.setText(o);
            if (o.length() > 0 && d.length() > 0) trazar();
        });
        btnRecorrido.setOnClickListener(v -> alternarRecorrido());
        inDestino.setOnEditorActionListener((v, id, e) -> { trazar(); return true; });

        view.findViewById(R.id.btn_zoom_in).setOnClickListener(v -> {
            if (mapa != null) mapa.animateCamera(CameraUpdateFactory.zoomIn());
        });
        view.findViewById(R.id.btn_zoom_out).setOnClickListener(v -> {
            if (mapa != null) mapa.animateCamera(CameraUpdateFactory.zoomOut());
        });
        view.findViewById(R.id.btn_norte).setOnClickListener(v -> {
            if (mapa == null) return;
            CameraPosition c = new CameraPosition.Builder(mapa.getCameraPosition()).bearing(0).tilt(0).build();
            mapa.animateCamera(CameraUpdateFactory.newCameraPosition(c));
        });
        view.findViewById(R.id.btn_ubicacion).setOnClickListener(v -> centrarEnUbicacion());
        actualizarLogoIconos(Modos.iconosNuevos(requireContext()));   // logo inicial del botón según el modo
        view.findViewById(R.id.btn_iconos).setOnClickListener(v -> {
            boolean nuevos = !Modos.iconosNuevos(requireContext());
            Modos.setIconosNuevos(requireContext(), nuevos);
            actualizarLogoIconos(nuevos);
            if (rutaActiva != null && destinoCanon != null) dibujar(rutaActiva, destinoCanon);   // redibuja la ruta con el nuevo estilo
        });

        SupportMapFragment mf = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_ruta_container);
        if (mf == null) {
            mf = SupportMapFragment.newInstance();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.map_ruta_container, mf).commit();
        }
        mf.getMapAsync(m -> {
            mapa = m;
            mapa.getUiSettings().setMapToolbarEnabled(false);
            mapa.getUiSettings().setZoomControlsEnabled(false);
            mapa.getUiSettings().setMyLocationButtonEnabled(false); // usamos btn_ubicacion del panel
            mapa.getUiSettings().setCompassEnabled(false);        // reemplazada por btn_norte (brújula propia)
            final View bn = getView() != null ? getView().findViewById(R.id.btn_norte) : null;
            if (bn != null) mapa.setOnCameraMoveListener(() ->
                    bn.setRotation(-mapa.getCameraPosition().bearing));
            // Ventana de información con la misma tipografía que el mapa general.
            mapa.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
                @Override public View getInfoWindow(com.google.android.gms.maps.model.Marker m) { return null; }
                @Override public View getInfoContents(com.google.android.gms.maps.model.Marker m) {
                    View v = getLayoutInflater().inflate(R.layout.map_info_window, null, false);
                    TextView t = v.findViewById(R.id.info_titulo);
                    t.setText(m.getTitle());
                    Tipografia.aplicar(t);
                    TextView s = v.findViewById(R.id.info_sub);
                    String snip = m.getSnippet();
                    if (snip != null && !snip.isEmpty()) { s.setText(snip); Tipografia.aplicar(s); s.setVisibility(View.VISIBLE); }
                    else s.setVisibility(View.GONE);
                    return v;
                }
            });
            if (esNoche()) {   // sigue el tema del dispositivo
                mapa.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark));
            }
            mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(19.41, -99.14), 11f));
            rellenarOrigen();
        });
    }

    /** Botón "mi ubicación": fija el origen en la estación más cercana y traza si hay destino. */
    @SuppressLint("MissingPermission")
    private void usarUbicacionOrigen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.recorrido_sin_permiso), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), getString(R.string.recorrido_ubicando), Toast.LENGTH_SHORT).show();
        loc.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(l -> {
                    if (!isAdded() || l == null) return;
                    Estacion e = Planificador.masCercana(requireContext(),
                            new LatLng(l.getLatitude(), l.getLongitude()));
                    if (e == null) return;
                    setOrigen(e.nombre);
                    if (inDestino.getText().length() > 0) trazar();
                });
    }

    /** Botón de localización del panel: centra el mapa en la ubicación actual del usuario. */
    @SuppressLint("MissingPermission")
    private void centrarEnUbicacion() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.recorrido_sin_permiso), Toast.LENGTH_LONG).show();
            return;
        }
        loc.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(l -> {
                    if (!isAdded() || l == null || mapa == null) return;
                    mapa.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(l.getLatitude(), l.getLongitude()), 15f));
                });
    }

    /** Rellena el origen con la estación más cercana a la ubicación del usuario. */
    @SuppressLint("MissingPermission")
    private void rellenarOrigen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) { if (autoTrazar) trazar(); return; }
        loc.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(l -> {
                    if (!isAdded()) return;
                    if (l != null) {
                        Estacion e = Planificador.masCercana(requireContext(),
                                new LatLng(l.getLatitude(), l.getLongitude()));
                        if (e != null && inOrigen.getText().length() == 0) setOrigen(e.nombre);
                    }
                    if (autoTrazar) { autoTrazar = false; trazar(); }
                })
                .addOnFailureListener(e -> { if (autoTrazar) { autoTrazar = false; trazar(); } });
    }

    /** Muestra el nombre SIN prefijo MXB en el campo, pero recuerda el canónico para el ruteo. */
    private void setOrigen(String canon) { origenCanon = canon; origenLinea = 0; inOrigen.setText(Planificador.sinMxb(canon)); }
    private void setDestino(String canon) { destinoCanon = canon; destinoLinea = 0; inDestino.setText(Planificador.sinMxb(canon)); }

    /** Resuelve el texto del campo a un nombre canónico: si no se editó, usa el canónico recordado. */
    private String resolver(android.widget.EditText campo, String canon) {
        String txt = campo.getText().toString().trim();
        if (canon != null && Planificador.norm(txt).equals(Planificador.norm(Planificador.sinMxb(canon))))
            return canon;
        return Planificador.estacionParecida(requireContext(), txt);
    }

    /** Callback de desambiguación: nombre canónico (o null) + línea fijada (0 = cualquiera). */
    private interface ResueltoCb { void run(String nombre, int linea); }

    private void trazar() {
        if (mapa == null) return;
        // Desambigua origen y luego destino (puede mostrar carta/toast) antes de calcular la ruta.
        desambiguar(inOrigen, origenCanon, origenLinea, (o, lo) ->
                desambiguar(inDestino, destinoCanon, destinoLinea, (d, ld) ->
                        trazarResuelto(o, lo, d, ld)));
    }

    private void trazarResuelto(String origen, int lineaO, String destino, int lineaD) {
        if (destino == null) {
            Toast.makeText(requireContext(), getString(R.string.estacion_no_encontrada), Toast.LENGTH_SHORT).show();
            return;
        }
        if (origen == null) { origen = destino; lineaO = lineaD; }   // sin origen válido: se marcará "ya estás cerca"

        ultimaManifest = Manifestaciones.actualizado();
        Planificador.Ruta r = Planificador.calcular(requireContext(), origen, destino, lineaO, lineaD);
        if (r == null) {
            Toast.makeText(requireContext(), mensajeFallo(destino), Toast.LENGTH_LONG).show();
            return;
        }
        // nombres canónicos ya resueltos (el campo se muestra con nº de línea si estaba fijada)
        origenCanon = origen; origenLinea = lineaO;
        inOrigen.setText(lineaO == 0 ? Planificador.sinMxb(origen) : Planificador.nombreMostrar(requireContext(), origen, lineaO));
        destinoCanon = destino; destinoLinea = lineaD;
        inDestino.setText(lineaD == 0 ? Planificador.sinMxb(destino) : Planificador.nombreMostrar(requireContext(), destino, lineaD));
        ultOrigen = origen; ultDestino = destino;   // recuerda la ruta entre módulos
        rutaActiva = r;
        avisoUnidad = false;
        origenPos = r.pasos.isEmpty() ? null : r.pasos.get(0).puntos.get(0);
        dibujar(r, destino);
    }

    /**
     * Resuelve el campo a (nombre, línea). Si la estación es homónima de otra:
     *  · en otro SISTEMA (Metrobús vs Mexibús) y el Mexibús está visible → carta para elegir sistema;
     *  · en otra LÍNEA del Mexibús (sin ser correspondencia) → toast "Falta especificar línea" y toma
     *    la línea más baja por defecto (p. ej. "Las Américas" → L1).
     */
    private static int sistema(int n) { return n >= 200 ? 2 : (n >= 100 ? 1 : 0); }   // 0 Metrobús, 1 Mexibús, 2 Mexicable

    /** Reduce los candidatos de un sistema a una opción por LÍNEA BASE (la de menor número). */
    private static java.util.List<Planificador.Match> porLineaBase(java.util.List<Planificador.Match> cs, int sis) {
        java.util.LinkedHashMap<Integer, Planificador.Match> m = new java.util.LinkedHashMap<>();
        for (Planificador.Match c : cs) {
            if (sistema(c.linea) != sis) continue;
            int b = Servicios.base(c.linea);
            Planificador.Match prev = m.get(b);
            if (prev == null || c.linea < prev.linea) m.put(b, c);
        }
        return new java.util.ArrayList<>(m.values());
    }

    private void desambiguar(EditText campo, String canonRec, int lineaRec, ResueltoCb cb) {
        String canon = resolver(campo, canonRec);
        if (canon == null) { cb.run(null, 0); return; }
        if (lineaRec != 0 && canon.equals(canonRec)) { cb.run(canon, lineaRec); return; }   // ya fijada y sin cambios

        java.util.List<Planificador.Match> cs = Planificador.candidatos(requireContext(), canon);
        java.util.List<Planificador.Match> metro = porLineaBase(cs, 0);
        java.util.List<Planificador.Match> mxb = porLineaBase(cs, 1);
        java.util.List<Planificador.Match> mxc = porLineaBase(cs, 2);
        if (!Modos.mostrarMexibus(requireContext())) { mxb.clear(); mxc.clear(); }   // sin Mexibús visible no se ofrecen

        int sistemas = (metro.isEmpty() ? 0 : 1) + (mxb.isEmpty() ? 0 : 1) + (mxc.isEmpty() ? 0 : 1);
        if (sistemas == 0) { cb.run(canon, 0); return; }
        if (sistemas == 1) {                       // un solo sistema
            java.util.List<Planificador.Match> uni = !metro.isEmpty() ? metro : (!mxb.isEmpty() ? mxb : mxc);
            uni = colapsarCoubicadas(uni);         // líneas co-ubicadas (misma estación física) = una sola opción
            if (uni.size() <= 1) { fijar(campo, uni.get(0), cb); return; }
            elegirLinea(campo, uni, cb); return;   // varias líneas del mismo sistema en sitios distintos (p. ej. Mexibús L1/L2)
        }
        cartaSistema(campo, canon, metro, mxb, mxc, cb);   // varios sistemas: elegir sistema (y línea si Mexibús)
    }

    /** Opción de una carta flotante: logo (recurso o bitmap) + título + acción. */
    private static final class Opcion {
        final int iconoRes; final Bitmap iconoBmp; final String titulo; final Runnable accion;
        Opcion(int res, Bitmap bmp, String t, Runnable a) { iconoRes = res; iconoBmp = bmp; titulo = t; accion = a; }
    }

    /** Muestra una carta flotante con diseño propio: cada fila lleva su logo y título. */
    private void mostrarCarta(String titulo, java.util.List<Opcion> ops) {
        android.widget.LinearLayout cont = new android.widget.LinearLayout(requireContext());
        cont.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        cont.setPadding(0, pad, 0, pad);
        AlertDialog dlg = new AlertDialog.Builder(requireContext())
                .setTitle(titulo).setView(cont).setCancelable(true).create();
        for (Opcion o : ops) {
            View row = getLayoutInflater().inflate(R.layout.dialog_opcion_row, cont, false);
            android.widget.ImageView iv = row.findViewById(R.id.op_icono);
            if (o.iconoBmp != null) iv.setImageBitmap(o.iconoBmp);
            else if (o.iconoRes != 0) iv.setImageResource(o.iconoRes);
            ((TextView) row.findViewById(R.id.op_titulo)).setText(o.titulo);
            row.setOnClickListener(v -> { dlg.dismiss(); o.accion.run(); });
            cont.addView(row);
        }
        dlg.show();
    }

    /** Color de una línea (para el badge); si no se encuentra, gris. */
    private int colorLinea(int linea) {
        Linea l = GtfsRepository.porNumero(requireContext(), linea);
        return l != null ? l.color : 0xFF757575;
    }

    /** Badge circular del color de la línea con su número/etiqueta en blanco. */
    private Bitmap badgeLinea(int color, String texto) {
        int px = Math.round(40 * getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); c.drawCircle(px / 2f, px / 2f, px * 0.46f, p);
        p.setColor(0xFFFFFFFF); p.setFakeBoldText(true);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTextSize(px * (texto.length() > 1 ? 0.34f : 0.5f));
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(texto, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }

    /** Carta para elegir SISTEMA (Metrobús/Mexibús/Mexicable), con sus logos. */
    private void cartaSistema(EditText campo, String canon, java.util.List<Planificador.Match> metro,
                              java.util.List<Planificador.Match> mxb, java.util.List<Planificador.Match> mxc, ResueltoCb cb) {
        java.util.List<Opcion> ops = new java.util.ArrayList<>();
        if (!metro.isEmpty())
            ops.add(new Opcion(R.drawable.logo_mb, null, getString(R.string.desamb_sist_metrobus), () -> fijar(campo, metro.get(0), cb)));
        if (!mxb.isEmpty())
            ops.add(new Opcion(R.drawable.ic_mexibus_nuevo, null, getString(R.string.desamb_sist_mexibus),
                    () -> { if (mxb.size() == 1) fijar(campo, mxb.get(0), cb); else elegirLinea(campo, mxb, cb); }));
        if (!mxc.isEmpty())
            ops.add(new Opcion(R.drawable.mexicable_01_0, null, getString(R.string.desamb_sist_mexicable), () -> fijar(campo, mxc.get(0), cb)));
        mostrarCarta(getString(R.string.desamb_sistema_titulo, Planificador.sinMxb(canon)), ops);
    }

    /** Carta para elegir LÍNEA dentro de un sistema (Mexibús L1/L2/…), con badges de color. */
    private void elegirLinea(EditText campo, java.util.List<Planificador.Match> ops, ResueltoCb cb) {
        java.util.List<Opcion> op = new java.util.ArrayList<>();
        for (Planificador.Match m : ops) {
            String et = Planificador.etiquetaLineaCortaPub(m.linea);
            op.add(new Opcion(0, badgeLinea(colorLinea(m.linea), et),
                    getString(R.string.desamb_linea_n, et), () -> fijar(campo, m, cb)));
        }
        mostrarCarta(getString(R.string.desamb_elige_linea), op);
    }

    /** Une opciones que están a ≤400 m (misma estación física servida por varias líneas), dejando la de menor número. */
    private static java.util.List<Planificador.Match> colapsarCoubicadas(java.util.List<Planificador.Match> in) {
        java.util.List<Planificador.Match> out = new java.util.ArrayList<>();
        for (Planificador.Match m : in) {
            boolean fund = false;
            for (int i = 0; i < out.size(); i++)
                if (Linea.distancia(m.pos, out.get(i).pos) <= 400) {
                    if (m.linea < out.get(i).linea) out.set(i, m);
                    fund = true; break;
                }
            if (!fund) out.add(m);
        }
        return out;
    }

    /** Fija la opción elegida: Metrobús no requiere pin (0); Mexibús/Mexicable fijan su línea. */
    private void fijar(EditText campo, Planificador.Match m, ResueltoCb cb) {
        int pin = m.linea < 100 ? 0 : m.linea;
        campo.setText(Planificador.nombreMostrar(requireContext(), m.nombre, m.linea));
        cb.run(m.nombre, pin);
    }

    /** Palabra para el cambio de servicio: Metrobús↔Metrobús=Transbordo, Mexibús/Mexicable entre sí=Correspondencia, mixto=Conexión. */
    private static int verboTransferencia(int lineaAnt, int lineaAct) {
        boolean antMetro = lineaAnt < 100;   // <100 = Metrobús (0/mixtas también son Metrobús)
        boolean actMetro = lineaAct < 100;
        if (antMetro && actMetro) return R.string.transf_transbordo;
        if (!antMetro && !actMetro) return R.string.transf_correspondencia;
        return R.string.transf_conexion;
    }

    /**
     * Mensaje del toast cuando no se pudo trazar. Distingue "estación fuera de servicio" (con su
     * motivo: bloqueo/manifestación o mantenimiento) de "línea partida" o el genérico sin ruta.
     */
    private String mensajeFallo(String destino) {
        if (Planificador.motivoFallo == Planificador.MOTIVO_ESTACION_CERRADA) {
            String est = Planificador.estacionCerrada != null ? Planificador.estacionCerrada : destino;
            int cat = Manifestaciones.razonCierre(Planificador.norm(est));
            int motivo = cat == Manifestaciones.C_MANTENIMIENTO ? R.string.ruta_motivo_mantenimiento
                    : cat == Manifestaciones.C_ESTADO ? R.string.ruta_motivo_bloqueo
                    : R.string.ruta_motivo_afectacion;
            return getString(R.string.ruta_estacion_cerrada, est, getString(motivo));
        }
        if (Planificador.motivoFallo == Planificador.MOTIVO_SIN_RUTA && Manifestaciones.hay()) {
            return getString(R.string.ruta_corte_bloqueo);
        }
        return getString(R.string.ruta_sin_ruta, destino);
    }

    private void dibujar(Planificador.Ruta r, String destino) {
        for (Polyline p : trazo) p.remove();
        trazo.clear();
        mapa.clear();
        marcadoresUnidad.clear();               // mapa.clear() ya los quitó
        mkUsuario = null; progresoLine = null;
        rutaPuntos = new java.util.ArrayList<>();

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (Planificador.Paso p : r.pasos) {
            trazo.add(mapa.addPolyline(new PolylineOptions()
                    .addAll(p.puntos).color(p.color).width(18f).zIndex(5f)));
            rutaPuntos.addAll(p.puntos);
            for (LatLng pt : p.puntos) bounds.include(pt);
        }
        anclasZona = densificar(rutaPuntos, PASO_TRAZO_M);   // puntos finos: filtro de unidades + progreso suave
        // conector entre tramos (transbordo o cruce de ruta mixta) para que no quede hueco
        for (int k = 1; k < r.pasos.size(); k++) {
            List<LatLng> ant = r.pasos.get(k - 1).puntos, act = r.pasos.get(k).puntos;
            if (ant.isEmpty() || act.isEmpty()) continue;
            trazo.add(mapa.addPolyline(new PolylineOptions()
                    .add(ant.get(ant.size() - 1)).add(act.get(0))
                    .color(r.pasos.get(k).color).width(12f).zIndex(4f)));
        }
        // logos (pictogramas) de las estaciones de la ruta: actúan como marcadores (sin punteros)
        for (int i = 0; i < r.secuencia.size(); i++) {
            Planificador.Parada p = r.secuencia.get(i);
            BitmapDescriptor ic = bitmapEstacion(p.icono, p.color);
            if (ic == null) continue;
            LatLng pos = p.pos;
            String titulo = Planificador.nombreMostrar(requireContext(), p.nombre, p.linea);
            // Indios Verdes de Mexibús L4: el ASCENSO (donde cae el icono del L4 Express) es el mismo
            // andén para ordinario y exprés en ambos sentidos. Se marca ahí (andén central), no en la
            // plataforma sur; La Raza queda solo para descenso exprés / ascenso ordinario.
            if (p.linea == 104 && p.nombre.startsWith("Indios Verdes")) {
                pos = new LatLng(19.495027, -99.119547);
                titulo += " · andén de ascenso";
            }
            mapa.addMarker(new MarkerOptions().position(pos).title(titulo)
                    .icon(ic).anchor(0.5f, 0.5f).zIndex(6f));
        }
        dibujarUnidades(RealtimeRepository.get().getUltimo());   // unidades cerca del trazo (ocultas en recorrido)
        final LatLngBounds limites = bounds.build();

        resResumen.setText(getString(R.string.ruta_resumen, r.paradas, r.transbordos, r.minutos));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.instrucciones.size(); i++) {
            Planificador.Instruccion in = r.instrucciones.get(i);
            String term = Planificador.nombreMostrar(requireContext(), in.terminal, in.linea);   // sin MXB, con nº si repite
            // Palabra del cambio de servicio según los sistemas: Metrobús=Transbordo, Mexibús/Mexicable=Correspondencia, mixto=Conexión.
            String verbo = getString(verboTransferencia(i > 0 ? r.instrucciones.get(i - 1).linea : 0, in.linea));
            // Si el transbordo es entre estaciones DISTINTAS (nombres diferentes), es una caminata a pie.
            if (in.transbordoAntes && i > 0 && i < r.pasos.size()) {
                Planificador.Paso prev = r.pasos.get(i - 1), cur = r.pasos.get(i);
                if (prev.destino != null && cur.origen != null
                        && !Planificador.norm(prev.destino).equals(Planificador.norm(cur.origen)))
                    sb.append(getString(R.string.ruta_camina,
                            Planificador.nombreMostrar(requireContext(), cur.origen, in.linea))).append("\n");
            }
            if (in.ruta != null) {
                sb.append(in.transbordoAntes ? getString(R.string.ruta_luego3, verbo, in.ruta, term)
                                             : getString(R.string.ruta_toma3, in.ruta, term));
            } else if (in.linea > 0) {   // línea normal: muestra su número (L3, L1, …)
                sb.append(in.transbordoAntes ? getString(R.string.ruta_luego, verbo, in.linea, term)
                                             : getString(R.string.ruta_toma, in.linea, term));
            } else {
                sb.append(in.transbordoAntes ? getString(R.string.ruta_luego2, verbo, term)
                                             : getString(R.string.ruta_toma2, term));
            }
            sb.append(" (").append(in.paradas).append(" paradas)");
            if (i < r.instrucciones.size() - 1) sb.append("\n");
        }
        resPasos.setText(sb.toString());
        boolean afect = Manifestaciones.hay();
        resAviso.setVisibility(afect ? View.VISIBLE : View.GONE);
        if (afect) resAviso.setText(getString(R.string.ruta_alterna));
        panelResultado.setVisibility(View.VISIBLE);

        // deslizador de estaciones (arriba)
        sliderAdapter.set(r.secuencia);
        panelEstaciones.setVisibility(r.secuencia.isEmpty() ? View.GONE : View.VISIBLE);
        boolean veniaRecorrido = recorrido;
        if (recorrido) detenerRecorrido();
        resEstado.setVisibility(View.GONE);
        btnRecorrido.setText(R.string.recorrido_iniciar);

        // Si al volver al módulo hay un recorrido en curso, reengancha su seguimiento (sin reiniciar el servicio).
        if (!veniaRecorrido && RecorridoService.activo && rutaActiva != null) {
            recorrido = true;
            btnRecorrido.setText(R.string.recorrido_detener);
            resEstado.setVisibility(View.VISIBLE);
            resEstado.setText(R.string.recorrido_ubicando);
        }

        encuadrar(limites);   // encuadra la ruta en el espacio visible (sin tapar con las tarjetas)
    }

    /** ¿El dispositivo está en modo oscuro? */
    private boolean esNoche() {
        int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Pictograma (logo) de una estación, decodificado en pequeño (≈28dp) como el mapa general. */
    /** Logo del botón de iconos: Movimex "B" en modo nuevo, "M" verde en modo antiguo. */
    private void actualizarLogoIconos(boolean nuevos) {
        View v = getView();
        if (v == null) return;
        android.widget.ImageButton b = v.findViewById(R.id.btn_iconos);
        if (b == null) return;
        b.setImageTintList(null);
        b.setImageResource(nuevos ? R.drawable.ic_mexibus_nuevo : R.drawable.ic_mexibus_ant);
    }

    private BitmapDescriptor bitmapEstacion(String icono, int color) {
        int px = Math.round(28 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Iconos.pictograma(requireContext(), icono, px);
        if (bmp != null) return BitmapDescriptorFactory.fromBitmap(bmp);
        // Modo "iconos antiguos" (o sin pictograma): punto del color de la línea.
        Bitmap dot = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dot);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF); c.drawCircle(px / 2f, px / 2f, px * 0.30f, p);
        p.setColor(color);      c.drawCircle(px / 2f, px / 2f, px * 0.22f, p);
        return BitmapDescriptorFactory.fromBitmap(dot);
    }

    /** Dibuja/actualiza SOLO las unidades dentro del área visible del mapa (como el mapa general). */
    private void dibujarUnidades(List<UnidadReal> unidades) {
        if (mapa == null) return;
        // Sin una ruta trazada no se muestran unidades (durante el recorrido sí se mantienen).
        if (anclasZona == null || anclasZona.isEmpty() || unidades == null) {
            for (com.google.android.gms.maps.model.Marker m : marcadoresUnidad.values()) m.remove();
            marcadoresUnidad.clear();
            return;
        }
        // Filtro por zonas: solo las unidades a RADIO_ESTACION_M metros a la redonda del trazo
        // (puntos densificados, así también salen las de tramos largos entre estaciones).
        java.util.Set<String> vistos = new java.util.HashSet<>();
        for (UnidadReal u : unidades) {
            if (u.posicion == null) continue;
            boolean enZona = false;
            for (LatLng a : anclasZona) {
                if (Linea.distancia(u.posicion, a) <= RADIO_ESTACION_M) { enZona = true; break; }
            }
            if (!enZona) continue;   // fuera de la zona del trazo
            vistos.add(u.numero);
            com.google.android.gms.maps.model.Marker m = marcadoresUnidad.get(u.numero);
            if (m == null) {
                m = mapa.addMarker(new MarkerOptions().position(u.posicion)
                        .title("Unidad " + u.numero).snippet(snippet(u)).icon(iconoUnidad(u))
                        .anchor(0.5f, 0.5f).zIndex(9f));
                if (m != null) marcadoresUnidad.put(u.numero, m);
            } else {
                moverMarcador(u.numero, m, u.posicion);   // desplazamiento animado (sensación de tiempo real)
                m.setSnippet(snippet(u));
            }
        }
        for (java.util.Iterator<java.util.Map.Entry<String, com.google.android.gms.maps.model.Marker>>
             it = marcadoresUnidad.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, com.google.android.gms.maps.model.Marker> e = it.next();
            if (!vistos.contains(e.getKey())) { e.getValue().remove(); animToken.remove(e.getKey()); it.remove(); }
        }
    }

    /** Interpola la posición del marcador hacia {@code destino} (mismo efecto que el mapa general). */
    private void moverMarcador(String numero, com.google.android.gms.maps.model.Marker marker, LatLng destino) {
        final LatLng inicio = marker.getPosition();
        if (inicio.latitude == destino.latitude && inicio.longitude == destino.longitude) return;
        final long token = ++animSeq;
        animToken.put(numero, token);
        final long t0 = android.os.SystemClock.uptimeMillis();
        handler.post(new Runnable() {
            @Override public void run() {
                Long actual = animToken.get(numero);
                if (mapa == null || actual == null || actual != token) return;   // reemplazado/eliminado
                float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) Config.ANIM_MS);
                double lat = inicio.latitude + t * (destino.latitude - inicio.latitude);
                double lon = inicio.longitude + t * (destino.longitude - inicio.longitude);
                try { marker.setPosition(new LatLng(lat, lon)); }
                catch (Exception e) { return; }
                if (t < 1f) handler.postDelayed(this, 16);
            }
        });
    }

    /** Añade puntos intermedios al trazo cada ~{@code pasoM} metros (cobertura del filtro y snap del puntero). */
    private List<LatLng> densificar(List<LatLng> pts, double pasoM) {
        List<LatLng> out = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return out;
        out.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            LatLng a = pts.get(i - 1), b = pts.get(i);
            double dist = Linea.distancia(a, b);
            int n = (int) Math.floor(dist / pasoM);
            for (int j = 1; j <= n; j++) {
                double t = (j * pasoM) / dist;
                out.add(new LatLng(a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t));
            }
            out.add(b);
        }
        return out;
    }

    /** Coloca el puntero (icono norte) pegado al trazo: engancha {@code gps} al punto más cercano de la ruta. */
    private void pintarPuntero(LatLng gps) {
        if (mapa == null || anclasZona == null || anclasZona.isEmpty() || gps == null) return;
        int idx = 0; double best = Double.MAX_VALUE;
        for (int i = 0; i < anclasZona.size(); i++) {
            double dd = Linea.distancia(gps, anclasZona.get(i));
            if (dd < best) { best = dd; idx = i; }
        }
        LatLng mejor = anclasZona.get(idx);
        // Rumbo según la dirección de avance del trazo (hacia el destino): el puntero rota alineado a la ruta.
        float rumbo = idx < anclasZona.size() - 1
                ? rumboEntre(mejor, anclasZona.get(idx + 1))
                : rumboEntre(anclasZona.get(Math.max(0, idx - 1)), mejor);
        if (mkUsuario == null) {
            mkUsuario = mapa.addMarker(new MarkerOptions().position(mejor)
                    .icon(iconoPuntero()).anchor(0.5f, 0.5f).zIndex(11f).flat(true).rotation(rumbo));
        } else {
            mkUsuario.setPosition(mejor);
            mkUsuario.setRotation(rumbo);
        }
    }

    /** Rumbo (grados, 0 = norte) del segmento a→b. */
    private float rumboEntre(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude), lat2 = Math.toRadians(b.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
    }

    /** Puntero de ubicación: icono de norte blanco sobre un círculo azul. */
    private BitmapDescriptor iconoPuntero() {
        int d = Math.round(38 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(d / 2f, d / 2f, d / 2f - 1, p);        // aro blanco
        p.setColor(0xFF1976D2);
        c.drawCircle(d / 2f, d / 2f, d / 2f - Math.round(d * 0.10f), p);   // disco azul
        android.graphics.drawable.Drawable n =
                androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_north);
        if (n != null) {
            int m = Math.round(d * 0.24f);
            n.setBounds(m, m, d - m, d - m);
            n.setTint(0xFFFFFFFF);
            n.draw(c);
        }
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private String snippet(UnidadReal u) {
        String linea = u.linea != null ? "Línea " + u.linea : "Sin línea";
        return u.destino != null && !u.destino.isEmpty() ? linea + " · " + u.destino : linea;
    }

    /**
    private int colorLinea(int n) {
        Linea l = GtfsRepository.porNumero(requireContext(), n);
        return l != null ? l.color : 0xFF757575;
    }

     * Bus idéntico al del mapa en tiempo real: halo blanco, color(es) de línea (degradado
     * diagonal si va en ruta mixta) y el económico dibujado en el parabrisas.
     */
    private BitmapDescriptor iconoUnidad(UnidadReal u) {
        RutasMixtas.Tramo t = RutasMixtas.tramo(u.origen, u.destino);
        int arriba, abajo;
        if (t != null) { arriba = colorLinea(t.salida); abajo = colorLinea(t.termino); }
        else { arriba = abajo = (u.linea != null ? colorLinea(u.linea) : 0xFF757575); }

        int d = Math.round(40 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.drawable.Drawable bus =
                androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_bus);
        if (bus != null) {
            int margen = d / 5;
            int h = Math.round(d * 0.05f);
            bus.setBounds(margen - h, margen - h, d - margen + h, d - margen + h);   // halo blanco
            bus.setTint(0xFFFFFFFF);
            bus.draw(c);
            if (arriba == abajo) {
                bus.setBounds(margen, margen, d - margen, d - margen);
                bus.setTint(arriba);
                bus.draw(c);
            } else {                                                                  // ruta mixta
                Bitmap capa = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(capa);
                bus.setBounds(margen, margen, d - margen, d - margen);
                bus.setTint(0xFFFFFFFF);
                bus.draw(cc);
                android.graphics.Paint tri = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                tri.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                android.graphics.Path pa = new android.graphics.Path();
                pa.moveTo(0, 0); pa.lineTo(d, 0); pa.lineTo(0, d); pa.close();
                tri.setColor(arriba); cc.drawPath(pa, tri);
                android.graphics.Path pb = new android.graphics.Path();
                pb.moveTo(d, 0); pb.lineTo(d, d); pb.lineTo(0, d); pb.close();
                tri.setColor(abajo); cc.drawPath(pb, tri);
                c.drawBitmap(capa, 0, 0, null);
            }
        }
        dibujarNumeroParabrisas(c, d, u.numero);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /** Económico en el parabrisas del bus (mismo trazo que el mapa en tiempo real). */
    private void dibujarNumeroParabrisas(Canvas c, int d, String numero) {
        if (numero == null || numero.isEmpty()) return;
        int margen = d / 5;
        float busSize = d - 2f * margen;
        float cx = margen + busSize * (12f / 24f);
        float cyWin = margen + busSize * (8.6f / 24f);
        float winW = busSize * (12f / 24f);
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(android.graphics.Paint.Align.CENTER);
        tp.setTypeface(Tipografia.metro(requireContext()));
        tp.setFakeBoldText(true);
        float ts = winW / Math.max(2.2f, numero.length() * 0.62f);
        tp.setTextSize(ts);
        float y = cyWin - (tp.descent() + tp.ascent()) / 2f;
        tp.setStyle(android.graphics.Paint.Style.STROKE);
        tp.setStrokeWidth(Math.max(1.2f, ts * 0.20f));
        tp.setColor(0xFF10233A);
        c.drawText(numero, cx, y, tp);
        tp.setStyle(android.graphics.Paint.Style.FILL);
        tp.setColor(0xFFFFFFFF);
        c.drawText(numero, cx, y, tp);
    }

    /** Opaca (con un overlay gris translúcido) la parte de la ruta ya recorrida hasta {@code pos}. */
    private void pintarProgreso(LatLng pos) {
        // Usa el trazo FINO (densificado) para que el gris avance suave aunque vayas entre 2 estaciones.
        List<LatLng> base = (anclasZona != null && anclasZona.size() >= 2) ? anclasZona : rutaPuntos;
        if (mapa == null || base == null || base.size() < 2 || pos == null) return;
        int k = 0; double best = Double.MAX_VALUE;
        for (int i = 0; i < base.size(); i++) {
            double dd = Linea.distancia(base.get(i), pos);
            if (dd < best) { best = dd; k = i; }
        }
        List<LatLng> hecho = new java.util.ArrayList<>(base.subList(0, Math.max(2, k + 1)));
        if (progresoLine != null) progresoLine.setPoints(hecho);
        else progresoLine = mapa.addPolyline(new PolylineOptions().addAll(hecho)
                .color(0xFF9E9E9E).width(20f).zIndex(7f));   // gris sólido encima = "ya recorrido" (estilo Maps)
    }

    /** Encaja la ruta en el área visible del mapa dejando margen para las tarjetas. */
    private void encuadrar(LatLngBounds limites) {
        View root = getView();
        if (root == null || mapa == null) return;
        root.post(() -> {
            if (!isAdded() || mapa == null) return;
            int arriba = (panelEstaciones.getVisibility() == View.VISIBLE
                    ? panelEstaciones.getBottom() : panelOrigen.getBottom());
            int abajo = panelResultado.getVisibility() == View.VISIBLE
                    ? root.getHeight() - panelResultado.getTop() : 0;
            int m = (int) (16 * getResources().getDisplayMetrics().density);
            mapa.setPadding(m, arriba + m, m, abajo + m);
            try {
                mapa.animateCamera(CameraUpdateFactory.newLatLngBounds(limites, m));
            } catch (Exception ignore) {}
        });
    }

    private void alternarRecorrido() {
        if (recorrido) detenerRecorrido();
        else iniciarRecorrido();
    }

    @SuppressLint("MissingPermission")
    private void iniciarRecorrido() {
        if (rutaActiva == null || rutaActiva.secuencia.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.recorrido_sin_permiso), Toast.LENGTH_LONG).show();
            return;
        }
        // ¿El viaje va por UNA sola línea Mexibús con varios servicios (Ordinario/Express, y Rosa si es mujer)?
        int base = baseUnicaMexibus(rutaActiva.secuencia);
        if (base != 0) {
            java.util.List<Servicios.Servicio> ops = Servicios.disponibles(requireContext(), base,
                    origenCanon, destinoCanon, Perfil.serviciosRosa(requireContext()));
            if (!ops.isEmpty()) { elegirServicio(ops); return; }   // muestra la carta y arranca al elegir
        }
        arrancarRecorrido(null);
    }

    /** Base Mexibús única del viaje (101..104) si TODO el recorrido va por esa línea; 0 si no aplica. */
    private static int baseUnicaMexibus(java.util.List<Planificador.Parada> seq) {
        int base = 0;
        for (Planificador.Parada p : seq) {
            int b = Servicios.base(p.linea);
            if (b < 100 || b >= 200) return 0;         // hay tramo Metrobús/Mexicable
            if (base == 0) base = b; else if (base != b) return 0;   // más de una línea Mexibús
        }
        return base;
    }

    /** Carta flotante para elegir el servicio; al elegir, re-rutea por ese servicio y arranca el recorrido. */
    private void elegirServicio(java.util.List<Servicios.Servicio> ops) {
        java.util.List<Opcion> op = new java.util.ArrayList<>();
        for (Servicios.Servicio s : ops) {
            Bitmap badge = s.rosa ? badgeLinea(0xFFE91E63, "R")
                                  : badgeLinea(colorLinea(s.linea), Planificador.etiquetaLineaCortaPub(s.linea));
            op.add(new Opcion(0, badge, s.nombre, () -> {
                // Re-rutea fijando la línea del servicio (Express salta estaciones: recorrido real distinto).
                Planificador.Ruta r = Planificador.calcular(requireContext(), origenCanon, destinoCanon, s.linea, s.linea);
                if (r != null && !r.secuencia.isEmpty()) { rutaActiva = r; dibujar(r, destinoCanon); }
                arrancarRecorrido(s);
            }));
        }
        mostrarCarta(getString(R.string.servicio_titulo), op);
    }

    @SuppressLint("MissingPermission")
    private void arrancarRecorrido(Servicios.Servicio s) {
        recorrido = true;
        btnRecorrido.setText(R.string.recorrido_detener);
        resEstado.setVisibility(View.VISIBLE);
        resEstado.setText(R.string.recorrido_ubicando);
        String destinoFinal = rutaActiva.secuencia.get(rutaActiva.secuencia.size() - 1).nombre;
        RecorridoService.servicioTexto = s == null ? null
                : (s.rosa ? getString(R.string.servicio_voz_rosa, s.nombre.replace(" · Rosa", ""))
                          : getString(R.string.servicio_voz, s.nombre));
        RecorridoService.iniciar(requireContext(), rutaActiva.secuencia, destinoFinal);
    }

    @SuppressLint("MissingPermission")
    private void detenerRecorrido() {
        recorrido = false;
        btnRecorrido.setText(R.string.recorrido_iniciar);
        resEstado.setVisibility(View.GONE);
        sliderAdapter.setActual(-1);
        if (mkUsuario != null) { mkUsuario.remove(); mkUsuario = null; }   // quita el puntero
        RecorridoService.detener(requireContext());
        dibujarUnidades(RealtimeRepository.get().getUltimo());   // al detener, vuelven las unidades cerca del trazo
    }

    /** Lee del servicio en qué estación va el usuario y actualiza el deslizador + estado. */
    private void refrescarRecorridoUI() {
        if (!recorrido || rutaActiva == null || !isAdded()) return;
        int idx = RecorridoService.actualIdx;
        List<Planificador.Parada> seq = rutaActiva.secuencia;
        if (idx < 0 || idx >= seq.size()) return;
        sliderAdapter.setActual(idx);
        rvEstaciones.smoothScrollToPosition(idx);
        Planificador.Parada act = seq.get(idx);
        // El trazo de progreso avanza con la ubicación real (cada segundo); si aún no hay GPS, usa la estación.
        LatLng posReal = RecorridoService.ultimaPos != null ? RecorridoService.ultimaPos : act.pos;
        pintarProgreso(posReal);   // opaca la parte de la ruta ya recorrida
        pintarPuntero(RecorridoService.ultimaPos);   // puntero de ubicación pegado al trazo
        java.util.function.Function<Planificador.Parada, String> vis =
                p -> Planificador.nombreMostrar(requireContext(), p.nombre, p.linea);
        if (idx >= seq.size() - 1) {
            resEstado.setText(getString(R.string.recorrido_llegaste, vis.apply(act)));
        } else {
            Planificador.Parada sig = seq.get(idx + 1);
            if (sig.transbordo) resEstado.setText(getString(R.string.recorrido_transborda, vis.apply(sig), sig.linea));
            else resEstado.setText(getString(R.string.recorrido_vas, vis.apply(act), vis.apply(sig)));
        }
    }

    /** Avisa una vez cuando la unidad del primer tramo se acerca a tu estación de origen. */
    private void revisarUnidad(List<UnidadReal> unidades) {
        if (!isAdded() || rutaActiva == null || origenPos == null || avisoUnidad
                || rutaActiva.pasos.isEmpty()) return;
        Planificador.Paso p0 = rutaActiva.pasos.get(0);
        for (UnidadReal u : unidades) {
            boolean mismaLinea = u.linea != null && u.linea == p0.linea;
            boolean mismoSentido = u.destino == null || u.destino.isEmpty()
                    || Planificador.norm(u.destino).contains(Planificador.norm(p0.destino))
                    || Planificador.norm(p0.destino).contains(Planificador.norm(u.destino));
            if (mismaLinea && mismoSentido
                    && Linea.distancia(u.posicion, origenPos) <= Config.SEGUIR_CERCA_M) {
                avisoUnidad = true;
                Toast.makeText(requireContext(),
                        getString(R.string.ruta_unidad_cerca, p0.linea, p0.destino, p0.origen),
                        Toast.LENGTH_LONG).show();
                break;
            }
        }
    }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
        handler.removeCallbacks(tickRecorrido);
        handler.post(tickRecorrido);
    }

    @Override public void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tickRecorrido);
    }

    @Override public void onDestroyView() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tickRecorrido);
        trazo.clear();
        rutaActiva = null;
        mapa = null;
        super.onDestroyView();
    }
}
