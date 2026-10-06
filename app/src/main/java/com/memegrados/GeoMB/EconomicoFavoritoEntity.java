package com.memegrados.GeoMB;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Un número económico GUARDADO por el usuario (su lista de "unidades guardadas"/favoritas).
 *  Independiente de si esa unidad se está SIGUIENDO activamente en este momento ({@link
 *  SeguimientoService#sigue}): guardar/quitar de aquí nunca inicia ni detiene el seguimiento, y
 *  seguir/dejar de seguir nunca guarda ni quita de aquí. Reiniciar el teléfono NO reinicia el
 *  seguimiento de las unidades guardadas (ver {@link ArranqueReceiver}) -- eso requiere una
 *  acción explícita del usuario ("Seguir") cada vez. */
@Entity(tableName = "economicos_favoritos")
public class EconomicoFavoritoEntity {

    @PrimaryKey
    @NonNull
    public String economico = "";

    public long fechaGuardado;
    public boolean sincronizado;

    /** ☑ "Avisarme cuando esté cerca" para esta unidad guardada. Solo almacena la preferencia --
     *  no hay (todavía) ninguna detección de proximidad ni envío de FCM que la lea. */
    public boolean alertaActiva = false;

    /** Radio de aviso en metros (250/500/1000; 500 por defecto) para cuando {@link #alertaActiva}
     *  esté activa. Sin efecto hasta que exista el mecanismo de detección. */
    public int radioAlertaM = 500;

    /** Línea CACHEADA solo para mostrarla en la lista de guardadas sin depender de que la unidad
     *  esté en el feed en ese momento (p. ej. "Línea 4" aunque ahora mismo no esté en servicio).
     *  0 = desconocida. NUNCA es fuente de verdad: no se usa para detectar proximidad, decidir a
     *  qué línea pertenece una unidad, disparar alertas, seguimiento, ni planificación de rutas --
     *  para eso siempre se consulta el feed en vivo (UnidadReal) o el catálogo real. Se refresca
     *  oportunistamente cuando la unidad vuelve a aparecer en el feed con datos actualizados. */
    public int linea = 0;
}
