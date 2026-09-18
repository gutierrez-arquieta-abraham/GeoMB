package com.memegrados.GeoMB;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un error detectado por la app: reanclaje/corrección de GPS durante un recorrido, falla de red
 *  al pedir el feed de unidades, o una excepción no manejada. {@code recorridoId} es null si el
 *  error ocurrió fuera de un recorrido activo (p. ej. una falla de red del mapa en segundo plano). */
@Entity(tableName = "errores")
public class ErrorEventoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public Long recorridoId;   // null = sin recorrido activo
    public int tipo;           // Telemetria.ERR_REANCLAJE / ERR_RED / ERR_EXCEPCION
    public String contexto = "";
    public String mensaje = "";
    public long ts;
    public boolean sincronizado;
}
