package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EventoEstacionDao {

    @Insert
    void insertar(EventoEstacionEntity e);

    @Query("SELECT * FROM eventos_estacion WHERE recorridoId = :recorridoId ORDER BY ts")
    List<EventoEstacionEntity> deRecorrido(long recorridoId);

    @Query("SELECT * FROM eventos_estacion WHERE sincronizado = 0")
    List<EventoEstacionEntity> pendientesSync();

    @Query("UPDATE eventos_estacion SET sincronizado = 1 WHERE id = :id")
    void marcarSincronizado(long id);

    @Query("DELETE FROM eventos_estacion WHERE sincronizado = 1 AND ts < :antesDe")
    void purgar(long antesDe);
}
