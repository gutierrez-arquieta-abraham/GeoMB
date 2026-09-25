package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Supplier;

/**
 * Tarjeta de resultado de una UNIDAD (view_carta_unidad.xml): rellena sus campos (número, línea,
 * ficha del catálogo, ruta/destino, badge de estado, foto+créditos) a partir de un UnidadReal
 * (o null si no está en servicio / sin conexión, mostrando solo el catálogo offline).
 *
 * Usada por MapFragment (buscador inline del mapa, único lugar de la app para buscar/seguir
 * unidades) para no reimplementar esta lógica: antes MapFragment tenía su propia validación/
 * consulta del feed y mostraba solo el tooltip genérico de Google Maps en vez de esta ficha.
 *
 * Los botones "Ver en mapa"/"Seguir" solo se muestran/ocultan aquí según haya o no unidad en vivo;
 * el LISTENER de cada uno lo pone MapFragment, porque su acción depende del contexto (permisos,
 * servicio de seguimiento).
 */
// ============================================================
// CLASE    : CartaUnidad   (subclase Vistas)
// PROYECTO : GeoMB
// ============================================================
public final class CartaUnidad {

    private CartaUnidad() {}

    /** Referencias a las vistas de view_carta_unidad.xml, resueltas una sola vez al inflar. */
    public static final class Vistas {
        public final MaterialCardView card;
        public final TextView txtUnidad, txtLinea, txtFicha, txtRuta, txtActualizacion, badgeEstado, txtCredito, txtTagline;
        public final ImageView imgUnidad;
        public final MaterialButton btnVerMapa, btnSeguir, btnAnadirSeguir, btnDetenerTodos;
        public final View filaSeguirMulti;

        public Vistas(View raiz) {
            card = raiz.findViewById(R.id.card_resultado);
            txtUnidad = raiz.findViewById(R.id.txt_unidad);
            txtLinea = raiz.findViewById(R.id.txt_linea);
            txtFicha = raiz.findViewById(R.id.txt_ficha);
            txtRuta = raiz.findViewById(R.id.txt_ruta);
            txtActualizacion = raiz.findViewById(R.id.txt_actualizacion);
            badgeEstado = raiz.findViewById(R.id.badge_estado);
            txtCredito = raiz.findViewById(R.id.txt_credito);
            txtTagline = raiz.findViewById(R.id.txt_tagline);
            imgUnidad = raiz.findViewById(R.id.img_unidad);
            btnVerMapa = raiz.findViewById(R.id.btn_ver_mapa);
            btnSeguir = raiz.findViewById(R.id.btn_seguir);
            btnAnadirSeguir = raiz.findViewById(R.id.btn_anadir_seguir);
            btnDetenerTodos = raiz.findViewById(R.id.btn_detener_todos);
            filaSeguirMulti = raiz.findViewById(R.id.fila_seguir_multi);
        }
    }

    /**
     * Rellena la tarjeta para el económico {@code numero}. {@code u} viene del feed en vivo, o es
     * null si no está en servicio o no hay conexión. {@code ecoVigente} se consulta al terminar de
     * bajar la foto (asíncrono): si para entonces ya no coincide con {@code numero} (el usuario
     * buscó otra unidad mientras tanto), la foto vieja NO se aplica.
     */
    public static void bind(Context ctx, Vistas v, String numero, UnidadReal u, Supplier<String> ecoVigente) {
        v.txtUnidad.setText(ctx.getString(R.string.unidad_numero, numero));

        // Ficha del catálogo (Drive) — disponible siempre, aunque no esté en servicio.
        Modelos.Ficha ficha = Modelos.paraEconomico(numero);
        String empresa = ficha.empresa;
        String mm = ficha.etiqueta();
        v.txtFicha.setText(Modelos.DESCONOCIDO.equals(mm) ? empresa : empresa + " · " + mm);

        mostrarImagen(v, ficha, numero, ecoVigente);

        v.badgeEstado.setVisibility(View.VISIBLE);
        if (u != null) {
            v.badgeEstado.setText(R.string.estado_en_ruta);
            v.txtLinea.setText(descripcionLinea(ctx, u));
            Ruta r = RutasRepository.porRouteId(u.ruta);
            if (r != null) {
                v.txtRuta.setText(ctx.getString(R.string.ruta_codigo_formato, r.codigo) + " · " + r.recorrido());
            } else if (u.destino != null && !u.destino.isEmpty()) {
                v.txtRuta.setText(ctx.getString(R.string.destino_formato, u.destino));
            } else {
                v.txtRuta.setText(R.string.estado_en_ruta);
            }
            v.txtRuta.setVisibility(View.VISIBLE);
            v.txtActualizacion.setText(u.placa != null && !u.placa.isEmpty()
                    ? ctx.getString(R.string.placa_formato, u.placa) : ctx.getString(R.string.estado_en_ruta));
            v.btnVerMapa.setVisibility(View.VISIBLE);
            v.btnSeguir.setVisibility(View.VISIBLE);
        } else {
            v.badgeEstado.setText(R.string.estado_fuera_servicio);
            v.txtLinea.setText(R.string.sin_ubicacion_vivo);
            v.txtRuta.setVisibility(View.GONE);
            v.txtActualizacion.setText(R.string.info_catalogo);
            v.btnVerMapa.setVisibility(View.GONE);
            v.btnSeguir.setVisibility(View.GONE);
        }

        aplicarTagline(ctx, v, u);
        v.card.setVisibility(View.VISIBLE);
    }

    /** Modo coqueto: reemplaza el tono por uno insinuante suave (solo si Modos.cachondo está activo). */
    private static void aplicarTagline(Context ctx, Vistas v, UnidadReal u) {
        if (!Modos.cachondo(ctx)) {
            v.txtTagline.setVisibility(View.GONE);
            return;
        }
        if (u != null) {
            String dest = (u.destino != null && !u.destino.isEmpty()) ? u.destino : "algún lugar";
            v.txtTagline.setText(ctx.getString(R.string.cachondo_tagline, dest));
        } else {
            v.txtTagline.setText(R.string.cachondo_tagline_off);
        }
        v.txtTagline.setVisibility(View.VISIBLE);
    }

    private static String descripcionLinea(Context ctx, UnidadReal u) {
        if (u.linea == null) return "Sin línea asignada";
        Linea l = GtfsRepository.porNumero(ctx, u.linea);
        String nombre = l != null ? l.nombre : "";
        if (u.linea >= 100) {
            // Mexibús/Mexicable: su nombre ya es autodescriptivo ("Mexibús L4"), no se antepone
            // "Línea 104"; se muestra además el par de terminales oficial en vez de repetir el nombre.
            String par = Planificador.terminalesMexibusPar(u.linea);
            return nombre + (par != null ? " · " + par : "");
        }
        // Metrobús: número PÚBLICO (no el crudo, "Línea 104") + nombre de la ruta.
        return ctx.getString(R.string.linea_formato_txt, Planificador.etiquetaLineaCortaPub(u.linea))
                + (nombre.isEmpty() ? "" : " · " + nombre);
    }

    /** Muestra la foto de la unidad (si el CSV trae URL) y sus créditos; descarga en hilo aparte. */
    private static void mostrarImagen(Vistas v, Modelos.Ficha ficha, String eco, Supplier<String> ecoVigente) {
        v.imgUnidad.setVisibility(View.GONE);
        v.txtCredito.setVisibility(View.GONE);
        String url = ficha.imagen;
        if (url == null || url.isEmpty()) return;

        v.txtCredito.setText(ficha.credito != null && !ficha.credito.isEmpty()
                ? v.txtCredito.getContext().getString(R.string.creditos_imagen_formato, ficha.credito)
                : v.txtCredito.getContext().getString(R.string.creditos_imagen_sin));
        v.txtCredito.setVisibility(View.VISIBLE);

        new Thread(() -> {
            Bitmap bmp = descargarBitmap(url);
            if (bmp == null) return;
            v.imgUnidad.post(() -> {
                if (ecoVigente != null && !eco.equals(ecoVigente.get())) return;   // resultado viejo
                v.imgUnidad.setImageBitmap(bmp);
                v.imgUnidad.setVisibility(View.VISIBLE);
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
}
