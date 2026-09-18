package com.memegrados.GeoMB;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Hora de llegada o salida a una estación DENTRO de un {@link RecorridoEntity} (id ligado por
 *  {@code recorridoId}), tal como las detecta {@link RecorridoService} por GPS. */
@Entity(tableName = "eventos_estacion")
public class EventoEstacionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long recorridoId;
    public String estacion = "";
    public int linea;
    public String tipo = "";   // "llegada" | "salida"
    public long ts;
    public boolean sincronizado;
}
