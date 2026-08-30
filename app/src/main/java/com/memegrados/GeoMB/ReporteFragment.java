package com.memegrados.GeoMB;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reporte de irregularidad con formulario DINÁMICO por tipo:
 *   - Unidad y personal / Personal ajeno -> económico, nombre del personal, cargo.
 *   - Estación -> estación (se ubica como en el mapa).
 * Requiere login + verificación KYC (el nombre del reportante sale de la sesión). Evidencia en vivo
 * obligatoria; línea deducida por cercanía; teléfono opcional.
 */
public class ReporteFragment extends Fragment {

    private static final float RADIO_UNIDADES_M = 500f;

    private Spinner spTipo, spCargo;
    private EditText inEco, inEstacion, inDesc, inInvolucrado, inTel;
    private TextView txtFoto, txtVerificado;
    private View grpUnidad, grpPersonal, grpEstacion;
    private Uri evidenciaUri, capturaPendiente;
    private String modoPendiente;
    private LatLng posReporte;
    private int lineaReporte = 0;
    private final long abiertoEn = System.currentTimeMillis();

    private int intentosVerif = 0;
    private final Handler hVerif = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Uri> tomarFoto =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (Boolean.TRUE.equals(ok) && capturaPendiente != null) fijarEvidencia(capturaPendiente);
            });
    private final ActivityResultLauncher<Uri> grabarVideo =
            registerForActivityResult(new ActivityResultContracts.CaptureVideo(), ok -> {
                if (Boolean.TRUE.equals(ok) && capturaPendiente != null) fijarEvidencia(capturaPendiente);
            });
    private final ActivityResultLauncher<Intent> grabarAudio =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null
                        && res.getData().getData() != null) fijarEvidencia(res.getData().getData());
            });
    private final ActivityResultLauncher<String> pedirCamara =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (Boolean.TRUE.equals(ok)) lanzarCaptura(modoPendiente);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_reporte, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spTipo = v.findViewById(R.id.sp_tipo);
        spCargo = v.findViewById(R.id.sp_cargo);
        inEco = v.findViewById(R.id.in_eco);
        inEstacion = v.findViewById(R.id.in_estacion);
        inDesc = v.findViewById(R.id.in_desc);
        inInvolucrado = v.findViewById(R.id.in_involucrado);
        inTel = v.findViewById(R.id.in_tel);
        txtFoto = v.findViewById(R.id.txt_foto);
        txtVerificado = v.findViewById(R.id.txt_verificado);
        grpUnidad = v.findViewById(R.id.grp_unidad);
        grpPersonal = v.findViewById(R.id.grp_personal);
        grpEstacion = v.findViewById(R.id.grp_estacion);

        spTipo.setAdapter(adaptador(new String[]{
                getString(R.string.reporte_tipo_unidad),       // 0 Unidad
                getString(R.string.reporte_tipo_personal),     // 1 Personal
                getString(R.string.reporte_tipo_estacion)}));  // 2 Estación
        spCargo.setAdapter(adaptador(new String[]{
                getString(R.string.reporte_cargo_policia),
                getString(R.string.reporte_cargo_chaleco),
                getString(R.string.reporte_cargo_operador),
                getString(R.string.reporte_cargo_otro)}));

        // Case por tipo: muestra solo los datos que aplican.
        spTipo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v2, int pos, long id) { mostrarCampos(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        mostrarCampos(spTipo.getSelectedItemPosition());

        autollenarContexto();
        actualizarVerificado();

        inEstacion.setOnEditorActionListener((t, id, e) -> { resolverEstacion(); return true; });
        v.findViewById(R.id.btn_unidades_cerca).setOnClickListener(x -> capturarUnidadCerca());
        v.findViewById(R.id.btn_foto).setOnClickListener(x -> elegirTipoEvidencia());
        v.findViewById(R.id.btn_enviar).setOnClickListener(x -> enviar());
    }

    /** Case por tipo: Unidad(0)=económico; Personal(1)=económico + nombre/cargo; Estación(2)=estación. */
    private void mostrarCampos(int tipoPos) {
        boolean unidad = tipoPos == 0 || tipoPos == 1;   // Unidad y Personal usan el económico
        boolean personal = tipoPos == 1;
        boolean estacion = tipoPos == 2;
        if (grpUnidad != null) grpUnidad.setVisibility(unidad ? View.VISIBLE : View.GONE);
        if (grpPersonal != null) grpPersonal.setVisibility(personal ? View.VISIBLE : View.GONE);
        if (grpEstacion != null) grpEstacion.setVisibility(estacion ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarVerificado();
        intentosVerif = 0;
        pollVerificacion();
    }

    @Override
    public void onPause() {
        super.onPause();
        hVerif.removeCallbacksAndMessages(null);
    }

    private ArrayAdapter<String> adaptador(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void autollenarContexto() {
        String eco = RealtimeRepository.unidadSeleccionada;
        if (eco != null && !eco.isEmpty()) {
            inEco.setText(eco);
            UnidadReal u = RealtimeRepository.get().buscar(eco);
            if (u != null) {
                posReporte = u.posicion;
                if (u.linea != null) lineaReporte = u.linea;
            }
        } else if (RealtimeRepository.lineaSeleccionada >= 1 && RealtimeRepository.lineaSeleccionada <= 7) {
            lineaReporte = RealtimeRepository.lineaSeleccionada;
        }
    }

    // ---- verificación ----

    private void actualizarVerificado() {
        if (txtVerificado != null)
            txtVerificado.setVisibility(Verificacion.verificado(requireContext()) ? View.VISIBLE : View.GONE);
    }

    private void pollVerificacion() {
        if (!isAdded() || Verificacion.verificado(requireContext())) { actualizarVerificado(); return; }
        String uid = uidUsuario();
        if (uid == null || !DiditKYC.configurado()) return;
        DiditKYC.consultarEstado(uid, (verif, nombre) -> {
            if (!isAdded()) return;
            if (verif) {
                Verificacion.marcarVerificado(requireContext(), nombre);
                actualizarVerificado();
                Toast.makeText(requireContext(), R.string.verif_listo, Toast.LENGTH_SHORT).show();
            } else if (intentosVerif++ < 6) {
                hVerif.postDelayed(this::pollVerificacion, 3000);
            }
        });
    }

    // ---- estación (mismo método que el mapa) + línea por cercanía ----

    private void resolverEstacion() {
        String txt = inEstacion.getText().toString().trim();
        if (txt.isEmpty()) return;
        String nombre = Planificador.estacionParecida(requireContext(), txt);
        if (nombre == null) {
            Toast.makeText(requireContext(), R.string.estacion_no_encontrada, Toast.LENGTH_SHORT).show();
            return;
        }
        inEstacion.setText(nombre);
        String nn = Planificador.norm(nombre);
        LatLng p = posicionEstacion(nn);
        if (p != null) posReporte = p;
        int ln = lineaEstacion(nn);
        if (ln > 0) lineaReporte = ln;
    }

    private LatLng posicionEstacion(String nn) {
        double lat = 0, lon = 0;
        int c = 0;
        try {
            for (Linea l : GtfsRepository.getLineas(requireContext()))
                for (Estacion e : l.estaciones)
                    if (Planificador.norm(e.nombre).equals(nn)) {
                        lat += e.posicion.latitude; lon += e.posicion.longitude; c++;
                    }
        } catch (Exception ignore) {}
        return c == 0 ? null : new LatLng(lat / c, lon / c);
    }

    private int lineaEstacion(String nn) {
        try {
            for (Linea l : GtfsRepository.getLineas(requireContext()))
                for (Estacion e : l.estaciones)
                    if (Planificador.norm(e.nombre).equals(nn)) return l.numero;
        } catch (Exception ignore) {}
        return 0;
    }

    // ---- captura de unidades cercanas ----

    @android.annotation.SuppressLint("MissingPermission")
    private void capturarUnidadCerca() {
        if (posReporte != null) { fetchYMostrarUnidades(posReporte); return; }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(requireContext()).getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (!isAdded()) return;
                        if (loc != null) fetchYMostrarUnidades(new LatLng(loc.getLatitude(), loc.getLongitude()));
                        else Toast.makeText(requireContext(), R.string.reporte_elige_estacion, Toast.LENGTH_LONG).show();
                    });
        } else {
            Toast.makeText(requireContext(), R.string.reporte_elige_estacion, Toast.LENGTH_LONG).show();
        }
    }

    private void fetchYMostrarUnidades(LatLng ref) {
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override public void onData(List<UnidadReal> unidades) { mostrarUnidadesCerca(ref, unidades); }
            @Override public void onError(String m) {
                mostrarUnidadesCerca(ref, RealtimeRepository.get().getUltimo());
            }
        });
    }

    private void mostrarUnidadesCerca(LatLng ref, List<UnidadReal> todas) {
        if (!isAdded() || ref == null) return;
        List<UnidadReal> cerca = new ArrayList<>();
        for (UnidadReal u : todas)
            if (Linea.distancia(u.posicion, ref) <= RADIO_UNIDADES_M) cerca.add(u);
        Collections.sort(cerca, (a, b) -> Double.compare(
                Linea.distancia(a.posicion, ref), Linea.distancia(b.posicion, ref)));
        if (cerca.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reporte_unidades_ninguna, Toast.LENGTH_LONG).show();
            return;
        }
        if (cerca.size() > 20) cerca = cerca.subList(0, 20);
        final List<UnidadReal> lista = cerca;
        CharSequence[] items = new CharSequence[lista.size()];
        for (int i = 0; i < lista.size(); i++) {
            UnidadReal u = lista.get(i);
            int m = (int) Math.round(Linea.distancia(u.posicion, ref));
            String ln = u.linea != null ? "L" + u.linea : "—";
            String dest = u.destino != null && !u.destino.isEmpty() ? " · " + u.destino : "";
            items[i] = u.numero + " · " + ln + dest + "  (" + m + " m)";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reporte_unidades_cerca)
                .setItems(items, (d, w) -> {
                    UnidadReal u = lista.get(w);
                    inEco.setText(u.numero);
                    posReporte = u.posicion;
                    if (u.linea != null && u.linea >= 1 && u.linea <= 7) lineaReporte = u.linea;
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- captura EN VIVO de evidencia ----

    private void elegirTipoEvidencia() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reporte_adjuntar)
                .setItems(new CharSequence[]{
                        getString(R.string.reporte_ev_foto),
                        getString(R.string.reporte_ev_video),
                        getString(R.string.reporte_ev_audio)}, (d, w) -> {
                    if (w == 2) lanzarCaptura("audio");
                    else { modoPendiente = (w == 0) ? "foto" : "video"; pedirCamaraYCapturar(); }
                })
                .show();
    }

    private void pedirCamaraYCapturar() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            lanzarCaptura(modoPendiente);
        } else {
            pedirCamara.launch(Manifest.permission.CAMERA);
        }
    }

    private void lanzarCaptura(String modo) {
        try {
            if ("audio".equals(modo)) {
                Intent i = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
                if (i.resolveActivity(requireContext().getPackageManager()) != null) grabarAudio.launch(i);
                else Toast.makeText(requireContext(), R.string.reporte_sin_grabadora, Toast.LENGTH_LONG).show();
                return;
            }
            String ext = "foto".equals(modo) ? "jpg" : "mp4";
            capturaPendiente = nuevoArchivo(ext);
            if (capturaPendiente == null) return;
            if ("foto".equals(modo)) tomarFoto.launch(capturaPendiente);
            else grabarVideo.launch(capturaPendiente);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.reporte_sin_camara, Toast.LENGTH_LONG).show();
        }
    }

    private Uri nuevoArchivo(String ext) {
        try {
            File dir = new File(requireContext().getCacheDir(), "evidencia");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "ev_" + System.currentTimeMillis() + "." + ext);
            return FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", f);
        } catch (Exception e) {
            return null;
        }
    }

    private void fijarEvidencia(Uri uri) {
        evidenciaUri = uri;
        String nombre = uri.getLastPathSegment();
        txtFoto.setText(getString(R.string.reporte_foto_adjunta, nombre != null ? nombre : "1"));
        txtFoto.setVisibility(View.VISIBLE);
    }

    // ---- envío ----

    private void enviar() {
        if (correoUsuario() == null) {
            Toast.makeText(requireContext(), R.string.reporte_requiere_login, Toast.LENGTH_LONG).show();
            return;
        }
        if (!Verificacion.verificado(requireContext())) {
            String uid = uidUsuario();
            if (uid != null && DiditKYC.configurado()) {
                DiditKYC.consultarEstado(uid, (verif, nombre) -> {
                    if (!isAdded()) return;
                    if (verif) {
                        Verificacion.marcarVerificado(requireContext(), nombre);
                        actualizarVerificado();
                        enviar();
                    } else {
                        Toast.makeText(requireContext(), R.string.reporte_requiere_verificacion, Toast.LENGTH_LONG).show();
                        Verificacion.iniciar(this);
                    }
                });
            } else {
                Toast.makeText(requireContext(), R.string.reporte_requiere_verificacion, Toast.LENGTH_LONG).show();
                Verificacion.iniciar(this);
            }
            return;
        }
        String desc = inDesc.getText().toString().trim();
        if (desc.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reporte_falta_desc, Toast.LENGTH_SHORT).show();
            inDesc.requestFocus();
            return;
        }
        if (evidenciaUri == null) {
            Toast.makeText(requireContext(), R.string.reporte_falta_evidencia, Toast.LENGTH_LONG).show();
            return;
        }

        int tipoPos = spTipo.getSelectedItemPosition();
        String tipo = (String) spTipo.getSelectedItem();
        String tel = inTel.getText().toString().trim();
        String nombre = Verificacion.nombre(requireContext());   // reportante: nombre de la sesión

        String eco = null, estacion = null, personalNombre = null, personalCargo = null;
        if (tipoPos == 2) {                       // Estación
            estacion = vacioNull(inEstacion.getText().toString());
        } else {                                  // Unidad(0) o Personal(1) -> económico
            eco = vacioNull(inEco.getText().toString());
            if (tipoPos == 1) {                   // Personal -> nombre + cargo
                personalNombre = vacioNull(inInvolucrado.getText().toString());
                personalCargo = (String) spCargo.getSelectedItem();
            }
        }

        ReporteIrregularidad.enviar(requireContext(), abiertoEn, lineaReporte,
                estacion, posReporte, eco, null, tipo, desc,
                personalNombre, personalCargo,
                nombre.isEmpty() ? null : nombre,
                tel.isEmpty() ? null : tel, correoUsuario(), evidenciaUri);
    }

    private static String vacioNull(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? null : t;
    }

    private String correoUsuario() {
        try {
            com.google.firebase.auth.FirebaseUser u =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getEmail() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String uidUsuario() {
        try {
            com.google.firebase.auth.FirebaseUser u =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
