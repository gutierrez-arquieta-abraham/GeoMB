package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RecorridoDao {

    @Insert
    long insertar(RecorridoEntity r);

    @Query("UPDATE recorridos SET finTs = :finTs WHERE id = :id")
    void finalizar(long id, long finTs);

    @Query("SELECT * FROM recorridos WHERE sincronizado = 0 AND finTs > 0")
    List<RecorridoEntity> pendientesSync();

    @Query("UPDATE recorridos SET sincronizado = 1 WHERE id = :id")
    void marcarSincronizado(long id);

    @Query("DELETE FROM recorridos WHERE sincronizado = 1 AND finTs > 0 AND finTs < :antesDe")
    void purgar(long antesDe);
}
