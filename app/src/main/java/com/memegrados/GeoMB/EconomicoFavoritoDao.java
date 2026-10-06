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

    /** Una sola fila (o null si no está guardada), para leer su preferencia de alerta/línea cacheada. */
    @Query("SELECT * FROM economicos_favoritos WHERE economico = :economico")
    EconomicoFavoritoEntity obtener(String economico);

    /** Solo la columna de alerta -- evita leer/escribir la fila completa cuando únicamente cambia esto. */
    @Query("UPDATE economicos_favoritos SET alertaActiva = :activa WHERE economico = :economico")
    void actualizarAlertaActiva(String economico, boolean activa);

    /** Solo el radio de aviso (250/500/1000 m). */
    @Query("UPDATE economicos_favoritos SET radioAlertaM = :radioM WHERE economico = :economico")
    void actualizarRadioAlerta(String economico, int radioM);

    /** Solo la caché de línea para presentación (ver EconomicoFavoritoEntity.linea) -- nunca se lee
     *  como fuente de verdad para routing/tracking/alertas. */
    @Query("UPDATE economicos_favoritos SET linea = :linea WHERE economico = :economico")
    void actualizarLinea(String economico, int linea);
}
