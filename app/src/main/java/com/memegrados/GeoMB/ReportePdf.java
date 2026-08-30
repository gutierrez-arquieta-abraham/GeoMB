package com.memegrados.GeoMB;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import androidx.core.content.FileProvider;

import android.net.Uri;

import com.google.android.gms.maps.model.LatLng;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Genera una hoja membretada del reporte en PDF ("Enviado mediante GeoMB"), lista para adjuntar
 * al correo. Se dibuja en el dispositivo con {@link PdfDocument} (sin Word ni servidor).
 */
public final class ReportePdf {

    private static final int A4_W = 595, A4_H = 842, MARGEN = 48;
    private static final int ROJO = 0xFFCF102D;

    private ReportePdf() {}

    /** Crea el PDF y devuelve su Uri de FileProvider (o null si falla). */
    public static Uri generar(Context ctx, long momentoMs, int linea, String estacion, LatLng pos,
                              String unidadEco, String tipo, String descripcion,
                              String personalNombre, String personalCargo,
                              String reportanteNombre, String reportanteCorreo) {
        Date cuando = momentoMs > 0 ? new Date(momentoMs) : new Date();
        String fecha = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(cuando);

        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page page = doc.startPage(
                    new PdfDocument.PageInfo.Builder(A4_W, A4_H, 1).create());
            Canvas cv = page.getCanvas();

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            // ---- Membrete institucional: banda con logos Metrobús | GeoMB ----
            final int bandH = 72, logoH = 38;
            p.setColor(ROJO);
            cv.drawRect(0, 0, A4_W, bandH, p);                   // banda superior

            // Logos Metrobús y GeoMB VECTORIALES (VectorDrawable), rasterizados a alta resolución
            // e incrustados a resolución completa: nítidos a cualquier zoom.
            final int RENDER_H = 512;
            float lx = MARGEN;
            lx = dibujarLogo(cv, rasterizarVector(ctx, R.drawable.logo_mb_v, RENDER_H), lx, bandH, logoH);

            p.setColor(Color.WHITE);
            p.setStrokeWidth(2);
            cv.drawLine(lx, 20, lx, bandH - 20, p);             // divisor vertical
            lx += 12;

            dibujarLogo(cv, rasterizarVector(ctx, R.drawable.logo_geomb_v, RENDER_H), lx, bandH, logoH);

            // Icono de línea DESDE EL DRAWABLE (linea_1..7) con contorno negro alrededor.
            int idLinea = (linea >= 1 && linea <= 7)
                    ? ctx.getResources().getIdentifier("linea_" + linea, "drawable", ctx.getPackageName()) : 0;
            Bitmap li = idLinea != 0 ? BitmapFactory.decodeResource(ctx.getResources(), idLinea) : null;
            if (li != null) dibujarIconoLinea(cv, li, A4_W - MARGEN - 44, 14, 44);

            int y = bandH + 22;
            p.setColor(Color.DKGRAY);
            p.setTypeface(Typeface.SANS_SERIF);
            p.setTextSize(11);
            cv.drawText("Reporte ciudadano · Enviado mediante GeoMB", MARGEN, y, p);
            y += 18;

            p.setColor(0xFFDDDDDD);
            cv.drawRect(MARGEN, y, A4_W - MARGEN, y + 1, p);     // línea divisoria
            y += 24;

            // ---- Título ----
            p.setColor(Color.BLACK);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(16);
            cv.drawText("Reporte de irregularidad", MARGEN, y, p);
            y += 28;

            // ---- Datos (etiqueta + valor) ----
            y = campo(cv, p, y, "Fecha y hora", fecha);
            y = campo(cv, p, y, "Tipo", tipo);
            if (linea > 0) y = campo(cv, p, y, "Línea", "Línea " + linea);
            if (noVacio(unidadEco)) y = campo(cv, p, y, "Unidad (económico)", unidadEco);
            if (noVacio(personalCargo) || noVacio(personalNombre)) {
                String v = (noVacio(personalCargo) ? personalCargo : "")
                        + (noVacio(personalNombre) ? (noVacio(personalCargo) ? " · " : "") + personalNombre : "");
                y = campo(cv, p, y, "Personal reportado", v);
            }
            if (noVacio(estacion)) {
                String v = estacion + (pos != null
                        ? String.format(Locale.US, "  (%.5f, %.5f)", pos.latitude, pos.longitude) : "");
                y = campo(cv, p, y, "Estación / ubicación", v);
            }
            y += 8;

            // ---- Descripción (multilínea) ----
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setColor(0xFF666666);
            p.setTextSize(10);
            cv.drawText("Descripción", MARGEN, y, p);
            y += 16;
            p.setTypeface(Typeface.SANS_SERIF);
            p.setColor(Color.BLACK);
            p.setTextSize(12);
            y = parrafo(cv, p, y, noVacio(descripcion) ? descripcion : "—", A4_W - 2 * MARGEN);
            y += 12;

            cv.drawText("Se adjunta evidencia (foto, video o audio) que describe la irregularidad.", MARGEN, y, p);
            y += 30;

            // ---- Firma / reportante ----
            p.setColor(0xFFDDDDDD);
            cv.drawRect(MARGEN, y, A4_W - MARGEN, y + 1, p);
            y += 22;
            p.setColor(Color.BLACK);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            cv.drawText("Atentamente,", MARGEN, y, p);
            y += 18;
            p.setTypeface(Typeface.SANS_SERIF);
            if (noVacio(reportanteNombre)) { cv.drawText(reportanteNombre, MARGEN, y, p); y += 16; }
            if (noVacio(reportanteCorreo)) { cv.drawText(reportanteCorreo, MARGEN, y, p); y += 16; }

            // ---- Pie ----
            p.setColor(0xFF999999);
            p.setTextSize(9);
            cv.drawText("Documento generado automáticamente por GeoMB · Identidad verificada",
                    MARGEN, A4_H - 30, p);

            doc.finishPage(page);

            File dir = new File(ctx.getCacheDir(), "reportes");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "reporte_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream fo = new FileOutputStream(f)) { doc.writeTo(fo); }
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        } catch (Exception e) {
            return null;
        } finally {
            doc.close();
        }
    }

    private static int campo(Canvas cv, Paint p, int y, String etiqueta, String valor) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(0xFF666666);
        p.setTextSize(10);
        cv.drawText(etiqueta, MARGEN, y, p);
        p.setTypeface(Typeface.SANS_SERIF);
        p.setColor(Color.BLACK);
        p.setTextSize(12);
        cv.drawText(valor != null ? valor : "—", MARGEN + 150, y, p);
        return y + 20;
    }

    /** Dibuja un párrafo con salto de línea por ancho. Devuelve la nueva Y. */
    private static int parrafo(Canvas cv, Paint p, int y, String texto, int ancho) {
        String[] palabras = texto.replace("\n", " \n ").split(" ");
        StringBuilder linea = new StringBuilder();
        for (String w : palabras) {
            if (w.equals("\n")) { cv.drawText(linea.toString(), MARGEN, y, p); y += 16; linea.setLength(0); continue; }
            String prueba = linea.length() == 0 ? w : linea + " " + w;
            if (p.measureText(prueba) > ancho) {
                cv.drawText(linea.toString(), MARGEN, y, p);
                y += 16;
                linea = new StringBuilder(w);
            } else {
                linea = new StringBuilder(prueba);
            }
        }
        if (linea.length() > 0) { cv.drawText(linea.toString(), MARGEN, y, p); y += 16; }
        return y;
    }

    /**
     * Dibuja un logo con altura fija {@code h}, centrado verticalmente en una banda de alto
     * {@code bandH}, incrustando el bitmap ORIGINAL (sin reescalar antes) para que quede nítido
     * al hacer zoom en el PDF. Devuelve la X siguiente (después del logo + separación).
     */
    /** Rasteriza un VectorDrawable a un bitmap de altura {@code alturaPx} (conserva proporción). */
    private static Bitmap rasterizarVector(Context ctx, int resId, int alturaPx) {
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(ctx, resId);
        if (d == null || d.getIntrinsicHeight() <= 0) return null;
        int w = Math.max(1, Math.round(alturaPx * d.getIntrinsicWidth() / (float) d.getIntrinsicHeight()));
        Bitmap bmp = Bitmap.createBitmap(w, alturaPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, alturaPx);
        d.draw(c);
        return bmp;
    }

    /**
     * Dibuja el icono de línea en un cuadro de lado {@code lado} con un contorno NEGRO que sigue
     * su silueta: tinta el bitmap de negro y lo dibuja con leves desfases alrededor, luego el
     * icono a color encima.
     */
    private static void dibujarIconoLinea(Canvas cv, Bitmap icono, float x, float y, int lado) {
        Paint negro = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        negro.setColorFilter(new android.graphics.PorterDuffColorFilter(
                0xFF000000, android.graphics.PorterDuff.Mode.SRC_IN));
        float o = 1.6f;
        float[][] off = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o, -o}, {o, -o}, {-o, o}, {o, o}};
        for (float[] dxy : off)
            cv.drawBitmap(icono, null, new android.graphics.RectF(
                    x + dxy[0], y + dxy[1], x + lado + dxy[0], y + lado + dxy[1]), negro);
        cv.drawBitmap(icono, null, new android.graphics.RectF(x, y, x + lado, y + lado), null);
    }

    private static float dibujarLogo(Canvas cv, Bitmap b, float x, int bandH, int h) {
        if (b == null || b.getHeight() <= 0) return x;
        float w = h * b.getWidth() / (float) b.getHeight();
        float top = (bandH - h) / 2f;
        cv.drawBitmap(b, null, new android.graphics.RectF(x, top, x + w, top + h), null);
        return x + w + 12;
    }

    private static boolean noVacio(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
