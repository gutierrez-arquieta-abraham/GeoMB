package com.memegrados.GeoMB;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Mini base de datos LOCAL de la app (histórico de recorridos, tiempos por estación, errores,
 *  búsquedas y económicos favoritos). Ver {@link Telemetria} para el punto de entrada y
 *  {@link TelemetriaSync} para la sincronización a Firestore. */
@Database(entities = {
        RecorridoEntity.class, EventoEstacionEntity.class, ErrorEventoEntity.class,
        BusquedaUnidadEntity.class, EconomicoFavoritoEntity.class
}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract RecorridoDao recorridoDao();
    public abstract EventoEstacionDao eventoEstacionDao();
    public abstract ErrorEventoDao errorEventoDao();
    public abstract BusquedaUnidadDao busquedaUnidadDao();
    public abstract EconomicoFavoritoDao economicoFavoritoDao();

    private static volatile AppDatabase instancia;

    public static AppDatabase get(Context c) {
        if (instancia == null) {
            synchronized (AppDatabase.class) {
                if (instancia == null) {
                    instancia = Room.databaseBuilder(c.getApplicationContext(),
                            AppDatabase.class, "geomb.db").build();
                }
            }
        }
        return instancia;
    }
}
