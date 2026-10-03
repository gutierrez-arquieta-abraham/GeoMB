package com.memegrados.GeoMB;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Mini base de datos LOCAL de la app (histórico de recorridos, tiempos por estación, errores,
 *  búsquedas, económicos favoritos y reportes técnicos de la app). Ver {@link Telemetria} para el
 *  punto de entrada de la telemetría general, {@link ReporteApp} para los reportes, y
 *  {@link TelemetriaSync} para la sincronización de ambos a Firestore. */
@Database(entities = {
        RecorridoEntity.class, EventoEstacionEntity.class, ErrorEventoEntity.class,
        BusquedaUnidadEntity.class, EconomicoFavoritoEntity.class, ReporteEntity.class
}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract RecorridoDao recorridoDao();
    public abstract EventoEstacionDao eventoEstacionDao();
    public abstract ErrorEventoDao errorEventoDao();
    public abstract BusquedaUnidadDao busquedaUnidadDao();
    public abstract EconomicoFavoritoDao economicoFavoritoDao();
    public abstract ReporteDao reporteDao();

    /** v1 -> v2: agrega la tabla "reportes_app" (ver {@link ReporteEntity}) sin tocar las tablas
     *  existentes -- ninguna columna de "recorridos"/"errores"/"eventos_estacion"/"busquedas_unidad"/
     *  "economicos_favoritos" cambia, así que no hay riesgo para el histórico local ya guardado. */
    static final Migration MIGRACION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `reportes_app` ("
                    + "`reportId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `estado` TEXT, "
                    + "`prioridad` TEXT, `categoria` TEXT, `descripcion` TEXT, `versionApp` TEXT, "
                    + "`versionCode` INTEGER NOT NULL, `fabricante` TEXT, `modelo` TEXT, "
                    + "`androidRelease` TEXT, `apiLevel` INTEGER NOT NULL, `arquitectura` TEXT, "
                    + "`conectividadTipo` TEXT, `internetValidado` INTEGER, "
                    + "`ultimaSincronizacionTs` INTEGER NOT NULL, `edadDatoMs` INTEGER NOT NULL, "
                    + "`gpsActivado` INTEGER, `permisoUbicacion` TEXT, `proveedorUbicacion` TEXT, "
                    + "`ubicacionTs` INTEGER NOT NULL, `lineaContexto` INTEGER NOT NULL, "
                    + "`sentidoContexto` TEXT, `estacionActual` TEXT, `estacionSiguiente` TEXT, "
                    + "`unidadContexto` TEXT, `estadoSeguimiento` TEXT, `backendEndpoint` TEXT, "
                    + "`backendHttpStatus` INTEGER NOT NULL, `backendLatenciaMs` INTEGER NOT NULL, "
                    + "`erroresCercanosJson` TEXT, `sincronizado` INTEGER NOT NULL, "
                    + "`intentosSync` INTEGER NOT NULL, `ultimoIntentoSyncTs` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`reportId`))");
        }
    };

    private static volatile AppDatabase instancia;

    public static AppDatabase get(Context c) {
        if (instancia == null) {
            synchronized (AppDatabase.class) {
                if (instancia == null) {
                    instancia = Room.databaseBuilder(c.getApplicationContext(),
                            AppDatabase.class, "geomb.db")
                            .addMigrations(MIGRACION_1_2)
                            .build();
                }
            }
        }
        return instancia;
    }
}
