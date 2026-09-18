package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface BusquedaUnidadDao {

    @Insert
    void insertar(BusquedaUnidadEntity b);

    /** Económicos más buscados (para "mostrar resultados" en el panel de análisis). */
    @Query("SELECT economico, COUNT(*) as veces FROM busquedas_unidad "
            + "GROUP BY economico ORDER BY veces DESC LIMIT :top")
    List<ConteoBusqueda> masBuscadas(int top);

    @Query("SELECT * FROM busquedas_unidad WHERE sincronizado = 0")
    List<BusquedaUnidadEntity> pendientesSync();

    @Query("UPDATE busquedas_unidad SET sincronizado = 1 WHERE id = :id")
    void marcarSincronizado(long id);

    @Query("DELETE FROM busquedas_unidad WHERE sincronizado = 1 AND ts < :antesDe")
    void purgar(long antesDe);
}
