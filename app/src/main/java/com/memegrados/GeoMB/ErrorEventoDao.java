package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ErrorEventoDao {

    @Insert
    void insertar(ErrorEventoEntity e);

    @Query("SELECT COUNT(*) FROM errores WHERE recorridoId = :recorridoId")
    int contarDeRecorrido(long recorridoId);

    @Query("SELECT * FROM errores WHERE sincronizado = 0")
    List<ErrorEventoEntity> pendientesSync();

    /** Errores registrados en una ventana de tiempo (p. ej. ±5 min alrededor de un reporte), para
     *  correlacionarlos -- ver {@link DiagnosticoReporte}. */
    @Query("SELECT * FROM errores WHERE ts BETWEEN :desde AND :hasta ORDER BY ts")
    List<ErrorEventoEntity> enRango(long desde, long hasta);

    @Query("UPDATE errores SET sincronizado = 1 WHERE id = :id")
    void marcarSincronizado(long id);

    @Query("DELETE FROM errores WHERE sincronizado = 1 AND ts < :antesDe")
    void purgar(long antesDe);
}
