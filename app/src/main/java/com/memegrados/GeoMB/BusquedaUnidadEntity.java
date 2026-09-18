package com.memegrados.GeoMB;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Una búsqueda de número económico en {@link SearchFragment}, para poder calcular las unidades
 *  más buscadas (agrupando por {@code economico} en {@link BusquedaUnidadDao#masBuscadas}). */
@Entity(tableName = "busquedas_unidad")
public class BusquedaUnidadEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String economico = "";
    public long ts;
    public boolean sincronizado;
}
