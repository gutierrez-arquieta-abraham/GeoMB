package com.memegrados.GeoMB;

import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.InputStream;

/** Información general de la app, agradecimientos y el "modo personalizado" oculto. */
public class AcercaFragment extends Fragment {

    private View panel;
    private SwitchMaterial swCachondo, swPbs;
    private View btnVerClaves;
    private final CheckBox[] chkLineas = new CheckBox[8];   // 1..7 (índice 0 sin usar)
    private int taps = 0;
    private long ultimoTap = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_acerca, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Excepción: en "Acerca de" solo el nombre de la app y "Agradecimientos" usan Tipo Metro.
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_app_nombre));
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_agradecimientos));

        TextView txtVersion = view.findViewById(R.id.txt_version);
        String v = "1.0";
        try {
            PackageInfo pi = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            if (pi.versionName != null) v = pi.versionName;
        } catch (Exception ignore) {}
        txtVersion.setText(getString(R.string.acerca_version, v));

        view.findViewById(R.id.btn_idioma).setOnClickListener(x -> Idiomas.mostrarSelector(requireContext()));
        view.findViewById(R.id.btn_editar_perfil).setOnClickListener(x -> editarPerfil());
        configurarSimulador(view);

        panel = view.findViewById(R.id.panel_personalizado);
        swCachondo = view.findViewById(R.id.sw_cachondo);
        swPbs = view.findViewById(R.id.sw_pbs);
        btnVerClaves = view.findViewById(R.id.btn_ver_claves);
        EditText inputFrase = view.findViewById(R.id.input_frase);

        // Sincronización en segundo plano (siempre visible, independiente del modo oculto)
        SwitchMaterial swSincro = view.findViewById(R.id.sw_sincro);
        swSincro.setChecked(Modos.sincronizacionFondo(requireContext()));
        swSincro.setOnCheckedChangeListener((btn, activar) -> {
            Modos.setSincronizacionFondo(requireContext(), activar);
            if (activar) SincronizacionService.iniciar(requireContext());
            else SincronizacionService.detener(requireContext());
        });

        // Tiempo de actualización del mapa (visible para todos). Rango MAPA_SEG_MIN..MAPA_SEG_MAX.
        TextView txtRefresco = view.findViewById(R.id.txt_refresco_valor);
        android.widget.SeekBar seekRefresco = view.findViewById(R.id.seek_refresco);
        int segAct = Modos.mapaRefrescoSeg(requireContext());
        txtRefresco.setText(getString(R.string.mapa_refresco_valor, segAct));
        seekRefresco.setProgress(segAct - Modos.MAPA_SEG_MIN);
        seekRefresco.setMax(Modos.MAPA_SEG_MAX - Modos.MAPA_SEG_MIN);
        seekRefresco.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int prog, boolean fromUser) {
                int seg = prog + Modos.MAPA_SEG_MIN;
                txtRefresco.setText(getString(R.string.mapa_refresco_valor, seg));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                Modos.setMapaRefrescoSeg(requireContext(), sb.getProgress() + Modos.MAPA_SEG_MIN);   // guarda en el dispositivo
            }
        });

        // "Mostrar Mexibús": capa en el mapa + que el planificador considere sus estaciones.
        SwitchMaterial swMexibus = view.findViewById(R.id.sw_mexibus);
        swMexibus.setChecked(Modos.mostrarMexibus(requireContext()));
        swMexibus.setOnCheckedChangeListener((btn, activar) ->
                Modos.setMostrarMexibus(requireContext(), activar));

        // "Recibir por líneas": activa la suscripción a afectaciones y despliega el menú por línea.
        View panelLineas = view.findViewById(R.id.panel_notif_lineas);
        SwitchMaterial swAfect = view.findViewById(R.id.sw_notif_afect);
        boolean recibir = Modos.notifAfectaciones(requireContext());
        swAfect.setChecked(recibir);
        if (panelLineas != null) panelLineas.setVisibility(recibir ? View.VISIBLE : View.GONE);
        swAfect.setOnCheckedChangeListener((btn, activar) -> {
            Modos.setNotifAfectaciones(requireContext(), activar);
            com.google.firebase.messaging.FirebaseMessaging fm =
                    com.google.firebase.messaging.FirebaseMessaging.getInstance();
            if (activar) fm.subscribeToTopic("afectaciones");
            else fm.unsubscribeFromTopic("afectaciones");
            if (panelLineas != null) panelLineas.setVisibility(activar ? View.VISIBLE : View.GONE);
        });

        // Avisos de actualización de la app: SIEMPRE activos (sin interruptor). Se fuerza la
        // suscripción para que todos los reciban en la próxima versión.
        Modos.setNotifActualizaciones(requireContext(), true);
        com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("actualizaciones");

        configurarNotifLineas(view);

        view.findViewById(R.id.img_logo).setOnClickListener(v2 -> contarTap());

        view.findViewById(R.id.btn_activar_frase).setOnClickListener(v2 -> {
            String f = inputFrase.getText() != null ? inputFrase.getText().toString() : "";
            aplicarFrase(f);
            inputFrase.setText("");
        });

        // Los switches solo aparecen si el modo está activo; tocarlos lo apaga.
        swCachondo.setOnClickListener(v2 -> {
            Modos.setCachondo(requireContext(), swCachondo.isChecked());
            refrescar();
        });
        swPbs.setOnClickListener(v2 -> {
            Modos.setPbs(requireContext(), swPbs.isChecked());
            refrescar();
        });
        btnVerClaves.setOnClickListener(v2 -> verClaves());
        view.findViewById(R.id.btn_salir_personalizado).setOnClickListener(v2 -> {
            Modos.setPersonalizado(requireContext(), false);
            refrescar();
        });

        refrescar();
    }

    /**
     * Control maestro de avisos por línea: una CASILLA por cada línea (1..7) con el icono de la línea.
     * Apagar una línea evita que ServicioMB te notifique de ella. El botón maestro apaga/enciende todas
     * y alterna su texto entre "Desactivar todas" y "Seleccionar líneas".
     */
    private void configurarNotifLineas(View view) {
        android.widget.LinearLayout cont = view.findViewById(R.id.cont_notif_lineas);
        android.widget.Button maestro = view.findViewById(R.id.btn_lineas_maestro);
        if (cont == null) return;
        cont.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * dp), ic = Math.round(28 * dp);
        for (int n = 1; n <= 7; n++) {
            final int linea = n;
            CheckBox cb = new CheckBox(requireContext());
            cb.setText(getString(R.string.linea_formato, n));
            cb.setChecked(Modos.notifLinea(requireContext(), n));
            cb.setCompoundDrawablePadding(pad);
            Tipografia.aplicar(cb);   // tipografía MI (Tipo Metro)
            // Casillas en fila (scroll horizontal): separación entre una y otra.
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pad;
            cb.setLayoutParams(lp);
            // Icono de la línea: se resuelve por nombre ("linea_1".."linea_7") para NO romper el build
            // si el drawable aún no existe; cuando lo agregues, aparece solo.
            int idIcon = getResources().getIdentifier("linea_" + n, "drawable", requireContext().getPackageName());
            if (idIcon != 0) {
                android.graphics.drawable.Drawable d =
                        androidx.core.content.ContextCompat.getDrawable(requireContext(), idIcon);
                if (d != null) { d.setBounds(0, 0, ic, ic); cb.setCompoundDrawables(d, null, null, null); }
            }
            cb.setOnCheckedChangeListener((b, v) -> {
                Modos.setNotifLinea(requireContext(), linea, v);
                actualizarBotonMaestro(maestro);
            });
            chkLineas[n] = cb;
            cont.addView(cb);
        }
        if (maestro != null) {
            actualizarBotonMaestro(maestro);
            maestro.setOnClickListener(x -> {
                boolean nuevo = !algunaLineaActiva();   // si todas apagadas -> encender; si hay alguna -> apagar
                for (int n = 1; n <= 7; n++) {
                    Modos.setNotifLinea(requireContext(), n, nuevo);
                    if (chkLineas[n] != null) chkLineas[n].setChecked(nuevo);
                }
                actualizarBotonMaestro(maestro);
            });
        }
    }

    private boolean algunaLineaActiva() {
        for (int n = 1; n <= 7; n++) if (Modos.notifLinea(requireContext(), n)) return true;
        return false;
    }

    /** "Desactivar todas" si hay alguna activa; "Seleccionar líneas" si están todas apagadas. */
    private void actualizarBotonMaestro(android.widget.Button maestro) {
        if (maestro == null) return;
        maestro.setText(algunaLineaActiva()
                ? R.string.notif_lineas_desactivar : R.string.notif_lineas_seleccionar);
    }

    /** Cuenta 5 toques seguidos al logo para desbloquear el modo personalizado. */
    private void contarTap() {
        long now = System.currentTimeMillis();
        if (now - ultimoTap > 1500) taps = 0;
        ultimoTap = now;
        taps++;
        if (taps >= 5) {
            taps = 0;
            if (!Modos.personalizado(requireContext())) {
                Modos.setPersonalizado(requireContext(), true);
                Toast.makeText(requireContext(), R.string.modo_desbloqueado, Toast.LENGTH_SHORT).show();
                refrescar();
            }
        }
    }

    /** Activa un sub-modo si la frase coincide EXACTAMENTE (sin normalizar). */
    private void aplicarFrase(String f) {
        if (!Modos.personalizado(requireContext())) return;
        if (Modos.FRASE_CACHONDO.equals(f)) {
            Modos.setCachondo(requireContext(), true);
            Toast.makeText(requireContext(), R.string.modo_frase_ok, Toast.LENGTH_SHORT).show();
        } else if (Modos.FRASE_PBS.equals(f)) {
            Modos.setPbs(requireContext(), true);
            Toast.makeText(requireContext(), R.string.modo_frase_ok, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), R.string.modo_frase_no, Toast.LENGTH_SHORT).show();
        }
        refrescar();
    }

    private void refrescar() {
        boolean person = Modos.personalizado(requireContext());
        panel.setVisibility(person ? View.VISIBLE : View.GONE);
        if (!person) return;
        boolean cach = Modos.cachondo(requireContext());
        boolean pbs = Modos.pbs(requireContext());
        swCachondo.setVisibility(cach ? View.VISIBLE : View.GONE);
        swCachondo.setChecked(cach);
        swPbs.setVisibility(pbs ? View.VISIBLE : View.GONE);
        swPbs.setChecked(pbs);
        btnVerClaves.setVisibility(pbs ? View.VISIBLE : View.GONE);
    }

    /** Muestra claves.png (colocada en res/raw) en un diálogo con scroll. */
    private void verClaves() {
        int id = getResources().getIdentifier("claves", "raw", requireContext().getPackageName());
        if (id == 0) {
            Toast.makeText(requireContext(), R.string.modo_claves_no, Toast.LENGTH_LONG).show();
            return;
        }
        try (InputStream is = getResources().openRawResource(id)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            ImageView iv = new ImageView(requireContext());
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(bmp);
            ScrollView sv = new ScrollView(requireContext());
            sv.addView(iv);
            new AlertDialog.Builder(requireContext())
                    .setView(sv)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.modo_claves_no, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Inyector de PRUEBA: simula una afectación por sentido (línea, estación, sentido) para
     * validar el planificador sin esperar una afectación real. "Quitar" la borra.
     */
    private void configurarSimulador(View v) {
        final Spinner spL = v.findViewById(R.id.sp_sim_linea);
        final Spinner spE = v.findViewById(R.id.sp_sim_estacion);
        final Spinner spE2 = v.findViewById(R.id.sp_sim_estacion2);
        final Spinner spS = v.findViewById(R.id.sp_sim_sentido);
        final List<Linea> lineas = GtfsRepository.getLineas(requireContext());

        List<String> nombresL = new ArrayList<>();
        for (Linea l : lineas) nombresL.add("Línea " + l.numero);
        spL.setAdapter(adaptador(nombresL));

        spL.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vv, int pos, long id) {
                Linea l = lineas.get(pos);
                List<String> est = new ArrayList<>();
                for (Estacion e : l.estaciones) est.add(e.nombre);
                spE.setAdapter(adaptador(est));
                spE2.setAdapter(adaptador(new ArrayList<>(est)));
                String t1 = l.estaciones.isEmpty() ? "" : l.estaciones.get(0).nombre;
                String t2 = l.estaciones.isEmpty() ? "" : l.estaciones.get(l.estaciones.size() - 1).nombre;
                spS.setAdapter(adaptador(Arrays.asList("Ambos sentidos", "Hacia " + t1, "Hacia " + t2)));
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        v.findViewById(R.id.btn_simular).setOnClickListener(x -> {
            Object es = spE.getSelectedItem();
            if (es == null) return;
            Linea l = lineas.get(spL.getSelectedItemPosition());
            int sentido = spS.getSelectedItemPosition();
            String term = null;
            if (!l.estaciones.isEmpty()) {
                if (sentido == 1) term = Planificador.norm(l.estaciones.get(0).nombre);
                else if (sentido == 2) term = Planificador.norm(l.estaciones.get(l.estaciones.size() - 1).nombre);
            }
            Manifestaciones.simular(Planificador.norm(es.toString()), term);
            Toast.makeText(requireContext(),
                    "Afectación simulada: " + es + (term == null ? " (ambos)" : " → " + spS.getSelectedItem()),
                    Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_sin_servicio).setOnClickListener(x -> {
            Linea l = lineas.get(spL.getSelectedItemPosition());
            int i1 = spE.getSelectedItemPosition(), i2 = spE2.getSelectedItemPosition();
            if (i1 < 0 || i2 < 0 || l.estaciones.isEmpty()) return;
            int lo = Math.min(i1, i2), hi = Math.max(i1, i2);
            for (int k = lo; k <= hi; k++)                       // todo el tramo fuera de servicio (ambos)
                Manifestaciones.simular(Planificador.norm(l.estaciones.get(k).nombre), null);
            Toast.makeText(requireContext(),
                    "Sin servicio: " + l.estaciones.get(lo).nombre + " a " + l.estaciones.get(hi).nombre,
                    Toast.LENGTH_LONG).show();
        });
        v.findViewById(R.id.btn_cortar).setOnClickListener(x -> {
            Linea l = lineas.get(spL.getSelectedItemPosition());
            int ep = spE.getSelectedItemPosition();
            if (ep < 0 || l.estaciones.isEmpty()) return;
            // Vecino del corte: hacia la 1ª terminal = ep-1; hacia la 2ª (o "ambos") = ep+1.
            int vecino = (spS.getSelectedItemPosition() == 1) ? ep - 1 : ep + 1;
            if (vecino < 0 || vecino >= l.estaciones.size()) {
                Toast.makeText(requireContext(), "No hay estación vecina en ese sentido", Toast.LENGTH_SHORT).show();
                return;
            }
            Estacion a = l.estaciones.get(ep), b = l.estaciones.get(vecino);
            Manifestaciones.cortar(l.numero, Planificador.norm(a.nombre), Planificador.norm(b.nombre));
            Toast.makeText(requireContext(),
                    "L" + l.numero + " cortada entre " + a.nombre + " y " + b.nombre,
                    Toast.LENGTH_LONG).show();
        });
        v.findViewById(R.id.btn_quitar_sim).setOnClickListener(x -> {
            Manifestaciones.limpiarSimulado();
            Toast.makeText(requireContext(), "Simulación / corte quitado", Toast.LENGTH_SHORT).show();
        });
    }

    private ArrayAdapter<String> adaptador(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /**
     * Cambio de tipo de usuario y movilidad reducida desde Acerca de. Pensado para quien
     * eligió mal en el mini-formulario de inicio. Reajusta la suscripción de avisos de elevadores.
     */
    private void editarPerfil() {
        View v = getLayoutInflater().inflate(R.layout.dialog_perfil, null, false);
        RadioGroup rg = v.findViewById(R.id.rg_tipo);
        RadioGroup rgGen = v.findViewById(R.id.rg_genero);
        CheckBox cb = v.findViewById(R.id.cb_movilidad);
        // Preselecciona el perfil actual.
        ((RadioButton) v.findViewById(
                Perfil.tipo(requireContext()) == Perfil.AFICIONADO ? R.id.rb_aficionado : R.id.rb_normal))
                .setChecked(true);
        ((RadioButton) v.findViewById(
                Perfil.genero(requireContext()) == Perfil.MUJER ? R.id.rb_mujer : R.id.rb_hombre))
                .setChecked(true);
        cb.setChecked(Perfil.movilidadReducida(requireContext()));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.perfil_titulo)
                .setView(v)
                .setPositiveButton(R.string.perfil_continuar, (d, w) -> {
                    int tipo = rg.getCheckedRadioButtonId() == R.id.rb_aficionado
                            ? Perfil.AFICIONADO : Perfil.NORMAL;
                    int genero = rgGen.getCheckedRadioButtonId() == R.id.rb_mujer
                            ? Perfil.MUJER : Perfil.HOMBRE;
                    boolean movilidad = cb.isChecked();
                    Perfil.guardar(requireContext(), tipo, movilidad, genero);
                    // Los avisos de elevadores solo aplican con movilidad reducida.
                    com.google.firebase.messaging.FirebaseMessaging fm =
                            com.google.firebase.messaging.FirebaseMessaging.getInstance();
                    if (movilidad) fm.subscribeToTopic("elevadores");
                    else fm.unsubscribeFromTopic("elevadores");
                    Toast.makeText(requireContext(), R.string.perfil_guardado, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
