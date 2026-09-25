package com.memegrados.GeoMB;

import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.InputStream;

// ============================================================
// CLASE    : ConfiguracionFragment   (extends Fragment)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Pantalla "Configuración" (antes "Acerca de"): organizada en 2 MÓDULOS
// visuales -- Personalización (perfil de Google, idioma, ahorro de datos,
// refresco del mapa, Mostrar Mexibús, sincronización, notificaciones,
// descarga de voz offline) y Agradecimientos (info de la app) -- más el
// "modo personalizado" OCULTO (5 toques al logo). Es el panel de
// configuración que lee/escribe en Modos.
// ============================================================
/** Configuración (perfil + ajustes) y agradecimientos, en 2 módulos, más el "modo personalizado" oculto. */
public class ConfiguracionFragment extends Fragment {

    private View panel;
    private SwitchMaterial swCachondo, swPbs;
    private View btnVerClaves;
    private AlertDialog dlgDescargaAudios;   // se cierra en onDestroyView para no dejar la ventana "colgada"
    private final CheckBox[] chkLineas = new CheckBox[8];   // 1..7 (índice 0 sin usar)
    private final java.util.LinkedHashMap<Integer, CheckBox> chkTodas = new java.util.LinkedHashMap<>();  // Metrobús + Mexibús
    private int taps = 0;
    private long ultimoTap = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_configuracion, container, false);
    }

    /** Evita dejar la "carta" de progreso colgada (window leak) si el fragmento se destruye a
     *  mitad de una descarga; la descarga en sí sigue viva en {@link DescargaVozService}. */
    @Override
    public void onDestroyView() {
        if (dlgDescargaAudios != null) {
            try { if (dlgDescargaAudios.isShowing()) dlgDescargaAudios.dismiss(); } catch (Exception ignore) {}
            dlgDescargaAudios = null;
        }
        DescargaVozService.setEscucha(null);
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Excepción: en Configuración solo el nombre de la app y los títulos de módulo
        // ("Personalización"/"Agradecimientos") usan Tipo Metro.
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_app_nombre));
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_agradecimientos));
        Tipografia.aplicar((TextView) view.findViewById(R.id.txt_personalizacion_titulo));

        cargarPerfilGoogle(view);

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
        view.findViewById(R.id.btn_descargar_audios).setOnClickListener(x -> menuAudios());
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

        // "Ahorro de datos": activo por defecto; espacia el refresco de unidades en vivo y evita
        // descargar la voz Mia mientras se está en datos móviles (ver Red.java).
        SwitchMaterial swAhorroDatos = view.findViewById(R.id.sw_ahorro_datos);
        swAhorroDatos.setChecked(Modos.ahorroDatos(requireContext()));
        swAhorroDatos.setOnCheckedChangeListener((btn, activar) ->
                Modos.setAhorroDatos(requireContext(), activar));

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

    /** Encabezado de perfil (módulo Personalización): nombre/correo/foto de la cuenta de Google con
     *  la que se inició sesión ({@link LoginActivity}). La foto se descarga aparte (URL remota de
     *  Google) y se recorta en círculo; si algo falla, se queda el icono genérico de la plantilla. */
    private void cargarPerfilGoogle(View view) {
        TextView txtNombre = view.findViewById(R.id.txt_perfil_nombre);
        TextView txtEmail = view.findViewById(R.id.txt_perfil_email);
        ImageView imgFoto = view.findViewById(R.id.img_perfil_foto);
        FirebaseUser u;
        try {
            u = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Throwable ex) {
            u = null;   // sin Firebase disponible (p. ej. sin Google Play Services): se queda el respaldo
        }
        if (u == null) {
            txtNombre.setText(R.string.config_perfil_sin_nombre);
            txtEmail.setVisibility(View.GONE);
            return;
        }
        String nombre = u.getDisplayName();
        txtNombre.setText(nombre != null && !nombre.isEmpty() ? nombre : getString(R.string.config_perfil_sin_nombre));
        String email = u.getEmail();
        if (email != null && !email.isEmpty()) {
            txtEmail.setText(email);
            txtEmail.setVisibility(View.VISIBLE);
        } else {
            txtEmail.setVisibility(View.GONE);
        }
        cargarFotoPerfil(imgFoto, u.getPhotoUrl());
    }

    /** Descarga (hilo aparte) la foto de perfil de Google y la aplica recortada en círculo. Cualquier
     *  falla (sin red, URL vencida, etc.) simplemente deja el icono genérico de la plantilla. */
    private void cargarFotoPerfil(ImageView iv, Uri photoUrl) {
        if (photoUrl == null) return;
        new Thread(() -> {
            Bitmap bmp = null;
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(photoUrl.toString()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Throwable ignore) {
                // sin red, URL vencida, etc.: se queda el icono genérico
            } finally {
                if (c != null) c.disconnect();
            }
            if (bmp == null) return;
            Bitmap fondo = bmp;
            handler.post(() -> {
                if (!isAdded()) return;   // el fragmento ya no está en pantalla
                RoundedBitmapDrawable rd = RoundedBitmapDrawableFactory.create(getResources(), fondo);
                rd.setCircular(true);
                iv.setPadding(0, 0, 0, 0);
                iv.setBackground(null);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageDrawable(rd);
            });
        }).start();
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
        chkTodas.clear();
        // Metrobús L1..L7
        for (int n = 1; n <= 7; n++)
            agregarChkLinea(cont, maestro, n, getString(R.string.linea_formato, n), "linea_" + n);
        // Mexibús: troncales (101..104) y ramales (111..113), si la capa está activa.
        if (Modos.mostrarMexibus(requireContext())) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                int c = l.numero;
                if (!((c >= 101 && c <= 104) || (c >= 111 && c <= 113))) continue;
                String dr = "mexibus_0" + (c >= 111 ? (c - 110) + "a" : String.valueOf(c - 100));
                agregarChkLinea(cont, maestro, c, "MXB L" + Planificador.etiquetaLineaCortaPub(c), dr);
            }
        }
        if (maestro != null) {
            actualizarBotonMaestro(maestro);
            maestro.setOnClickListener(x -> {
                boolean nuevo = !algunaLineaActiva();   // todas apagadas -> encender; alguna activa -> apagar
                for (java.util.Map.Entry<Integer, CheckBox> e : chkTodas.entrySet()) {
                    Modos.setNotifLinea(requireContext(), e.getKey(), nuevo);
                    e.getValue().setChecked(nuevo);
                }
                actualizarBotonMaestro(maestro);
            });
        }
    }

    /** Crea una casilla de notificación para una línea (Metrobús o Mexibús) con su ícono. */
    private void agregarChkLinea(android.widget.LinearLayout cont, android.widget.Button maestro,
                                 int codigo, String etiqueta, String drawableNombre) {
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * dp), ic = Math.round(28 * dp);
        CheckBox cb = new CheckBox(requireContext());
        cb.setText(etiqueta);
        cb.setChecked(Modos.notifLinea(requireContext(), codigo));
        cb.setCompoundDrawablePadding(pad);
        Tipografia.aplicar(cb);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = pad;
        cb.setLayoutParams(lp);
        int idIcon = getResources().getIdentifier(drawableNombre, "drawable", requireContext().getPackageName());
        if (idIcon != 0) {
            android.graphics.drawable.Drawable d =
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), idIcon);
            if (d != null) { d.setBounds(0, 0, ic, ic); cb.setCompoundDrawables(d, null, null, null); }
        }
        cb.setOnCheckedChangeListener((b, v) -> {
            Modos.setNotifLinea(requireContext(), codigo, v);
            actualizarBotonMaestro(maestro);
        });
        if (codigo >= 1 && codigo <= 7) chkLineas[codigo] = cb;
        chkTodas.put(codigo, cb);
        cont.addView(cb);
    }

    private boolean algunaLineaActiva() {
        for (int c : chkTodas.keySet()) if (Modos.notifLinea(requireContext(), c)) return true;
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
        // Metrobús (1..7) + Mexibús/Mexicable (troncal/ramal/exprés/Mexicable): el simulador es de
        // prueba (Modo personalizado), así que siempre incluye Mexibús aunque el toggle esté apagado.
        final List<Linea> lineas = new ArrayList<>(GtfsRepository.getLineas(requireContext()));
        lineas.addAll(GtfsRepository.getMexibus(requireContext()));

        // Mexibús/Mexicable: su nombre ya es autodescriptivo ("Mexibús L4", "Mexicable L2"), no se
        // antepone "Línea 104" (mismo criterio que LinesAdapter).
        List<String> nombresL = new ArrayList<>();
        for (Linea l : lineas) nombresL.add(l.numero < 100 ? "Línea " + l.numero : l.nombre);
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
            Manifestaciones.simular(l.numero, Planificador.norm(es.toString()), term);
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
                Manifestaciones.simular(l.numero, Planificador.norm(l.estaciones.get(k).nombre), null);
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
     * Cambio de tipo de usuario y movilidad reducida desde Configuración. Pensado para quien
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

    /** Menú de descarga de audios offline: elige línea (o borra todo). */
    private void menuAudios() {
        final java.util.List<Integer> cod = new java.util.ArrayList<>();
        final java.util.List<String> nom = new java.util.ArrayList<>();
        final java.util.List<Integer> todas = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) { cod.add(i); nom.add("Metrobús L" + i); todas.add(i); }
        if (Modos.mostrarMexibus(requireContext())) {
            for (Linea l : GtfsRepository.getMexibus(requireContext())) {
                if ((l.numero >= 101 && l.numero <= 104) || (l.numero >= 111 && l.numero <= 113)) {
                    cod.add(l.numero);
                    nom.add("Mexibús L" + Planificador.etiquetaLineaCortaPub(l.numero));
                    todas.add(l.numero);
                }
            }
        }
        // Opciones extra al inicio (descargar todas) y al final (borrar).
        cod.add(0, -2); nom.add(0, getString(R.string.audios_todas));
        cod.add(-1);    nom.add(getString(R.string.audios_borrar));
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.audios_titulo)
                .setItems(nom.toArray(new String[0]), (d, w) -> {
                    int c = cod.get(w);
                    if (c == -1) {
                        int n = DescargaVoz.borrarTodo(requireContext());
                        android.widget.Toast.makeText(requireContext(),
                                getString(R.string.audios_borrados, n), android.widget.Toast.LENGTH_SHORT).show();
                    } else if (c == -2) {
                        descargarAudios(todas, getString(R.string.audios_todas));
                    } else {
                        descargarAudios(java.util.Collections.singletonList(c), nom.get(w));
                    }
                }).show();
    }

    /**
     * Descarga los audios de una o varias líneas mostrando el progreso. La descarga corre en
     * {@link DescargaVozService} (primer plano, con notificación de progreso) para que "descargar
     * todas las líneas" (cientos de peticiones, varios minutos) no se corte si la app pasa a
     * segundo plano. La "carta" solo REFLEJA el avance mientras está visible: cerrarla (o que la
     * app se vaya a segundo plano) no cancela nada, solo deja de actualizarse; el progreso real
     * se sigue viendo en la notificación hasta terminar.
     *
     * NO se precalcula aquí el total de textos (DescargaVoz.textosLinea() de TODAS las líneas):
     * eso recorre estaciones de todas las líneas cruzándolas entre sí para las correspondencias
     * (Locuciones.basesCorresp()), cientos de comparaciones para "descargar todas", y hacerlo en el
     * hilo principal antes de mostrar la carta bloqueaba la UI el tiempo suficiente para que Android
     * la reportara como "no responde" y la cerrara -- exactamente el mismo cálculo YA lo hace
     * DescargaVozService en su hilo de 2º plano; aquí solo se refleja cuando llegue el primer avance().
     */
    private void descargarAudios(java.util.List<Integer> lineas, String nombre) {
        if (DescargaVozService.corriendo) {
            Toast.makeText(requireContext(), R.string.audios_en_curso, Toast.LENGTH_SHORT).show();
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        final TextView tv = new TextView(requireContext());
        int p = Math.round(22 * dp);
        tv.setPadding(p, p, p, p);
        tv.setText(R.string.audios_preparando);
        final AlertDialog dlg = new AlertDialog.Builder(requireContext())
                .setTitle(nombre).setView(tv)
                .setNegativeButton(android.R.string.cancel, (d, w) -> DescargaVozService.cancelar(requireContext()))
                .create();
        dlg.setOnDismissListener(d -> DescargaVozService.setEscucha(null));   // deja de actualizar la carta; la descarga sigue
        dlgDescargaAudios = dlg;
        dlg.show();
        DescargaVozService.setEscucha(new DescargaVozService.Escucha() {
            @Override public void avance(int h, int t) {
                if (isAdded() && dlg.isShowing()) tv.setText(getString(R.string.audios_descargando, h, t));
            }
            @Override public void fin(int ok, int t) {
                if (dlg.isShowing()) dlg.dismiss();
                if (isAdded()) Toast.makeText(requireContext(),
                        getString(R.string.audios_listo, ok), Toast.LENGTH_SHORT).show();
            }
        });
        DescargaVozService.iniciar(requireContext(), lineas, nombre);
    }
}
