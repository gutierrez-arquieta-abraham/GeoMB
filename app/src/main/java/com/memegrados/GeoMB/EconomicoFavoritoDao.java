package com.memegrados.GeoMB;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EconomicoFavoritoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertar(EconomicoFavoritoEntity f);

    @Query("DELETE FROM economicos_favoritos WHERE economico = :economico")
    void borrar(String economico);

    @Query("SELECT * FROM economicos_favoritos ORDER BY fechaGuardado DESC")
    List<EconomicoFavoritoEntity> listar();

    @Query("SELECT EXISTS(SELECT 1 FROM economicos_favoritos WHERE economico = :economico)")
    boolean existe(String economico);

    @Query("SELECT * FROM economicos_favoritos WHERE sincronizado = 0")
    List<EconomicoFavoritoEntity> pendientesSync();

    @Query("UPDATE economicos_favoritos SET sincronizado = 1 WHERE economico = :economico")
    void marcarSincronizado(String economico);
}
