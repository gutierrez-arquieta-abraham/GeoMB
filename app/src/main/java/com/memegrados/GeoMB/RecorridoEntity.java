package com.memegrados.GeoMB;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un recorrido realizado (de {@link PlanificadorFragment}/{@link RecorridoService}): origen,
 *  destino y cuándo empezó/terminó. Las estaciones intermedias van en {@link EventoEstacionEntity}
 *  y los errores en {@link ErrorEventoEntity}, ligados por {@code id}. */
@Entity(tableName = "recorridos")
public class RecorridoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String origen = "";
    public String destino = "";
    public int totalEstaciones;
    public long inicioTs;
    public long finTs;          // 0 = aún no ha terminado (o se abandonó sin llegar)
    public boolean sincronizado;
}
