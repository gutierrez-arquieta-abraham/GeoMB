package com.memegrados.GeoMB;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un número económico GUARDADO por el usuario (su lista de "unidades guardadas"/favoritas).
 *  Independiente de si esa unidad se está SIGUIENDO activamente en este momento ({@link
 *  SeguimientoService#sigue}): guardar/quitar de aquí nunca inicia ni detiene el seguimiento, y
 *  seguir/dejar de seguir nunca guarda ni quita de aquí. Reiniciar el teléfono NO reinicia el
 *  seguimiento de las unidades guardadas (ver {@link ArranqueReceiver}) -- eso requiere una
 *  acción explícita del usuario ("Seguir") cada vez. */
@Entity(tableName = "economicos_favoritos")
public class EconomicoFavoritoEntity {

    @PrimaryKey
    @NonNull
    public String economico = "";

    public long fechaGuardado;
    public boolean sincronizado;
}
