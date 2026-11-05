package com.memegrados.GeoMB;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

/**
 * Base de las pantallas del estilo CITI: la barra "título / Menú" (bar_menu.xml de la app de
 * Indra) y piezas comunes (cuadro de ruta, ícono de estación).
 */
public abstract class CitiPantalla extends Fragment {

    protected CitiActivity citi() {
        return (CitiActivity) requireActivity();
    }

    /** Pone el título de la barra; "Menú" regresa al inicio como en el original. */
    protected void barra(View raiz, int titulo) {
        ((TextView) raiz.findViewById(R.id.citi_titulo)).setText(titulo);
        raiz.findViewById(R.id.citi_btn_menu).setOnClickListener(v -> citi().irInicio());
    }

    /** Cuadro de ruta (box_route / route_box_list): radio 3dp en el color de la línea. */
    protected GradientDrawable cuadroRuta(int linea) {
        GradientDrawable fondo = new GradientDrawable();
        fondo.setCornerRadius(3 * getResources().getDisplayMetrics().density);
        Linea l = GtfsRepository.porNumero(requireContext(), linea);
        fondo.setColor(EstiloCiti.color(linea, l != null ? l.color : 0xFF9E9E9E));
        return fondo;
    }

    /** Pictograma de la estación o, si no tiene, el ícono genérico de parada del original. */
    protected void iconoEstacion(ImageView iv, CitiDatos.Est e, int dp) {
        Bitmap b = Iconos.pictograma(requireContext(), e.icono, (int) (dp * getResources().getDisplayMetrics().density));
        if (b != null) iv.setImageBitmap(b);
        else iv.setImageResource(R.drawable.citi_estacion);
    }

    /** "Línea 1" / "Mexibús 1" / "Mexicable 1", para el dato bajo el nombre de una estación. */
    protected String sistemaDe(int linea) {
        String n = CitiDatos.etiqueta(linea);
        switch (Sistemas.sistemaDe(linea)) {
            case Sistemas.MEXIBUS:   return "Mexibús " + n;
            case Sistemas.MEXICABLE: return "Mexicable " + n;
            default:                 return getString(R.string.linea_formato_txt, n);
        }
    }
}
