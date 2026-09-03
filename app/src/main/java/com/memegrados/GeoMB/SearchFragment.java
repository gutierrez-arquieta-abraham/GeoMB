package com.memegrados.GeoMB;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Buscador de unidad por número económico. Permite ver la unidad en el mapa
 * o "Seguir" (aviso de cercanía a 500 m mediante SeguimientoService).
 */
public class SearchFragment extends Fragment {

    private MaterialButton btnSeguir;
    private MaterialButton btnVerMapa;
    private MaterialButton btnBuscar;
    private MaterialButton btnAnadir, btnDetenerTodos;
    private View filaSeguirMulti;
    private TextInputEditText inputUnidad;
    private MaterialCardView cardResultado;
    private TextView txtUnidad, txtLinea, txtFicha, txtRuta, txtActualizacion, badgeEstado, txtCredito, txtTagline;
    private ImageView imgUnidad;
    private String ecoActual;   // económico mostrado en la tarjeta

    private final ActivityResultLauncher<String> permisoUbicacion =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) intentarSeguir();
                else if (isAdded()) Toast.makeText(requireContext(),
                        getString(R.string.seguir_permiso_ubicacion), Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> permisoNotif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> arrancarSeguimiento());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        inputUnidad = view.findViewById(R.id.input_unidad);
        btnBuscar = view.findViewById(R.id.btn_buscar);
        cardResultado = view.findViewById(R.id.card_resultado);
        txtUnidad = view.findViewById(R.id.txt_unidad);
        txtLinea = view.findViewById(R.id.txt_linea);
        txtFicha = view.findViewById(R.id.txt_ficha);
        txtRuta = view.findViewById(R.id.txt_ruta);
        txtActualizacion = view.findViewById(R.id.txt_actualizacion);
        badgeEstado = view.findViewById(R.id.badge_estado);
        imgUnidad = view.findViewById(R.id.img_unidad);
        txtCredito = view.findViewById(R.id.txt_credito);
        txtTagline = view.findViewById(R.id.txt_tagline);
        btnVerMapa = view.findViewById(R.id.btn_ver_mapa);
        btnSeguir = view.findViewById(R.id.btn_seguir);
        filaSeguirMulti = view.findViewById(R.id.fila_seguir_multi);
        btnAnadir = view.findViewById(R.id.btn_anadir_seguir);
        btnDetenerTodos = view.findViewById(R.id.btn_detener_todos);
        btnAnadir.setOnClickListener(v -> { if (ecoActual != null && !SeguimientoService.sigue(ecoActual)) intentarSeguir(); });
        btnDetenerTodos.setOnClickListener(v -> detenerTodos());

        cardResultado.setVisibility(View.GONE);

        btnBuscar.setOnClickListener(v -> ejecutarBusqueda(
                inputUnidad.getText() != null ? inputUnidad.getText().toString().trim() : ""));

        btnSeguir.setOnClickListener(v -> {
            if (ecoActual == null) return;
            if (SeguimientoService.sigue(ecoActual)) {
                detenerSeguimiento();
            } else {
                intentarSeguir();   // añade esta unidad (sin quitar las que ya se siguen)
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        actualizarBotonSeguir();
        // Si el buscador del mapa mandó un económico (no estaba en vivo), búscalo aquí.
        if (RealtimeRepository.ecoParaBuscar != null) {
            String eco = RealtimeRepository.ecoParaBuscar;
            RealtimeRepository.ecoParaBuscar = null;
            if (inputUnidad != null) inputUnidad.setText(eco);
            ejecutarBusqueda(eco);
        }
    }

    /** Busca un económico: valida el límite y consulta el feed (o muestra la ficha offline). */
    private void ejecutarBusqueda(String numero) {
        if (numero == null || numero.isEmpty()) return;
        if (inputUnidad != null) ocultarTeclado(inputUnidad);

        int max = Modelos.maxEconomico();
        try {
            int n = Integer.parseInt(numero.replaceAll("[^0-9]", ""));
            if (max > 0 && n > max) {
                Toast.makeText(requireContext(),
                        getString(R.string.eco_max, max), Toast.LENGTH_LONG).show();
                return;
            }
        } catch (NumberFormatException ignore) {}

        final String eco = numero;
        btnBuscar.setEnabled(false);
        RealtimeRepository.get().fetch(new RealtimeRepository.Callback() {
            @Override
            public void onData(List<UnidadReal> unidades) {
                if (!isAdded()) return;
                btnBuscar.setEnabled(true);
                mostrarInfo(eco, RealtimeRepository.get().buscar(eco));
            }

            @Override
            public void onError(String mensaje) {
                if (!isAdded()) return;
                btnBuscar.setEnabled(true);
                mostrarInfo(eco, null);   // sin conexión: ficha del catálogo (offline)
            }
        });
    }

    /** Verifica permisos y arranca el seguimiento del económico actual. */
    private void intentarSeguir() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoNotif.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        arrancarSeguimiento();
    }

    private void arrancarSeguimiento() {
        if (!isAdded() || ecoActual == null) return;
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .putExtra(SeguimientoService.EXTRA_ECO, ecoActual);
        ContextCompat.startForegroundService(requireContext(), i);
        SeguimientoService.ecosSeguidos.add(ecoActual);   // reflejo inmediato en la UI
        actualizarBotonSeguir();
        Toast.makeText(requireContext(),
                getString(R.string.seguir_activado, ecoActual), Toast.LENGTH_LONG).show();
    }

    private void detenerSeguimiento() {
        if (ecoActual == null) return;
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .setAction(SeguimientoService.ACCION_DETENER)
                .putExtra(SeguimientoService.EXTRA_ECO, ecoActual);   // detiene SOLO esta unidad
        requireContext().startService(i);
        SeguimientoService.ecosSeguidos.remove(ecoActual);
        actualizarBotonSeguir();
    }

    /** Detiene el seguimiento de TODAS las unidades. */
    private void detenerTodos() {
        Intent i = new Intent(requireContext(), SeguimientoService.class)
                .setAction(SeguimientoService.ACCION_DETENER);   // sin económico = todas
        requireContext().startService(i);
        SeguimientoService.ecosSeguidos.clear();
        actualizarBotonSeguir();
    }

    private void actualizarBotonSeguir() {
        if (btnSeguir == null) return;
        boolean sigueEsta = ecoActual != null && SeguimientoService.sigue(ecoActual);
        btnSeguir.setText(sigueEsta ? R.string.dejar_de_seguir : R.string.seguir);
        // Fila de acciones múltiples: solo cuando ya hay unidad(es) en seguimiento en curso.
        if (filaSeguirMulti != null) {
            boolean hay = !SeguimientoService.ecosSeguidos.isEmpty();
            filaSeguirMulti.setVisibility(hay ? View.VISIBLE : View.GONE);
            if (btnAnadir != null) btnAnadir.setEnabled(ecoActual != null && !sigueEsta);   // añadir la actual
        }
    }

    /**
     * Muestra la ficha del económico. Si {@code u} viene del feed, agrega los
     * datos en vivo (línea, ruta, placa) y habilita Ver en mapa / Seguir; si es
     * null (no en servicio o sin conexión), muestra solo el catálogo.
     */
    private void mostrarInfo(String numero, UnidadReal u) {
        ecoActual = numero;
        txtUnidad.setText(getString(R.string.unidad_numero, numero));

        // Ficha del catálogo (Drive) — disponible siempre, aunque no esté en servicio.
        Modelos.Ficha ficha = Modelos.paraEconomico(numero);
        String empresa = ficha.empresa;
        String mm = ficha.etiqueta();
        txtFicha.setText(Modelos.DESCONOCIDO.equals(mm) ? empresa : empresa + " · " + mm);

        mostrarImagen(ficha, numero);

        badgeEstado.setVisibility(View.VISIBLE);
        if (u != null) {
            badgeEstado.setText(R.string.estado_en_ruta);
            txtLinea.setText(descripcionLinea(u));
            Ruta r = RutasRepository.porRouteId(u.ruta);
            if (r != null) {
                txtRuta.setText(getString(R.string.ruta_codigo_formato, r.codigo) + " · " + r.recorrido());
            } else if (u.destino != null && !u.destino.isEmpty()) {
                txtRuta.setText(getString(R.string.destino_formato, u.destino));
            } else {
                txtRuta.setText(R.string.estado_en_ruta);
            }
            txtRuta.setVisibility(View.VISIBLE);
            txtActualizacion.setText(u.placa != null && !u.placa.isEmpty()
                    ? getString(R.string.placa_formato, u.placa) : getString(R.string.estado_en_ruta));
            btnVerMapa.setVisibility(View.VISIBLE);
            btnSeguir.setVisibility(View.VISIBLE);
            btnVerMapa.setOnClickListener(b -> {
                RealtimeRepository.unidadSeleccionada = numero;
                ((MainActivity) requireActivity()).navegarA(R.id.nav_mapa);
            });
        } else {
            badgeEstado.setText(R.string.estado_fuera_servicio);
            txtLinea.setText(R.string.sin_ubicacion_vivo);
            txtRuta.setVisibility(View.GONE);
            txtActualizacion.setText(R.string.info_catalogo);
            btnVerMapa.setVisibility(View.GONE);
            btnSeguir.setVisibility(View.GONE);
        }

        aplicarTagline(u);

        cardResultado.setVisibility(View.VISIBLE);
        actualizarBotonSeguir();
    }

    /** Modo coqueto (búsqueda): reemplaza el tono por uno insinuante suave. */
    private void aplicarTagline(UnidadReal u) {
        if (!Modos.cachondo(requireContext())) {
            txtTagline.setVisibility(View.GONE);
            return;
        }
        if (u != null) {
            String dest = (u.destino != null && !u.destino.isEmpty()) ? u.destino : "algún lugar";
            txtTagline.setText(getString(R.string.cachondo_tagline, dest));
        } else {
            txtTagline.setText(R.string.cachondo_tagline_off);
        }
        txtTagline.setVisibility(View.VISIBLE);
    }

    private String descripcionLinea(UnidadReal u) {
        if (u.linea == null) return "Sin línea asignada";
        Linea l = GtfsRepository.porNumero(requireContext(), u.linea);
        String nombre = l != null ? l.nombre : "";
        return getString(R.string.linea_formato, u.linea) + (nombre.isEmpty() ? "" : " · " + nombre);
    }

    /** Muestra la foto de la unidad (si el CSV trae URL) y sus créditos. */
    private void mostrarImagen(Modelos.Ficha ficha, String eco) {
        imgUnidad.setVisibility(View.GONE);
        txtCredito.setVisibility(View.GONE);
        String url = ficha.imagen;
        if (url == null || url.isEmpty()) return;

        txtCredito.setText(ficha.credito != null && !ficha.credito.isEmpty()
                ? getString(R.string.creditos_imagen_formato, ficha.credito)
                : getString(R.string.creditos_imagen_sin));
        txtCredito.setVisibility(View.VISIBLE);

        new Thread(() -> {
            Bitmap bmp = descargarBitmap(url);
            if (bmp == null || imgUnidad == null) return;
            imgUnidad.post(() -> {
                if (!isAdded() || !eco.equals(ecoActual)) return;   // resultado viejo
                imgUnidad.setImageBitmap(bmp);
                imgUnidad.setVisibility(View.VISIBLE);
            });
        }, "img-unidad").start();
    }

    /** Tamaño máximo (px) al que se reduce la foto para no gastar memoria de más. */
    private static final int IMG_MAX_PX = 1080;

    private static Bitmap descargarBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;

            byte[] datos;
            try (InputStream is = c.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                datos = bos.toByteArray();
            }

            // 1) Lee solo las dimensiones. 2) Decodifica reducido (downsampling).
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
            int s = 1;
            while (o.outWidth / s > IMG_MAX_PX || o.outHeight / s > IMG_MAX_PX) s *= 2;
            o.inSampleSize = s;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void ocultarTeclado(View v) {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
