package com.memegrados.GeoMB;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un número económico guardado por el usuario para que {@link SeguimientoService} lo avise
 *  automáticamente al arrancar la app (o el teléfono, vía {@link ArranqueReceiver}) sin tener que
 *  volver a buscarlo y darle "seguir" cada vez. */
@Entity(tableName = "economicos_favoritos")
public class EconomicoFavoritoEntity {

    @PrimaryKey
    @NonNull
    public String economico = "";

    public long fechaGuardado;
    public boolean sincronizado;
}
