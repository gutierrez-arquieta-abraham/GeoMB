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
}, version = 3, exportSchema = false)
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

    /** v2 -> v3: agrega a "economicos_favoritos" las columnas de preferencia de alerta de
     *  proximidad ({@code alertaActiva}, {@code radioAlertaM}) y una caché de {@code linea} solo
     *  para presentación (ver EconomicoFavoritoEntity) -- ALTER TABLE puro, no recrea ni vacía la
     *  tabla, así que ningún económico guardado se pierde. Las filas que ya existían quedan con
     *  los defaults (alertaActiva=0/false, radioAlertaM=500, linea=0/desconocida) porque no había
     *  manera confiable de inferir esos valores retroactivamente. Todavía NO hay ningún mecanismo
     *  que lea estas columnas (ni detección de proximidad ni FCM): solo queda guardado el esquema. */
    static final Migration MIGRACION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `economicos_favoritos` ADD COLUMN `alertaActiva` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `economicos_favoritos` ADD COLUMN `radioAlertaM` INTEGER NOT NULL DEFAULT 500");
            db.execSQL("ALTER TABLE `economicos_favoritos` ADD COLUMN `linea` INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static volatile AppDatabase instancia;

    public static AppDatabase get(Context c) {
        if (instancia == null) {
            synchronized (AppDatabase.class) {
                if (instancia == null) {
                    instancia = Room.databaseBuilder(c.getApplicationContext(),
                            AppDatabase.class, "geomb.db")
                            .addMigrations(MIGRACION_1_2, MIGRACION_2_3)
                            .build();
                }
            }
        }
        return instancia;
    }
}
