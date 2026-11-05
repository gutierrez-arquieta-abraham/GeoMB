package com.memegrados.GeoMB;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.List;

/**
 * Captura una imagen satelital de un punto para adjuntarla a un reporte (ReportePdf).
 */
// ============================================================
// CLASE    : MapaSatelital
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// Las llaves de Google de la app solo están autorizadas para el SDK de Maps
// para Android (la API de mapas estáticos responde 403), así que la imagen se
// obtiene con ese mismo SDK: un MapView en modo "lite" y tipo satélite se
// agrega DETRÁS del contenido de la Activity (no se ve), se espera a que
// cargue y se toma su snapshot(). Después se quita.
//
// Si algo falla o tarda más de TIEMPO_MS, responde null y el reporte sigue
// sin imagen (con las coordenadas y el enlace a Google Maps).
// ============================================================
public final class MapaSatelital {

    public interface Listo { void con(Bitmap imagen); }

    private static final long TIEMPO_MS = 10_000;
    private static final int ANCHO_PX = 1000, ALTO_PX = 560;
    private static final float ZOOM = 17.5f;

    private MapaSatelital() {}

    /** Captura el punto {@code pos} (con su marcador y, si se da, el {@code tramo} de la línea en
     *  {@code colorTramo}). {@code cb} corre en el hilo principal, una sola vez. */
    public static void capturar(Activity a, LatLng pos, List<LatLng> tramo, int colorTramo, Listo cb) {
        Handler main = new Handler(Looper.getMainLooper());
        ViewGroup raiz = a != null && !a.isFinishing() ? a.findViewById(android.R.id.content) : null;
        if (raiz == null || pos == null) { cb.con(null); return; }

        GoogleMapOptions op = new GoogleMapOptions()
                .liteMode(true)
                .mapType(GoogleMap.MAP_TYPE_SATELLITE)
                .mapToolbarEnabled(false)
                .camera(CameraPosition.fromLatLngZoom(pos, ZOOM));
        MapView mv;
        try {
            mv = new MapView(a, op);
            raiz.addView(mv, 0, new FrameLayout.LayoutParams(ANCHO_PX, ALTO_PX));   // detrás del contenido
            mv.onCreate(null);
            mv.onStart();
            mv.onResume();
        } catch (Exception e) {
            cb.con(null);
            return;
        }
        final MapView vista = mv;
        final boolean[] hecho = {false};
        final Listo terminar = img -> {
            if (hecho[0]) return;
            hecho[0] = true;
            try { vista.onPause(); vista.onStop(); vista.onDestroy(); } catch (Exception ignore) {}
            raiz.removeView(vista);
            cb.con(img);
        };
        main.postDelayed(() -> terminar.con(null), TIEMPO_MS);
        vista.getMapAsync(mapa -> {
            try {
                if (tramo != null && tramo.size() > 1)
                    mapa.addPolyline(new PolylineOptions().addAll(tramo).color(colorTramo).width(10f));
                mapa.addMarker(new MarkerOptions().position(pos)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                mapa.setOnMapLoadedCallback(() -> mapa.snapshot(img -> main.post(() -> terminar.con(img))));
            } catch (Exception e) {
                main.post(() -> terminar.con(null));
            }
        });
    }
}
