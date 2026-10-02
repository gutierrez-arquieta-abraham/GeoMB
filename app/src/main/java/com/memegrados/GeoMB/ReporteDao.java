package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ReporteDao {

    @Insert
    void insertar(ReporteEntity r);

    @Query("SELECT * FROM reportes_app WHERE reportId = :id")
    ReporteEntity obtener(String id);

    @Query("SELECT * FROM reportes_app WHERE sincronizado = 0 ORDER BY ts ASC")
    List<ReporteEntity> pendientesSync();

    @Query("UPDATE reportes_app SET sincronizado = 1 WHERE reportId = :id")
    void marcarSincronizado(String id);

    @Query("UPDATE reportes_app SET intentosSync = intentosSync + 1, ultimoIntentoSyncTs = :ts WHERE reportId = :id")
    void registrarIntento(String id, long ts);

    @Query("DELETE FROM reportes_app WHERE sincronizado = 1 AND ts < :antesDe")
    void purgar(long antesDe);
}
