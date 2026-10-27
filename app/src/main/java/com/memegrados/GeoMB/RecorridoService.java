package com.memegrados.GeoMB;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.List;
import java.util.Locale;

/**
 * Rastreo de "recorrido" (estilo Maps): sigue la ubicación del usuario por la secuencia
 * de estaciones y muestra una notificación (diseño propio, en pantalla de bloqueo y centro)
 * con la PRÓXIMA ESTACIÓN, origen/destino y pictograma, más avisos de voz (TTS) de
 * próxima estación, transbordo, llegada y afectaciones del servicio.
 */
// ============================================================
// CLASE    : RecorridoService   (extends Service)
// PROYECTO : GeoMB
// ============================================================
//
// DESCRIPCIÓN:
//
// El "modo recorrido" estilo Google Maps: es un SERVICIO EN PRIMER PLANO
// que sigue la ubicación del usuario a lo largo de la secuencia de
// estaciones de su ruta y lo va GUIANDO POR VOZ y con una notificación.
//
// QUÉ HACE:
//   - Muestra una notificación (diseño propio, visible en pantalla de
//     bloqueo) con la PRÓXIMA ESTACIÓN, origen/destino y pictograma.
//   - Da avisos de voz (TTS): próxima estación, llegada, transbordo/
//     correspondencia/conexión, cambio de servicio y afectaciones.
//   - Usa la voz "Mia" (AWS Polly) cacheada; si no hay, la voz local del
//     teléfono como respaldo.
//   - Pega el puntero al trazo (snap) y tiene brújula propia.
//
// ¿POR QUÉ SERVICIO EN PRIMER PLANO? Para seguir funcionando (GPS + voz)
// aunque el usuario apague la pantalla o cambie de app (onTaskRemoved lo
// mantiene). Es la clase MÁS GRANDE del proyecto: coordina ubicación,
// audio, notificación y la lógica de "¿qué aviso toca ahora?".
//
// (El detalle fino de radios/avisos está documentado en MEMORIA.md.)
// ============================================================
public class RecorridoService extends Service {

    public static final String ACCION_DETENER = "detener_recorrido";
    private static final String CANAL = "recorrido";
    private static final int ID = 4401;
    // MISMA fórmula para Metrobús y Mexibús (Haversine): a ≤50 m del punto avisa la LLEGADA; y al
    // alejarse ~70 m del punto más cercano (cruzando hacia la siguiente) avisa la PRÓXIMA estación.
    private static final float CERCA_M = 50f;   // "llegando a estación" cuando estás a ≤50 m del punto
    private static final float CERCA_MXB_M = 50f;
    // Anillo de cobertura extra: dentro de (llegada 50 m + 20 m) NO se anuncia la próxima estación,
    // solo la llegada. La "próxima" se dispara al SALIR de esos ~70 m del punto más cercano.
    private static final float COBERTURA_EXTRA_M = 20f;
    // En una correspondencia, el aviso cambia de línea SOLO cuando estás en el andén de la otra línea.
    // ~15 m (el usuario pidió <10 m; se deja un poco más por el error típico del GPS para no quedar clavado).
    private static final float CAMBIO_LINEA_M = 15f;
    // Fin de recorrido: el "llegaste" suena al llegar al punto de la estación final. Se usa un radio
    // ALCANZABLE por GPS (≈30 m; 5 m casi nunca se cumple y el aviso no sonaba). Excepción: Mexibús L4
    // (Indios Verdes) son 2 andenes y la unidad avanza hasta el fondo rebasando el punto → radio normal.
    private static final float FIN_M = 30f;
    private static final long INTERVALO_MS = 1000L;   // revisa la ubicación cada 1 s durante el recorrido
    // Cadencia de GPS ADAPTATIVA: a más de CADENCIA_LEJOS_M de la próxima parada a anunciar (normalmente
    // varias cuadras, no unos pasos) se relaja el muestreo a INTERVALO_LEJOS_MS en vez de INTERVALO_MS.
    // El GPS de alta precisión encendido de forma continua es lo que más batería consume y calienta el
    // equipo en recorridos largos; la mayor parte del trayecto NO necesita precisión al segundo, solo el
    // tramo final de acercamiento (donde SÍ se vuelve a INTERVALO_MS para no perder el aviso "casi
    // instantáneo"). Se usan 500 m porque a la velocidad típica de un camión eso son varias decenas de
    // segundos: de sobra para volver a cadencia rápida antes de necesitar precisión real.
    private static final float CADENCIA_LEJOS_M = 500f;
    private static final long INTERVALO_LEJOS_MS = 3000L;
    private static final float TURURU_VOL = 0.7f;     // volumen del "tururu" (70% del real)
    /** Volumen relativo de la VOZ (Mia/TTS) dentro del volumen de medios del dispositivo. Antes se
     *  forzaba a 1.0 (máximo) sin importar el volumen que el usuario tuviera puesto: como el audio
     *  de Mia y la voz del sistema vienen "masterizados" más fuerte que música/podcasts normales,
     *  a un mismo nivel del volumen de medios (p. ej. 20%) sonaban como si el dispositivo estuviera
     *  mucho más arriba (p. ej. 80%), obligando a bajarle manualmente durante el aviso. Se atenúa
     *  igual que el "tururu" para que respete proporcionalmente el volumen ya puesto por el usuario. */
    private static final float VOZ_VOL = 0.7f;

    /** Atenuación extra cuando el audio sale por el ALTAVOZ interno (sin audífonos/bluetooth): a un
     *  mismo índice de volumen de medios, el altavoz suena bastante más fuerte que unos audífonos, así
     *  que el 0.7 de VOZ_VOL/TURURU_VOL (pensado para audífonos) no basta ahí y el aviso sale exagerado
     *  -- el usuario reportó tener que bajarle manualmente al volumen justo al iniciar el recorrido sin
     *  nada conectado. Se atenúa un poco más solo en ese caso. */
    private static final float ALTAVOZ_ATENUACION = 0.55f;

    /** ¿La salida de audio ahora mismo es el altavoz interno (o el auricular de llamada), SIN audífonos
     *  ni bluetooth conectado? Sin esa info (o ante cualquier duda) se asume que sí -- el altavoz es el
     *  caso más ruidoso, y atenuar de más es preferible a dejar un aviso exagerado. */
    private boolean salidaEsAltavoz() {
        if (audioManager == null) return true;
        try {
            for (android.media.AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                        return false;   // hay un dispositivo externo real conectado
                }
            }
        } catch (Throwable ignore) {}
        return true;
    }

    /** Volumen ACTUAL del stream de medios (0..1), para escalar la voz/tururu nosotros mismos: el
     *  parámetro KEY_PARAM_VOLUME de TextToSpeech (motor de respaldo, sin Mia) no sigue de forma
     *  confiable el volumen de medios que el usuario ya tiene puesto en varios motores/fabricantes
     *  (se queda en un nivel fijo alto sin importar el volumen del sistema); MediaPlayer.setVolume()
     *  sí es proporcional al volumen del stream, pero se aplica igual aquí por consistencia. Incluye la
     *  atenuación extra del altavoz interno (ver {@link #salidaEsAltavoz()}). */
    private float volumenSistema() {
        if (audioManager == null) return 1f;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float v = max > 0 ? (float) cur / max : 1f;
            return salidaEsAltavoz() ? v * ALTAVOZ_ATENUACION : v;
        } catch (Exception ignore) { return 1f; }
    }
    // Subido de 4000 -> 7000: con el radio de "próxima estación" más angosto (COBERTURA_EXTRA_M=20,
    // ver radioCerca()), paradas cercanas entre sí (p. ej. 1a de Villa / Eje 8) encolan avisos más
    // seguido -- 4 s no alcanzaba a bajar la voz Mia antes de caer al TTS de respaldo con esa cadencia,
    // así que el TTS sonaba más seguido de lo deseado. La cola (sonarYHablar/colaVoz) sigue esperando a
    // que cada aviso termine antes del siguiente, así que un margen mayor no hace que se amontonen.
    private static final long VOZ_TIMEOUT_MS = 7000L; // margen para descargar la voz Mia antes de caer al TTS

    // Andenes con ZONA de cobertura (Indios Verdes) miden ~100 m de largo. Como la distancia se mide al
    // corredor, el andén ya queda cubierto; además se amplía el radio de llegada para el aproche y, con
    // ello, el de "próxima estación" (que es radioCerca + COBERTURA_EXTRA_M).
    private static final float ZONA_CERCA_M = 70f;    // corredor de andén (Indios Verdes): radio amplio
    // Radio de las estaciones Mexibús L4 de "andén largo" (ANDENES_LARGOS en Planificador -- coordenada de
    // CENTRO ajustada a mano, no la cruda del JSON, porque solo se tiene un punto por plataforma de ~100 m).
    // Puente de Fierro es una de ellas: su acceso peatonal real queda ~85 m del centro registrado, más lejos
    // de lo que cubría el radio anterior (55 m); antes esto se resolvía con un punto de acceso adicional
    // (PF_L4_ACC_LAT/LON, ya quitado) que solo cubría UNA estación y de forma puntual. En vez de eso, el
    // radio se sube aquí para las 16 estaciones de la lista, cubriendo la plataforma completa (~100 m) más
    // el acceso real, sin necesitar coordenadas extra por estación.
    private static final float ANDEN_LARGO_M = 90f;

    // Radio del CRUCE de línea (L4→L2) en Puente de Fierro L2 -- SOLO para ese umbral, nunca para el
    // aviso normal de "llegando"/"próxima" viajando dentro de L2 (ver radioCruce() y su comentario).
    // PREVENTIVO (no la corrección de un defecto confirmado de este lado: el bug real confirmado en
    // Puente de Fierro fue la llegada a la plataforma L4, ya resuelto por ANDEN_LARGO_M) -- un margen
    // de seguridad para el cruce, dado que ambos puntos quedan a solo 284 m reales entre sí. No puede
    // ser tan amplio como ANDEN_LARGO_M (90 m): el propio tramo recto de L4 (San Cristóbal -> Puente de
    // Fierro L4) pasa a solo ~82 m reales de este punto, así que con 90 m viajando en L4 (antes incluso
    // de llegar a SU propia parada) ya se entraba al radio de "cruce" de L2. 60 m queda con margen bajo
    // esos 82 m (deja espacio para la imprecisión normal del GPS) y sigue siendo más amplio que el
    // radio normal de Mexibús (50 m).
    private static final float CRUCE_PF_L2_M = 60f;

    // Radio propio de Clínica 93 (L4) -- su acceso real llega hasta ~108 m del centro registrado
    // (19.550236,-99.052184), más que el radio compartido de ANDEN_LARGO_M (90 m). Se le da su propio
    // radio (115 m: 108 m reales + margen) en vez de subir el compartido de las 16 estaciones de
    // ANDENES_LARGOS -- innecesario, la separación real entre estaciones vecinas (≥457 m) deja margen
    // de sobra para un radio mayor solo aquí (ver Planificador.esClinica93()).
    private static final float RADIO_CLINICA_93_M = 115f;

    // Radio propio de Clínica 76 (L4) -- su acceso real llega hasta ~93 m del centro registrado
    // (19.527470,-99.081484), más que el radio compartido de ANDEN_LARGO_M (90 m). Se le da su propio
    // radio (100 m: 93 m reales + margen) en vez de subir el compartido de las 14 estaciones restantes
    // de ANDENES_LARGOS -- innecesario, sus vecinas (Vía Morelos, Martín Carrera) quedan a ≥825 m, sin
    // riesgo de solape (ver Planificador.esClinica76()).
    private static final float RADIO_CLINICA_76_M = 100f;

    // Radio propio de CEDA Ecatepec (Central de Abastos, L4/L4 Exprés) -- plataforma CUÁDRUPLE unida,
    // medida contra su propio corredor (ver Planificador.Z_CEDA_ECATEPEC/distanciaCedaEcatepec()), así
    // que el radio solo necesita cubrir la imprecisión normal del GPS a través del corredor, no la
    // longitud completa del andén (eso ya lo cubre el corredor). Nunca ZONA_CERCA_M (70 m, Indios
    // Verdes): ahí no hay ambigüedad entre andenes distintos que distinguir.
    private static final float RADIO_CEDA_ECATEPEC_M = 7f;

    // Radio ANGOSTO del aviso de "llegando" (propio) para las 4 plataformas con zona de Indios Verdes --
    // SOLO para ese aviso de la línea en la que ya vas viajando (ver radioLlegada()/su único uso, línea
    // ~905): el radio ANCHO (ZONA_CERCA_M=70 m) sigue intacto en radioCerca()/radioCruce() para la
    // lógica de correspondencia/cruce (distinguir entre los 4 andenes co-ubicados, "fin" de recorrido en
    // L4, "próxima estación" al alejarse) -- esa SÍ necesita el radio ancho a propósito (ver sus propios
    // comentarios). Defecto real confirmado (Mexibús L4 sur viniendo del norte): el corredor de cobertura
    // de Indios Verdes (Z_IV_L4_LLEGA, ~112 m) tiene su extremo norte a solo ~50 m del andén de Metrobús
    // L1 sur -- MENOS que ZONA_CERCA_M (70 m) -- así que el aviso de "llegando a tu Mexibús" podía sonar
    // estando aún a la altura del andén de Metrobús, no genuinamente sobre el andén de Mexibús. Mismo
    // criterio que CEDA Ecatepec (RADIO_CEDA_ECATEPEC_M=7): el corredor ya cubre toda la longitud real
    // del andén por SEGMENTO, así que el radio perpendicular solo necesita cubrir el margen normal de
    // imprecisión del GPS, no la ambigüedad entre plataformas (eso lo sigue resolviendo el radio ancho
    // en la lógica de cruce). Un poco más amplio que CEDA Ecatepec (7 m, coordenada medida con precisión)
    // porque estos 4 corredores se ubicaron a ojo sobre el mapa, no con una medición exacta.
    private static final float RADIO_LLEGADA_ZONA_M = 10f;

    /** Ciclos de GPS SEGUIDOS exigidos para confirmar una "correspondencia corta" (ver su uso en
     *  procesarInterno): antes eran 2; defecto real confirmado (viaje real, Indios Verdes) con
     *  ruido/multipath del GPS que puede durar más de 1 ciclo cerca de estructuras elevadas. */
    private static final int CORRESP_CORTA_CICLOS_MIN = 3;

    /** Radio ANGOSTO de "llegando" para Indios Verdes (ver {@link #RADIO_LLEGADA_ZONA_M}); el resto de
     *  casos es idéntico a {@link #radioCerca}. Úsese SOLO para el aviso de llegada a la parada en la
     *  que ya vas viajando -- nunca para el umbral de cruce de línea ({@link #radioCruce}) ni para
     *  "fin"/"próxima", que necesitan el radio ancho a propósito. */
    static float radioLlegada(Planificador.Parada p) {
        if (Planificador.tieneZona(p)) return RADIO_LLEGADA_ZONA_M;
        return radioCerca(p);
    }

    /** Radio de "llegando" según la parada: corredor (Indios Verdes) > CEDA Ecatepec > andén largo >
     *  Clínica 93/76 > Mexibús/Metrobús normal. */
    private static float radioCerca(Planificador.Parada p) {
        if (Planificador.tieneZona(p)) return ZONA_CERCA_M;
        if (Planificador.esCedaEcatepec(p)) return RADIO_CEDA_ECATEPEC_M;
        if (Planificador.andenLargo(p)) return ANDEN_LARGO_M;
        if (Planificador.esClinica93(p)) return RADIO_CLINICA_93_M;
        if (Planificador.esClinica76(p)) return RADIO_CLINICA_76_M;
        return (p != null && p.linea >= 100) ? CERCA_MXB_M : CERCA_M;
    }

    /** Como {@link #radioCerca}, pero ampliando TAMBIÉN para Puente de Fierro L2 (ver
     *  {@code Planificador.esCruceL2PuenteDeFierro}) -- SOLO para el umbral de un CRUCE de línea ahí
     *  (L4→L2), con SU PROPIO radio ({@link #CRUCE_PF_L2_M}, no {@link #ANDEN_LARGO_M}). Nunca para el
     *  aviso normal de "llegando"/"próxima" viajando dentro de L2 (ese usa radioCerca(), que ya no
     *  incluye ese punto: su propio andén es normal, ~50 m -- caso real confirmado: con el radio amplio
     *  aplicado también ahí, el aviso sonaba mucho antes de tiempo en el tramo corto, ~247 m, Casa de
     *  Morelos→Puente de Fierro). */
    private static float radioCruce(Planificador.Parada p) {
        if (Planificador.tieneZona(p)) return ZONA_CERCA_M;
        if (Planificador.esCedaEcatepec(p)) return RADIO_CEDA_ECATEPEC_M;
        if (Planificador.andenLargo(p)) return ANDEN_LARGO_M;
        if (Planificador.esClinica93(p)) return RADIO_CLINICA_93_M;
        if (Planificador.esClinica76(p)) return RADIO_CLINICA_76_M;
        if (Planificador.esCruceL2PuenteDeFierro(p)) return CRUCE_PF_L2_M;
        return (p != null && p.linea >= 100) ? CERCA_MXB_M : CERCA_M;
    }

    /** Distancia en metros por la Fórmula de Haversine. */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static volatile List<Planificador.Parada> paradas;
    public static volatile String terminal = "";
    public static volatile int actualIdx = -1;
    public static volatile boolean activo = false;
    public static volatile String servicioTexto = null;   // aviso único del servicio elegido (Ordinario/Express/Rosa)
    // Terminal (dirección) que calculó el planificador por TRAMO (índice de tramo = nº de transbordos hasta
    // ese punto). Respaldo autoritativo para la dirección hablada, correcto también en couplets (L4/L7).
    public static volatile java.util.List<String> terminalesTramo = null;
    private static volatile boolean servicioAnunciado = false;
    private static volatile int avanceMin = 0;             // índice mínimo ya alcanzado (progreso monótono)
    public static volatile com.google.android.gms.maps.model.LatLng ultimaPos;   // última ubicación GPS (para el puntero)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient loc;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;   // API 26+
    // No reacciona a pérdidas de foco: los avisos son cortos y transitorios; al terminar se suelta solo.
    private final AudioManager.OnAudioFocusChangeListener focusListener = f -> {};
    private boolean ciclando = false;
    private boolean recibiendo = false;   // ya se pidió el stream continuo de ubicación
    private boolean cadenciaRapida = true;   // modo GPS actual (rápido cerca de una parada, relajado lejos)
    private boolean cadenciaIniciada = false;   // ya se registró un request de ubicación (permite el primero)
    private final Runnable tick = this::ciclo;

    /** Stream continuo de ubicación (como Google Maps): entrega en cuanto el GPS tiene un fix. El
     *  request pide lote (ver {@link #aplicarCadencia}, setMaxUpdateDelayMillis) para ahorrar batería;
     *  con la pantalla apagada Android puede retener varios fixes y entregarlos juntos en un solo
     *  LocationResult. Defecto real confirmado: quedarse solo con getLastLocation() descartaba los
     *  fixes intermedios del lote, así que un recorrido con pantalla apagada podía saltar de "aún
     *  lejos" a "ya pasaste la estación" en una sola llamada -- y el SIGUIENTE lote (casi sin pausa
     *  real, apenas el siguiente puñado de fixes) completaba el aviso de "próxima estación" enseguida,
     *  sonando los dos avisos pegados en el mismo punto. Procesando TODOS los fixes del lote, en orden,
     *  cada transición de estado (ultLlegando/ultProxima) avanza una por una como si no hubiera lote. */
    private final LocationCallback locCb = new LocationCallback() {
        @Override public void onLocationResult(LocationResult r) {
            if (r == null) return;
            for (android.location.Location l : r.getLocations()) if (l != null) procesar(l);
        }
    };

    private TextToSpeech tts;
    private boolean ttsListo = false;
    /** Acción a ejecutar cuando TERMINE la voz actual (p. ej. finalizar el recorrido tras el aviso final). */
    private Runnable alFinVoz = null;

    /** Ejecuta (una sola vez) la acción pendiente para el fin de la voz. */
    private void dispararFinVoz() {
        Runnable r = alFinVoz;
        alFinVoz = null;
        if (r != null) handler.post(r);
    }

    /** Se llama cuando TERMINA de sonar un aviso completo (tururu + voz): sigue con el siguiente aviso
     *  en cola si hay, y SOLO si la cola queda REALMENTE vacía (procesarSiguienteVoz() dejó
     *  vozOcupada=false) dispara la acción pendiente de fin de recorrido, si la hay -- igual que ya
     *  hacía {@code detenerPendiente} para la salida manual. Antes se disparaba ANTES de intentar sacar
     *  el siguiente de la cola: si el aviso final ("llegaste") se encolaba detrás de uno que ya estaba
     *  sonando (p. ej. "próxima estación: terminal" todavía reproduciéndose cuando el GPS detecta la
     *  llegada), ese aviso anterior terminaba, alFinVoz disparaba stopSelf() de inmediato, y onDestroy()
     *  llamaba colaVoz.clear()+tts.shutdown() -- el aviso final nunca llegaba a sonar completo (o ni
     *  llegaba a empezar). Por eso se notaba sobre todo en la última estación: es el único lugar del
     *  archivo donde se fija alFinVoz. */
    private void vozTerminada() {
        procesarSiguienteVoz();
        if (!vozOcupada) dispararFinVoz();
        if (detenerPendiente && !vozOcupada) detenerDeVerdad();
    }
    private int ultVoz = -99;              // índice ya anunciado por voz (solo "llegaste" al final)
    private int ultLlegando = -99;         // estación cuya llegada ya se anunció ("Llegando a…")
    private int ultProxima = -99;          // próxima estación ya anunciada al salir de la anterior
    private int estSeguida = -99;          // estación cuyo acercamiento se está midiendo
    // Candidato de CRUCE DIRECTO de una correspondencia corta (ver procesarInterno, bloque de cambio de
    // línea): cuando la separación real entre las dos plataformas ya excede el radioCerca() de la
    // anterior, "llegar" a la anterior es geométricamente imposible desde el lado de la nueva (p. ej.
    // Puente de Fierro L2↔L4, 284 m, radioCerca=100 m). Para ese caso puntual se acepta la llegada a la
    // plataforma NUEVA como evidencia suficiente, pero nunca de un solo fix: se exige ver el MISMO índice
    // candidato en dos ciclos consecutivos (mismo criterio que MARGEN_MEJORA_M en reanclarOtraLinea).
    // -1 = sin candidato pendiente.
    private int corresPendienteIdx = -1;
    // Cuántos ciclos SEGUIDOS lleva 'corresPendienteIdx' con la condición cumplida (ver más abajo,
    // CORRESP_CORTA_CICLOS_MIN): antes bastaban 2 ciclos seguidos (uno de margen); defecto real
    // confirmado (viaje real, Indios Verdes Mexibús→Metrobús): el aviso seguía disparando de más,
    // consistente con ruido/multipath del GPS que puede durar más de 1 ciclo cerca de estructuras
    // elevadas -- se sube a 3 ciclos seguidos para exigir más margen.
    private int corresPendienteCiclos = 0;
    // Indios Verdes (único cruce con 4 plataformas, ver Planificador.IV_ZONA_CENTRAL): si la
    // posición ya pasó alguna vez por la zona central que conecta las 4, antes de aceptar el salto
    // de "correspondencia corta" -- evidencia física independiente del radio angosto de cada andén,
    // ver su uso más abajo. Nunca se reinicia a mitad de un mismo recorrido (una vez cruzada, sigue
    // cruzada), solo al iniciar uno nuevo.
    private boolean ivZonaCentralVista = false;
    // Candidato de reanclarOtraLinea() visto en el ciclo ANTERIOR, aún sin confirmar: un salto de línea
    // nunca se acepta de un solo fix de GPS (una lectura suelta -rebote/multipath, p. ej. bajo un
    // puente- podía ganar la comparación de distancia una sola vez y ya quedaba fijo, pues avanceMin
    // nunca retrocede). Se exige ver el MISMO índice candidato en dos ciclos consecutivos antes de
    // saltar -mismo criterio que corresPendienteIdx, arriba-. -1 = sin candidato pendiente.
    private int reanclajeCandidatoPendiente = -1;
    private float distMin = Float.MAX_VALUE;   // distancia mínima alcanzada a estSeguida (para medir "ya la pasé")
    private boolean afectacionAvisada = false;
    private boolean finalizado = false;    // ya se llegó al destino: se detiene el servicio
    private android.media.MediaPlayer mpActual;   // reproductor en curso (tururu o voz): evita traslapes
    // Cola de avisos pendientes (tururu+voz), en vez de cancelar el que sigue sonando: dos eventos
    // seguidos (p. ej. "llegando con transbordo" y, segundos después, "próxima estación" del nuevo
    // tramo) antes SE PERDÍAN si el segundo llegaba mientras el primero aún esperaba la voz (descarga
    // Mia hasta 4 s). Cada elemento es {texto, línea}.
    private final java.util.ArrayDeque<Object[]> colaVoz = new java.util.ArrayDeque<>();
    private boolean vozOcupada = false;            // hay un aviso sonando/hablando ahora mismo
    private volatile boolean destruido = false;    // el servicio ya se detuvo: no reproducir nada más
    // El usuario pidió salir del recorrido (ACCION_DETENER) mientras sonaba/había en cola un aviso: no se
    // apaga de inmediato (cortaría el audio a media palabra), se espera a que termine TODO lo pendiente.
    private boolean detenerPendiente = false;

    public static void iniciar(android.content.Context c, List<Planificador.Parada> seq, String term) {
        paradas = seq; terminal = term; actualIdx = -1; servicioAnunciado = false; avanceMin = 0;
        persistir(c);   // guarda la ruta: si el SO mata el proceso, el servicio (START_STICKY) la restaura
        if (seq != null && !seq.isEmpty())
            Telemetria.iniciarRecorrido(c, seq.get(0).nombre, seq.get(seq.size() - 1).nombre, seq.size());
        try { ContextCompat.startForegroundService(c, new Intent(c, RecorridoService.class)); }
        catch (Exception ignore) {}   // Android puede negar el arranque del foreground service
    }

    /** Pide salir del recorrido. NO detiene el servicio directo (stopService cortaría de inmediato
     *  cualquier audio sonando/en cola a media palabra): manda ACCION_DETENER para que la propia
     *  instancia decida, con su estado de voz, si apagarse ya o esperar a que termine lo pendiente
     *  (ver el manejo de ACCION_DETENER en onStartCommand). */
    public static void detener(android.content.Context c) {
        try { c.startService(new Intent(c, RecorridoService.class).setAction(ACCION_DETENER)); }
        catch (Exception ignore) {}   // el servicio ya no estaba corriendo
    }

    private static final String PREFS = "recorrido_estado";

    /** Serializa la ruta activa (paradas + terminal + servicio) para poder resumir tras muerte de proceso. */
    private static void persistir(android.content.Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("term", terminal == null ? "" : terminal);
            o.put("svc", servicioTexto == null ? org.json.JSONObject.NULL : servicioTexto);
            org.json.JSONArray arr = new org.json.JSONArray();
            if (paradas != null) for (Planificador.Parada p : paradas) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("n", p.nombre); j.put("l", p.linea); j.put("c", p.color);
                j.put("t", p.transbordo); j.put("la", p.pos.latitude); j.put("lo", p.pos.longitude);
                j.put("i", p.icono == null ? org.json.JSONObject.NULL : p.icono);
                arr.put(j);
            }
            o.put("paradas", arr);
            c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString("ruta", o.toString()).apply();
        } catch (org.json.JSONException ignore) {}
    }

    /** Reconstruye {@link #paradas} desde disco cuando el proceso revivió sin la ruta en memoria. */
    private boolean restaurar() {
        try {
            String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString("ruta", null);
            if (s == null) return false;
            org.json.JSONObject o = new org.json.JSONObject(s);
            terminal = o.optString("term", "");
            servicioTexto = o.isNull("svc") ? null : o.optString("svc", null);
            org.json.JSONArray arr = o.getJSONArray("paradas");
            java.util.List<Planificador.Parada> seq = new java.util.ArrayList<>();
            for (int k = 0; k < arr.length(); k++) {
                org.json.JSONObject j = arr.getJSONObject(k);
                seq.add(new Planificador.Parada(j.getString("n"), j.getInt("l"), j.getInt("c"),
                        j.getBoolean("t"),
                        new com.google.android.gms.maps.model.LatLng(j.getDouble("la"), j.getDouble("lo")),
                        j.isNull("i") ? null : j.getString("i")));
            }
            if (seq.isEmpty()) return false;
            paradas = seq; actualIdx = -1; servicioAnunciado = false; avanceMin = 0;
            return true;
        } catch (org.json.JSONException e) { return false; }
    }

    private void limpiarPersistencia() { limpiarPersistencia(this); }
    private static void limpiarPersistencia(android.content.Context c) {
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().remove("ruta").apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        loc = LocationServices.getFusedLocationProviderClient(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        crearCanal();
        tts = new TextToSpeech(this, status -> {
            // Callback async de inicialización de TextToSpeech, sin protección hasta ahora.
            try {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("es", "MX"));
                    tts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());               // mismo canal que el tururu, voz audible
                    seleccionarVozFemenina();     // mejor aproximación a "Ximena" con el motor instalado
                    tts.setSpeechRate(0.98f);
                    tts.setPitch(1.05f);          // timbre ligeramente más agudo (femenino)
                    tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) { handler.post(() -> { try { vozTerminada(); } catch (Exception ignore) {} }); }
                        @Override public void onError(String id) { handler.post(() -> { try { vozTerminada(); } catch (Exception ignore) {} }); }
                    });
                    ttsListo = true;
                }
            } catch (Exception ignore) {}
        });
    }

    /** Apaga el servicio de verdad: limpia persistencia, cierra telemetría y se detiene. Lo llama
     *  ACCION_DETENER directo si no hay voz sonando, o {@link #vozTerminada()} en cuanto la cola
     *  quede vacía (si el usuario salió del recorrido a media reproducción) -- ver detenerPendiente. */
    private void detenerDeVerdad() {
        detenerPendiente = false;
        limpiarPersistencia();
        Telemetria.finalizarRecorrido(this);
        stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            if (vozOcupada) {
                // Deja terminar el aviso en curso (y los que sigan en cola) antes de apagar el
                // servicio del todo, para no cortar a media palabra el último audio al salir del
                // recorrido -- antes se llamaba stopSelf() aquí mismo, de inmediato.
                detenerPendiente = true;
                handler.postDelayed(this::detenerDeVerdad, 15000);   // respaldo si el callback no llega
            } else {
                detenerDeVerdad();
            }
            return START_NOT_STICKY;
        }
        // Proceso revivido por el SO (intent null) sin la ruta en memoria: recárgala del disco.
        if (paradas == null && !restaurar()) { limpiarPersistencia(); stopSelf(); return START_NOT_STICKY; }
        activo = true;
        ultVoz = -99; ultLlegando = -99; ultProxima = -99; afectacionAvisada = false;
        estSeguida = -99; distMin = Float.MAX_VALUE; finalizado = false;
        corresPendienteIdx = -1;   // nuevo recorrido (o reinicio tras revivir): sin candidato pendiente
        corresPendienteCiclos = 0;
        ivZonaCentralVista = false;
        reanclajeCandidatoPendiente = -1;
        Notification n = construir(getString(R.string.recorrido_ubicando), "", null, "", null, "", null, "", "", 0, 0, 0, 0);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            // El SO puede negar el inicio en primer plano -- nunca debe tumbar la app, solo
            // cancela el recorrido en vez de dejar la excepción subir.
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "RecorridoService.onStartCommand", String.valueOf(e));
            limpiarPersistencia();
            stopSelf();
            return START_NOT_STICKY;
        }
        pedirUbicacion();
        return START_STICKY;
    }

    /** Al cerrar/deslizar la app de recientes, el recorrido sigue en segundo plano (no se detiene). */
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (activo && !finalizado) {
            pedirUbicacion();   // reasegura el stream de ubicación aunque la UI ya no exista
        }
    }

    /**
     * Pide actualizaciones de ubicación CONTINUAS (alta precisión), en vez de un fix por ciclo. El GPS
     * entrega en cuanto tiene una lectura, igual que Google Maps, así el puntero y la detección Haversine
     * de la estación se refrescan sin retraso. Arranca en cadencia RÁPIDA (~1 s) porque aún no se conoce
     * la distancia a la próxima parada; procesar() la ajusta cada fix según {@link #CADENCIA_LEJOS_M}.
     */
    @SuppressLint("MissingPermission")
    private void pedirUbicacion() {
        if (recibiendo || !tienePermiso()) return;
        recibiendo = true;
        aplicarCadencia(true);
    }

    /** Cambia la frecuencia de las actualizaciones de ubicación entre RÁPIDA (~1 s / mín 0.5 s, igual que
     *  antes) y RELAJADA (~3 s / mín 2 s) según qué tan lejos esté la próxima parada a anunciar. El GPS de
     *  alta precisión encendido de forma continua es lo que más batería consume y calienta el equipo en
     *  recorridos largos; relajar la cadencia mientras se está a media cuadra de cualquier parada ahorra
     *  batería sin afectar el aviso "casi instantáneo" (que solo necesita la cadencia rápida cerca de la
     *  parada, momento en el que ya se vuelve a activar). Solo re-registra el request si el modo cambió,
     *  para no pedirle de más al proveedor de ubicación en cada fix. */
    @SuppressLint("MissingPermission")
    private void aplicarCadencia(boolean rapida) {
        if (loc == null || !tienePermiso()) return;
        if (cadenciaIniciada && rapida == cadenciaRapida) return;   // ya está en ese modo: no re-registrar
        cadenciaRapida = rapida;
        cadenciaIniciada = true;
        long intervalo = rapida ? INTERVALO_MS : INTERVALO_LEJOS_MS;
        long minIntervalo = rapida ? 500L : 2000L;
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalo)
                .setMinUpdateIntervalMillis(minIntervalo)
                .setMaxUpdateDelayMillis(intervalo)
                .build();
        loc.requestLocationUpdates(req, locCb, Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    private void ciclo() {
        if (!ciclando && paradas != null && tienePermiso()) {
            ciclando = true;
            loc.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this::procesar)
                    .addOnFailureListener(e -> ciclando = false);
        }
        handler.postDelayed(tick, INTERVALO_MS);
    }

    private java.util.List<com.google.android.gms.maps.model.LatLng> ivPlataformas;

    /** Las 4 plataformas de Indios Verdes (Metrobús L1/L7, Mexibús L4, Mexicable L2), cacheadas. */
    private java.util.List<com.google.android.gms.maps.model.LatLng> plataformasIV() {
        if (ivPlataformas != null) return ivPlataformas;
        java.util.List<com.google.android.gms.maps.model.LatLng> ls = new java.util.ArrayList<>();
        try {
            java.util.List<Linea> todas = new java.util.ArrayList<>(GtfsRepository.getLineas(this));
            todas.addAll(GtfsRepository.getMexibus(this));
            for (Linea li : todas)
                for (Estacion e : li.estaciones)
                    if (Planificador.norm(e.nombre).contains("indios verdes")) ls.add(e.posicion);
        } catch (Exception ignore) {}
        ivPlataformas = ls;
        return ls;
    }

    /** Distancia a la parada. En Indios Verdes cada andén tiene una ZONA de cobertura (corredor A→B): se
     *  mide contra el segmento para cubrir toda su longitud; en CEDA Ecatepec (Central de Abastos, su
     *  propio corredor más angosto, ver {@link Planificador#distanciaCedaEcatepec}) igual; en el resto,
     *  contra el punto (p.pos). Las estaciones de "andén largo" (p. ej. Puente de Fierro L4) YA cubren
     *  la plataforma completa por el radio ampliado de {@link #ANDEN_LARGO_M}, así que no hace falta un
     *  punto de acceso peatonal adicional aquí (se quitó: cubría solo una estación y de forma puntual,
     *  en vez de resolverlo con un radio que sirva para todas). */
    private double distParada(android.location.Location l, Planificador.Parada p) {
        double dz = Planificador.distanciaZona(p, l.getLatitude(), l.getLongitude());
        if (dz >= 0) return dz;
        double dc = Planificador.distanciaCedaEcatepec(p, l.getLatitude(), l.getLongitude());
        if (dc >= 0) return dc;
        return haversine(l.getLatitude(), l.getLongitude(), p.pos.latitude, p.pos.longitude);
    }

    /** Distancia mínima recorrida dentro del trazo (tras la correspondencia, sumando tramos entre
     *  paradas consecutivas) para dar por hecho que ya vas en él y saltar el aviso a esa línea. Antes
     *  eran "3 estaciones", pero el espaciado entre estaciones varía mucho entre sistemas (Metrobús
     *  vs. Mexibús L4), así que un umbral de distancia es más consistente. */
    private static final float SALTO_MIN_METROS = 800f;
    // Umbral de REUBICACIÓN: qué tan lejos de una parada de OTRA línea de la ruta puede estar tu GPS
    // para considerar que estás viajando en ella. Antes (RADIO_ENLACE_M=700) esto se medía CONTRA EL
    // PUNTO DE ENLACE fijo (seq.get(inicio), donde la ruta aborda ese trazo) en vez de contra la propia
    // parada candidata: con 2 líneas (A→B) alcanzaba a colar por geometría (abordar B poco después del
    // enlace A→B todavía cae, por casualidad, dentro de esos 700 m del enlace), pero con 3+ líneas
    // (A→B→C) la combinación era imposible de cumplir a la vez que SALTO_MIN_METROS: ya ≥800 m DENTRO
    // de C (tras el enlace B→C) tu posición real deja de estar a 700 m del enlace B→C, así que el
    // reanclaje nunca encontraba C (se quedaba clavado en A/B). El radio se mide ahora contra la propia
    // parada candidata (ya es, por construcción, una parada real y planificada de esa línea), lo que
    // además cubre ir sin detenerse justo sobre el nombre de una estación (unidad en movimiento).
    private static final float RADIO_REUBICACION_M = 900f;
    // Cuánto debe MEJORAR un candidato de otra línea sobre la posición actual (distBestActual) para
    // que valga la pena saltar -- sin esto, "estar a <900 m de otra línea" podía ganar aunque el
    // usuario siguiera mucho mejor ubicado en su línea actual (p. ej. L4 en la zona de Puente de
    // Fierro, a <10 m de su parada real, saltando a UPE de Mexibús L2 -- ambas líneas corren a
    // ~900 m una de otra en ese corredor -- con solo ~18 m de ruido de GPS). 25 m es consistente con
    // el margen de error de GPS que ya usa el resto de este archivo para lo mismo (ver
    // CAMBIO_LINEA_M=15, "se deja un poco más por el error típico del GPS"; COBERTURA_EXTRA_M=20).
    // NO hace que el candidato dependa del punto de enlace/correspondencia (eso era RADIO_ENLACE_M,
    // quitado a propósito en el fix de A→B→C): sigue midiendo contra la propia parada candidata.
    private static final float MARGEN_MEJORA_M = 25f;

    /**
     * Si la ubicación está a ≤{@link #RADIO_REUBICACION_M} de una parada de OTRA línea de la ruta
     * (adelante de {@code best}, respetando el orden de la ruta), Y esa parada representa una
     * mejora real sobre qué tan bien ubicado está el usuario en su línea actual (ver
     * {@link #MARGEN_MEJORA_M}), Y esa parada queda a ≥{@link #SALTO_MIN_METROS} de recorrido
     * dentro de su propio trazo (tras la correspondencia que ya construyó el planificador --
     * descarta la mera primera parada, donde una coincidencia geográfica es más probable), es una
     * candidata válida. Devuelve la MÁS CERCANA de todas las candidatas válidas (de cualquier línea
     * posterior, no solo la siguiente) -- no la más adelantada: si dos líneas distintas de la ruta
     * califican a la vez (p. ej. vas por B pero una parada de C, más adelante en la ruta, también
     * cae dentro del radio), la distancia real es la que distingue en cuál de las dos estás de
     * verdad. Si ninguna califica, devuelve {@code best}. EXCEPCIÓN: si el GPS está geográficamente
     * CERCA (≤{@link #RADIO_REUBICACION_M}) del primer punto de transbordo real (ver
     * {@code Parada#transbordo}) todavía sin anunciar, el reanclaje NO considera ninguna candidata en
     * o más allá de él -- primero debe sonar la llegada a ESE punto (p. ej. Puente de Fierro, Indios
     * Verdes). Lejos de ese punto no aplica (es el caso legítimo de "te subiste directo" más adelante
     * en esta misma ruta, sin pasar nunca por esa correspondencia).
     */
    private int reanclarOtraLinea(android.location.Location l, List<Planificador.Parada> seq, int best) {
        int mejor = best;
        double mejorDist = Double.MAX_VALUE;
        int trazoBest = seq.get(best).linea;                          // trazo = servicio (ordinario≠exprés≠ramal)
        // Qué tan bien ajustado está 'best' AHORA MISMO, con la misma función que se usa para
        // evaluar a los candidatos (consistente con el manejo de "zona" de Indios Verdes).
        double distBestActual = distParada(l, seq.get(best));
        // Defecto real confirmado en dispositivo: una lectura de GPS puntual mala podía ganar la
        // comparación de distancia contra un punto de transbordo REAL (transbordo=true -- el nodo
        // donde la ruta ABORDA la otra línea, p. ej. Puente de Fierro, Indios Verdes) todavía sin
        // anunciar, saltando de largo esa parada -y cualquier otra de su propio trazo antes del
        // salto, p. ej. "Casa de Morelos"- directo a una parada mucho más adelante (p. ej. "UPE").
        // Se ubica el PRIMER transbordo así (si lo hay): si el GPS está cerca de él (≤
        // RADIO_REUBICACION_M -- genuinamente podrías estar ahí mismo, es la zona donde el ruido de
        // GPS causa el salto falso), se descartan todas las candidatas en o más allá. Lejos de él, NO
        // se restringe: ese es el caso legítimo en que te subiste directo más adelante en la ruta sin
        // pasar nunca por esa correspondencia (reanclaje general, la razón de ser de esta función).
        //
        // Defecto real confirmado (viaje real): esta misma protección se queda atorada si el
        // recorrido arranca YA de pie en el andén de un punto de transbordo real (p. ej. iniciar la
        // app ya en Indios Verdes, con una ruta Bosques del Valle→IPN) -- nunca se va a "llegar" a
        // ese transbordo por el camino normal (el usuario jamás viajó el tramo Mexibús), así que
        // ultLlegando nunca lo alcanza y el límite se queda clavado para siempre. Esta protección
        // solo tiene sentido A MITAD DE VIAJE (vas acercándote de verdad por la línea anterior); al
        // INICIAR el recorrido (avanceMin==0, nada anunciado aún) no aplica -- el salto sigue
        // pasando por la confirmación normal de 2 ciclos de reanclarOtraLinea() (más abajo, en su
        // único llamador), así que no se vuelve menos estricto contra ruido de GPS puntual.
        boolean inicioDelRecorrido = avanceMin == 0 && ultLlegando == -99;
        int limite = seq.size();
        if (!inicioDelRecorrido) {
            for (int k = best + 1; k < seq.size(); k++) {
                if (seq.get(k).transbordo && ultLlegando < k) {
                    if (distParada(l, seq.get(k)) <= RADIO_REUBICACION_M) limite = k;
                    break;
                }
            }
        }
        for (int j = best + 1; j < limite; j++) {
            Planificador.Parada pj = seq.get(j);
            if (pj.linea == trazoBest) continue;                       // mismo trazo/servicio que el actual: no aplica
            double d = distParada(l, pj);
            if (d > RADIO_REUBICACION_M) continue;                     // no estás cerca de esa parada
            if (d >= distBestActual - MARGEN_MEJORA_M) continue;        // no es una mejora real sobre 'best'
            // 'inicio' = primer índice de la racha de ESE trazo que termina en j (el nodo donde la ruta
            // ABORDA ese trazo: el propio punto de correspondencia/conexión/transbordo que construyó el
            // planificador). Se compara el nº de servicio (no baseLinea) para que ORDINARIO↔EXPRÉS
            // también cuente como cambio de trazo.
            int inicio = j;
            for (int k = j; k >= 0 && seq.get(k).linea == pj.linea; k--) inicio = k;
            if (distanciaTrazo(seq, inicio, j) < SALTO_MIN_METROS) continue;
            if (d < mejorDist) { mejor = j; mejorDist = d; }           // la candidata válida más cercana
        }
        return mejor;
    }

    /** Distancia recorrida (m) sumando los tramos entre paradas consecutivas de 'inicio' a 'j'. */
    private static double distanciaTrazo(List<Planificador.Parada> seq, int inicio, int j) {
        double d = 0;
        for (int k = inicio; k < j; k++) {
            com.google.android.gms.maps.model.LatLng a = seq.get(k).pos, b = seq.get(k + 1).pos;
            if (a != null && b != null) d += haversine(a.latitude, a.longitude, b.latitude, b.longitude);
        }
        return d;
    }

    /** Envoltura de {@link #procesarInterno}: se llama en CADA fix de GPS de un recorrido activo
     *  (navegación en primer plano), así que una excepción sin atrapar aquí mataría la app a media
     *  ruta. Cualquier falla se registra y se descarta -- el siguiente fix de GPS simplemente
     *  reintenta desde cero, en vez de tumbar el servicio. */
    private void procesar(android.location.Location l) {
        try {
            procesarInterno(l);
        } catch (Throwable t) {
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "RecorridoService.procesar", String.valueOf(t));
        }
    }

    private void procesarInterno(android.location.Location l) {
        // ========================================================
        // "QUÉ AVISO TOCA": se llama en CADA fix de GPS. Mapa del método:
        // ========================================================
        //   1) Busca la estación más cercana ('best'), pero SOLO hacia adelante
        //      (avanceMin nunca retrocede) para no saltar atrás ni pegarse a un
        //      andén co-ubicado de otra línea antes de la correspondencia.
        //   2) Reglas finas de CAMBIO DE LÍNEA: no brinca a la otra línea hasta
        //      estar físicamente en su andén y haber anunciado la parada previa
        //      (ver los comentarios detallados abajo; casos Indios Verdes / zonas).
        //   3) Anuncia UNA vez la dirección + total de estaciones al iniciar.
        //   4) Detecta "ya pasaste" la estación (te alejaste más de radioCerca()+
        //      COBERTURA_EXTRA_M de tu punto más cercano) y calcula la PRÓXIMA
        //      estación distinta (salta co-ubicadas).
        //   5) Con eso arma y encola los avisos de voz (próxima, llegada, transbordo).
        // Los comentarios largos de cada bloque explican los casos borde REALES
        // (Puente de Fierro, Indios Verdes) que motivaron cada resguardo.
        ciclando = false;
        List<Planificador.Parada> seq = paradas;
        if (l == null || seq == null || seq.isEmpty()) return;
        ultimaPos = new com.google.android.gms.maps.model.LatLng(l.getLatitude(), l.getLongitude());
        // Indios Verdes: marca si ya se pasó por la zona central (ver su declaración y uso más
        // abajo, en el bloque de cambio de línea) -- se revisa en CADA fix, no solo cuando hay
        // correspondencia en curso, para no perder el cruce si pasó por ahí antes de que 'best'
        // alcanzara la parada anterior a Indios Verdes.
        if (!ivZonaCentralVista && Planificador.enZonaCentralIndiosVerdes(ultimaPos)) ivZonaCentralVista = true;

        // Búsqueda MONÓTONA hacia adelante: la estación más cercana se busca desde el avance mínimo ya
        // alcanzado, no desde el inicio. Así el recorrido no salta hacia atrás ni se "pega" a una estación
        // co-ubicada de otra línea antes del transbordo (se queda en su línea hasta hacer la correspondencia).
        int best = avanceMin;
        double bd = distParada(l, seq.get(avanceMin));
        for (int i = avanceMin + 1; i < seq.size(); i++) {
            Planificador.Parada pi = seq.get(i);
            double d = distParada(l, pi);   // Haversine (Indios Verdes: la más cercana de sus 4 plataformas)
            if (pi.linea != seq.get(best).linea) {
                // Correspondencia (cambio de línea): el aviso NO cambia a la nueva línea hasta que estás
                // físicamente en su andén. Te aferras a la línea actual mientras caminas la correspondencia.
                // Si es la MISMA estación física (mismo nombre exacto —p. ej. Puente de Fierro L2↔L4, u
                // ordinario↔exprés de la misma base—, O mismo NÚCLEO de nombre a corta distancia —p. ej.
                // "Indios Verdes" Metrobús vs "Indios Verdes (conexión Metrobús L1 y L7)" de Mexibús, que
                // NO son idénticas letra por letra por el paréntesis—), el cambio se permite dentro del
                // radio de LLEGADA (~50 m o más), porque estar en ese andén ya cuenta como haber hecho la
                // correspondencia. Para andenes co-ubicados de nombre REALMENTE distinto se mantiene el
                // umbral estricto (CAMBIO_LINEA_M) para no saltar antes. Se usa coUbicada() (no solo
                // mismaEstacion()) para que el núcleo del nombre también cuente: si solo se exigiera
                // igualdad exacta, Indios Verdes nunca cumplía este radio amplio y el salto dependía del
                // mecanismo más tosco (reanclarOtraLinea, sin la verificación de "ya llegaste antes").
                // No brincar a la otra línea antes de ANUNCIAR la llegada a la parada ACTUAL ('best'): si el
                // andén de la otra línea queda en el camino antes de tu terminal (Indios Verdes sur: el
                // andén de Metrobús L1 está al norte, antes del terminal L4 al sur), el aviso saltaba antes
                // de tiempo. Se exige que la llegada a 'best' YA se haya anunciado (ultLlegando ≥ best); así
                // no basta con que 'best' la roce por cercanía: hay que haber llegado físicamente a ella.
                // Esta verificación por POSICIÓN/ESTADO (no por tiempo) es a propósito: un temporizador fijo
                // no distingue caminar despacio de estar simplemente detenido en otro punto de la ruta.
                // Defecto real confirmado (Indios Verdes): antes se comparaba contra 'i - 1' (la parada
                // ANTERIOR en la secuencia combinada, no 'best' mismo) -- en el caso típico ambas coinciden,
                // pero el aviso de Metrobús seguía sonando sin haber anunciado nunca la llegada a Indios
                // Verdes por Mexibús. El usuario pidió expresamente esperar la llegada de la estación
                // indicada (Mexibús) antes de continuar con la otra línea (Metrobús); 'best' es siempre esa
                // estación indicada, así que comparar contra 'best' es la condición correcta y más estricta.
                boolean alcanzasteAnterior = ultLlegando >= best;
                boolean coloc = coUbicada(pi, seq.get(best));
                // radioCruce() (no radioCerca()): el umbral del CRUCE a 'pi' sí debe cubrir Puente de
                // Fierro L2 con el radio amplio (ver su comentario) -- el aviso normal de "llegando" ahí
                // (más abajo, fuera de este bloque de cambio de línea) usa radioCerca() normal.
                float umbral = coloc ? radioCruce(pi) : CAMBIO_LINEA_M;
                // CORRESPONDENCIA CORTA (excepción controlada, solo para un par ya validado por coUbicada):
                // si la separación FÍSICA real entre ambas plataformas ya excede el radioCerca() de la
                // anterior, 'alcanzasteAnterior' nunca puede volverse cierto por el camino normal -- no es
                // un problema de GPS, es geométricamente imposible. Ahí se acepta la llegada a la plataforma
                // NUEVA en su lugar, confirmada en CORRESP_CORTA_CICLOS_MIN ciclos consecutivos (nunca un
                // solo fix, ver su declaración). Esto NO es
                // un reanclaje general: solo aplica al nodo inmediato siguiente de la propia secuencia de la
                // ruta, y solo si coUbicada() ya certificó que es una correspondencia real.
                boolean imposibleLlegarAnterior = coloc
                        && Linea.distancia(pi.pos, seq.get(best).pos) > radioCerca(seq.get(best));
                // Indios Verdes (4 plataformas, ver Planificador.IV_ZONA_CENTRAL): exige ADEMÁS haber
                // pasado por la zona central que las conecta -- evidencia física independiente del
                // radio de 70 m de cada andén. Para cualquier otra correspondencia corta (p. ej.
                // Puente de Fierro, que no tiene esa zona) esta condición no aplica (siempre true).
                boolean centroOk = !Planificador.esPlataformaIndiosVerdes(pi) || ivZonaCentralVista;
                boolean enNuevaAhora = imposibleLlegarAnterior && d <= umbral && centroOk;
                // Defecto real confirmado (viaje real, Indios Verdes): 2 ciclos seguidos no bastaban --
                // consistente con ruido/multipath del GPS cerca de estructuras elevadas, que puede
                // durar más de 1 ciclo. Se sube a CORRESP_CORTA_CICLOS_MIN=3 ciclos seguidos.
                if (enNuevaAhora && corresPendienteIdx == i) corresPendienteCiclos++;
                else if (enNuevaAhora) corresPendienteCiclos = 1;
                else corresPendienteCiclos = 0;
                boolean alcanzasteNueva = corresPendienteCiclos >= CORRESP_CORTA_CICLOS_MIN;
                corresPendienteIdx = enNuevaAhora ? i : -1;
                // En plataformas con ZONA (p. ej. Indios Verdes): dos andenes co-ubicados por núcleo de
                // nombre pueden ser paralelos y estar a muy poca distancia entre sí (el de ascenso de
                // Metrobús L1 y el de Mexibús L4 quedan a ~30-40 m uno del otro) — bastante MENOS que el
                // radio ancho de zona (ZONA_CERCA_M=70 m). Ese radio por sí solo no alcanza a distinguirlos:
                // estando cómodo en el andén de Metrobús ya caías también dentro del radio de "llegada" de
                // Mexibús, y el aviso de la otra línea sonaba de más, sin haber caminado a su andén. Para
                // este caso se exige ADEMÁS estar más cerca de la zona NUEVA que de la actual.
                boolean zonaAmbigua = coloc && (Planificador.tieneZona(pi) || Planificador.tieneZona(seq.get(best)));
                boolean cruzaZona = !zonaAmbigua || d < distParada(l, seq.get(best));
                // IMPORTANTE: se corta aquí (break) aunque SÍ se cruce la correspondencia. Si se dejara
                // seguir el bucle, la siguiente iteración compararía la parada de ADELANTE (misma línea
                // nueva, p. ej. Nuevo Laredo tras Puente de Fierro en L4) por simple cercanía —sin ningún
                // resguardo de "ya se anunció"— y podía ganar la comparación y quedarse como 'best' EN EL
                // MISMO ciclo. Eso saltaba de largo el propio nodo de correspondencia (Puente de Fierro)
                // sin que su aviso de llegada/transbordo llegara a dispararse nunca: el próximo ciclo ya
                // hablaba de la estación siguiente. Al cortar aquí, este ciclo se queda EN el nodo de
                // correspondencia y el de llegada podrá anunciarlo; recién el siguiente ciclo avanza más.
                if ((alcanzasteAnterior || alcanzasteNueva) && d <= umbral && cruzaZona) {
                    // Defecto real confirmado (Indios Verdes): el bloque de cruce corta (break) y salta
                    // a la línea nueva ANTES de que el bloque normal de "próxima estación" (más abajo, con
                    // "favor de irte preparando") llegue a ejecutarse para 'best' -- se perdía por
                    // completo, sin importar por cuál de los 2 caminos (alcanzasteAnterior/alcanzasteNueva)
                    // se habilitó el salto. 'i' ES el propio nodo de correspondencia (pi), así que
                    // "próxima: i" con preparación es siempre el aviso correcto antes de saltar; se
                    // anuncia aquí, PRIMERO (antes que la llegada a la línea vieja, si aplica abajo) por
                    // orden lógico: "prepárate" antes de "ya llegaste".
                    // Defecto real confirmado (viaje real, Mexibús L4 Periférico→Indios Verdes): Indios
                    // Verdes es la parada INMEDIATA siguiente a Periférico en la secuencia -- el bloque
                    // NORMAL de "próxima estación" (más abajo) ya la anuncia correctamente apenas sales
                    // de Periférico (con "favor de irte preparando" incluido, porque Indios Verdes YA
                    // tiene correspondencia real con Metrobús). Ese aviso usa el índice del nodo
                    // MEXIBÚS; este bloque usa el índice del nodo METROBÚS -- son DOS nodos de secuencia
                    // distintos para la MISMA estación física (coUbicada), así que "ultProxima != i" por
                    // sí solo no detectaba que ya se había avisado. Se evita repetir si el candidato
                    // anterior YA estaba co-ubicado con este.
                    boolean yaAvisado = ultProxima == i
                            || (ultProxima >= 0 && ultProxima < seq.size() && coUbicada(seq.get(ultProxima), pi));
                    if (!yaAvisado) {
                        sonarYHablar(vozProxima(seq, i, true), seq.get(best).linea, nom(seq.get(i)));
                    }
                    ultProxima = i;
                    // Caso real confirmado (Indios Verdes, Mexibús→Metrobús): cuando es el camino de
                    // "correspondencia corta" (alcanzasteNueva, NO alcanzasteAnterior) el que habilita el
                    // salto -- p. ej. aquí mismo, IV_L1_SALE queda a ~340 m de IV_L4_LLEGA, más que
                    // radioCerca() (70 m): geométricamente imposible cumplir el camino normal -- la
                    // llegada al andén VIEJO (Mexibús) nunca pasó por la rama normal de abajo (líneas
                    // ~825 en adelante) -- ultLlegando seguía apuntando a una parada anterior, así que su
                    // propio aviso de "llegando a X" + instrucción de bajada (que ya incluye la
                    // correspondencia/conexión con la línea nueva, ver vozLlegada()) nunca sonaba: el
                    // usuario solo escuchaba el aviso del andén NUEVO (Metrobús), nunca el de Mexibús. Se
                    // anuncia aquí explícitamente, antes de saltar, tratando el andén viejo como alcanzado.
                    if (alcanzasteNueva && !alcanzasteAnterior && ultLlegando < best) {
                        ultLlegando = best;
                        Telemetria.registrarEventoEstacion(this, seq.get(best).nombre, seq.get(best).linea, true);
                        // Defecto real confirmado (viaje real, Indios Verdes): se probó omitir este aviso
                        // cuando ya se estaba DENTRO del radio angosto (10 m, radioLlegada()) del andén
                        // NUEVO (Metrobús), asumiendo que sería obvio/redundante -- en la práctica dejaba
                        // la correspondencia SIN NINGÚN cierre audible del lado Mexibús: lo único que se
                        // alcanzaba a oír era el aviso normal de Metrobús (más abajo), nunca el tono/voz de
                        // Mexibús. Se revierte esa condición: el aviso de llegada de Mexibús SIEMPRE suena
                        // aquí, sin importar qué tan cerca ya estés del andén nuevo.
                        sonarYHablar(vozLlegada(seq, best) + sufijoCambio(seq, best), seq.get(best).linea, nom(seq.get(best)));
                    }
                    bd = d; best = i;
                    corresPendienteIdx = -1;   // correspondencia resuelta: limpiar candidato pendiente
                    corresPendienteCiclos = 0;
                }
                break;
            } else if (d < bd) {
                bd = d; best = i;
            }
        }
        // Reanclaje a OTRA línea: si la ubicación ya está claramente sobre una parada de CUALQUIER línea
        // posterior de la ruta (no solo la siguiente -- recorre TODAS las que falten, en orden, y evita
        // retroceder a una ya pasada) a ≥SALTO_MIN_METROS de recorrido dentro de su propio trazo, el
        // aviso salta ahí. Así, si te subiste directo, te saltaste una correspondencia intermedia sin
        // pasar por ella, o la línea previa cerró, el recorrido no queda clavado en una línea anterior.
        int salto = reanclarOtraLinea(l, seq, best);
        if (salto > best) {
            // Nunca se salta con un solo fix: se exige ver la MISMA candidata en dos ciclos
            // consecutivos (ver reanclajeCandidatoPendiente) -- filtra una lectura de GPS puntual
            // mala (defecto real confirmado: rebote/multipath cerca de una correspondencia, p. ej.
            // Puente de Fierro, podía ganar la comparación de distancia una sola vez).
            if (reanclajeCandidatoPendiente == salto) {
                Telemetria.registrarError(this, Telemetria.ERR_REANCLAJE, "RecorridoService.reanclarOtraLinea",
                        "de " + seq.get(best).nombre + " a " + seq.get(salto).nombre);
                best = salto;
                reanclajeCandidatoPendiente = -1;
            } else {
                reanclajeCandidatoPendiente = salto;
            }
        } else {
            reanclajeCandidatoPendiente = -1;
        }

        avanceMin = best;   // nunca retrocede
        actualIdx = best;

        // Aviso ÚNICO al iniciar el recorrido: DIRECCIÓN (terminal del sentido de viaje) + TOTAL de
        // estaciones del viaje; y el servicio elegido (Ordinario/Express, unidad Rosa) si aplica.
        if (!servicioAnunciado) {
            servicioAnunciado = true;
            sonarYHablar(avisoAbordaje(seq, best), seq.get(best).linea);
        }

        // Mide el acercamiento a la estación más cercana: cuando ya te alejaste más de
        // radioCerca()+COBERTURA_EXTRA_M de tu punto más cercano, se considera que "ya la pasaste"
        // y se anuncia la próxima.
        if (best != estSeguida) { estSeguida = best; distMin = (float) bd; }
        else if (bd < distMin) distMin = (float) bd;

        int last = seq.size() - 1;
        // Fin sobre el punto (≤5 m); Mexibús L4 conserva el radio normal (2 andenes, la unidad rebasa el punto).
        boolean finL4 = seq.get(last).linea == 104 || seq.get(last).linea == 124;
        boolean fin = best >= last && bd <= (finL4 ? radioCerca(seq.get(last)) : FIN_M);
        // Próxima estación DISTINTA: salta los andenes CO-UBICADOS del mismo nombre (p. ej. Indios Verdes
        // L4 ↔ Metrobús L1/L7), para no anunciar "próxima Indios Verdes" ni "vas de Indios Verdes a Indios
        // Verdes" ni pisar el aviso de conexión con un "próxima" espurio. PERO no se salta un andén
        // co-ubicado que además esté marcado transbordo=true: ese SÍ es el punto real donde se aborda la
        // correspondencia (p. ej. Puente de Fierro L4, a 284 m real de su copia en L2) y trae su propio
        // texto de "correspondencia con línea X" -saltárselo dejaba el aviso mudo justo en la parada que
        // más importa, anunciando de una vez la SIGUIENTE estación real (San Cristóbal, Nuevo Laredo…).
        int proxIdx = best + 1;
        while (proxIdx < last && coUbicada(seq.get(best), seq.get(proxIdx)) && !seq.get(proxIdx).transbordo)
            proxIdx++;
        if (proxIdx > last) proxIdx = last;
        int antIdx = Math.max(0, proxIdx - 1);     // estación anterior a la próxima
        int postIdx = Math.min(last, proxIdx + 1); // estación posterior a la próxima
        Planificador.Parada prox = seq.get(proxIdx), ant = seq.get(antIdx), post = seq.get(postIdx);

        // Cadencia adaptativa de GPS: cerca de la próxima parada se necesita precisión al segundo para no
        // perder el aviso "casi instantáneo"; lejos (a varias cuadras) se relaja el muestreo para ahorrar
        // batería y no calentar el equipo en tramos largos entre estaciones.
        aplicarCadencia(fin || distParada(l, prox) <= CADENCIA_LEJOS_M);

        String estado;
        if (fin) {
            estado = getString(R.string.recorrido_llegaste, vis(seq.get(best)));
        } else if (prox.transbordo) {
            estado = getString(R.string.recorrido_transborda, vis(prox), Planificador.etiquetaLineaCortaPub(prox.linea));
        } else {
            estado = getString(R.string.recorrido_vas, vis(seq.get(best)), vis(prox));
        }

        // notificación (diseño propio): anterior · PRÓXIMA · posterior (sin MXB, con nº si el nombre se repite)
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID, construir(vis(prox), estado, pico(prox),
                vis(ant), pico(ant), vis(post), pico(post),
                vis(seq.get(0)), vis(seq.get(last)), seq.get(best).color, prox.linea,
                ant.linea, post.linea));

        // VOZ (precedida del "tururu"): al ARRIBAR a la estación dice "Llegando a estación: X"; si es
        // transbordo añade "Transbordo con Línea #"; si es terminal, "nadie debe permanecer a bordo"; y
        // de forma aleatoria un consejo. Al pasar la estación (ya te alejaste radioCerca()+
        // COBERTURA_EXTRA_M), anuncia la próxima.
        if (fin) {
            // Ruta terminada: aviso "última estación de tu recorrido" y, al TERMINAR ese audio, se
            // finaliza el servicio automáticamente (con un tope de respaldo por si la voz fallara).
            if (!finalizado) {
                finalizado = true;
                ultVoz = best;
                ultLlegando = best;
                corresPendienteIdx = -1;   // recorrido terminado: sin candidato pendiente
                corresPendienteCiclos = 0;
                limpiarPersistencia();   // llegaste: no debe resumir tras muerte de proceso
                handler.removeCallbacks(tick);
                detenerUbicacion();      // ya llegaste: corta el GPS para ahorrar batería
                Telemetria.registrarEventoEstacion(this, seq.get(best).nombre, seq.get(best).linea, true);
                Telemetria.finalizarRecorrido(this);
                alFinVoz = this::stopSelf;   // finaliza cuando acabe la voz del aviso final
                // Mismo aviso combinado de llegada (incluye "última estación de tu recorrido").
                sonarYHablar(vozLlegada(seq, best), seq.get(best).linea, nom(seq.get(best)));
                handler.postDelayed(this::stopSelf, 15000);   // respaldo si el callback no llega
            }
            return;   // no se anuncian más estaciones tras el final
        } else if (ultLlegando == best && ultProxima != proxIdx && bd > radioCerca(seq.get(best)) + COBERTURA_EXTRA_M) {
            ultProxima = proxIdx;
            Telemetria.registrarEventoEstacion(this, seq.get(best).nombre, seq.get(best).linea, false);   // sales hacia "prox"
            // Te acercas a la estación de BAJADA si la parada siguiente a "prox" es un transbordo.
            boolean prepararse = proxIdx + 1 <= last && seq.get(proxIdx + 1).transbordo;
            // El jingle usa la línea que VAS VIAJANDO (la actual), no la de la próxima parada: así en
            // correspondencias co-ubicadas (p. ej. Indios Verdes) no suena el tururu de Metrobús mientras
            // sigues en Mexibús.
            sonarYHablar(vozProxima(seq, proxIdx, prepararse), seq.get(best).linea, nom(seq.get(proxIdx)));   // "Próxima estación X" (+ correspondencia / prep)
        // REVERTIDO: llegandoPorSalidaDeZona() (respaldo "entró a la zona ancha y ya se aleja del
        // mínimo") se quitó -- confirmado con video real que seguía disparando PREMATURO en el mismo
        // punto de siempre (el mínimo local se alcanza mucho antes de llegar al andén real), y ese
        // disparo temprano encadenaba: marca ultLlegando de golpe, lo que satisface alcanzasteAnterior
        // en el bloque de cruce de línea de arriba y salta YA a la línea nueva -- saltándose el aviso
        // de "próxima: Indios Verdes" (y su "favor de irte preparando") del lado Metrobús por completo.
        // Mejor que el aviso a veces no dispare por imprecisión de GPS (silencioso) a que dispare mal y
        // arrastre este efecto dominó. Vuelve a ser solo el radio angosto.
        } else if (bd <= radioLlegada(seq.get(best)) && ultLlegando != best) {
            ultLlegando = best;
            ultProxima = -99;
            Telemetria.registrarEventoEstacion(this, seq.get(best).nombre, seq.get(best).linea, true);
            if (best >= last && !finalizado) {
                // Última estación: el propio aviso de llegada ya incluye "última estación de tu
                // recorrido"; al terminar ese audio se finaliza el servicio automáticamente.
                finalizado = true;
                ultVoz = best;
                corresPendienteIdx = -1;   // recorrido terminado: sin candidato pendiente
                corresPendienteCiclos = 0;
                limpiarPersistencia();
                handler.removeCallbacks(tick);
                detenerUbicacion();
                Telemetria.finalizarRecorrido(this);
                alFinVoz = this::stopSelf;
                sonarYHablar(vozLlegada(seq, best), seq.get(best).linea, nom(seq.get(best)));
                handler.postDelayed(this::stopSelf, 15000);   // respaldo si el callback no llega
                return;
            }
            sonarYHablar(vozLlegada(seq, best) + sufijoCambio(seq, best), seq.get(best).linea, nom(seq.get(best)));   // "Llegando a X" (+ cambio de dirección/unidad/ruta)
        }
        // voz de afectación (una vez, si aparece durante el recorrido), con voz Mia.
        if (Manifestaciones.hay() && !afectacionAvisada) {
            afectacionAvisada = true;
            sonarYHablar(getString(R.string.voz_afectacion), seq.get(best).linea);
        } else if (!Manifestaciones.hay()) {
            afectacionAvisada = false;
        }
    }

    private void hablar(String t) {
        // Se llama desde el hilo PRINCIPAL en varios puntos del recorrido (aviso normal, respaldo
        // por timeout de la voz Mia, etc.); una falla del motor TTS (algunos fabricantes fallan al
        // recibir un Bundle o un idioma no instalado) no debe tumbar el recorrido activo.
        try {
            if (ttsListo && tts != null && t != null) {
                android.os.Bundle p = new android.os.Bundle();
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VOZ_VOL * volumenSistema());   // respeta el volumen de medios puesto por el usuario
                tts.speak(t, TextToSpeech.QUEUE_FLUSH, p, "geomb");
            }
        } catch (Throwable ex) {
            Telemetria.registrarError(this, Telemetria.ERR_EXCEPCION, "RecorridoService.hablar", String.valueOf(ex));
        }
    }

    /**
     * Encola el aviso (PRIMERO el "tururu" y, cuando TERMINA, la voz). Los avisos se reproducen uno
     * tras otro EN ORDEN, sin cancelar los pendientes: si dos eventos se disparan seguidos (p. ej.
     * "llegando, baja y realiza tu transbordo" y, unos segundos después, "próxima estación" del nuevo
     * tramo), antes el segundo invalidaba al primero mientras este todavía esperaba la voz (la descarga
     * de Mia puede tardar hasta el timeout de {@link #VOZ_TIMEOUT_MS}), y el aviso importante del
     * transbordo se perdía en silencio. Ahora simplemente espera su turno.
     */
    private void sonarYHablar(String texto) { sonarYHablar(texto, 0, null); }

    /** Sin estación específica (avisos generales: abordaje, afectaciones) -- cae en la carpeta
     *  "_general" (ver Locuciones.carpetaEstacion()). Para avisos DE una estación, usar el overload
     *  de abajo con su nombre. */
    private void sonarYHablar(String texto, int linea) { sonarYHablar(texto, linea, null); }

    /** Jingle según el sistema de la estación (Mexibús/Mexicable = tururu_mxb; Metrobús = tururu_mb) + voz.
     *  'estacion' es el nombre de LA estación a la que pertenece este aviso (ver Locuciones.nom()/
     *  carpetaEstacion()) -- organiza el caché de voz en voz/<voz>/<sistema>/L<línea>/<estación>/, o
     *  null para avisos que no son de una estación específica. */
    private void sonarYHablar(String texto, int linea, String estacion) {
        if (destruido) return;
        colaVoz.add(new Object[]{texto, linea, estacion});
        if (!vozOcupada) {
            pedirFocoAudio();   // "duckea" lo que esté sonando (música, podcast) mientras avisamos
            procesarSiguienteVoz();
        }
    }

    /** Toma el siguiente aviso de la cola y lo reproduce (tururu, luego voz). Al terminar (ver
     *  {@link #vozTerminada()}), se vuelve a llamar para seguir con el que sigue. */
    private void procesarSiguienteVoz() {
        Object[] item = colaVoz.poll();
        if (item == null || destruido) {
            vozOcupada = false;
            soltarFocoAudio();   // ya no hay más avisos: que lo demás recupere su volumen normal
            return;
        }
        vozOcupada = true;
        String texto = (String) item[0];
        int linea = (Integer) item[1];
        String estacion = (String) item[2];
        soltarActual();                       // corta cualquier audio en curso (evita el "agudo" por traslape)
        String raw = linea >= 100 ? "tururu_mxb" : "tururu_mb";
        int id = getResources().getIdentifier(raw, "raw", getPackageName());
        if (id == 0) id = getResources().getIdentifier("tururu", "raw", getPackageName());   // respaldo
        if (id != 0) {
            try {
                android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, id);
                if (mp != null) {
                    mpActual = mp;
                    float vs = volumenSistema();
                    mp.setVolume(TURURU_VOL * vs, TURURU_VOL * vs);   // ~70% para que no reviente los oídos
                    mp.setOnCompletionListener(m -> {
                        try { m.release(); } catch (IllegalStateException ignore) {}
                        if (mpActual == m) mpActual = null;
                        handler.postDelayed(() -> decirConVoz(texto, linea, estacion), 250);   // deja respirar antes de la voz
                    });
                    mp.setOnErrorListener((m, a, b) -> {
                        try { m.release(); } catch (IllegalStateException ignore) {}
                        if (mpActual == m) mpActual = null;
                        decirConVoz(texto, linea, estacion);
                        return true;
                    });
                    mp.start();
                    return;
                }
            } catch (Exception ignore) {}
        }
        decirConVoz(texto, linea, estacion);
    }

    /** Detiene y libera el reproductor en curso (tururu o voz) para que no se encimen. */
    private void soltarActual() {
        if (mpActual != null) {
            try { mpActual.stop(); } catch (IllegalStateException ignore) {}
            try { mpActual.release(); } catch (IllegalStateException ignore) {}
            mpActual = null;
        }
    }

    /**
     * Pide foco de audio de tipo "transitorio, puede bajar el volumen de lo demás" (ducking): mientras
     * dura el aviso, Android le baja el volumen a lo que ya estuviera sonando (música, podcast, etc.)
     * en vez de dejarlo mezclado a todo volumen encima del aviso. Se usa USAGE_ASSISTANCE_NAVIGATION_
     * GUIDANCE, el tipo estándar para avisos de navegación por voz (el mismo que usan Maps/Waze), que
     * el resto de las apps de audio ya saben manejar. No cambia el volumen del dispositivo: el aviso
     * sigue sonando al volumen de multimedia que tenga puesto el usuario, solo dejando de competir con
     * lo que ya sonaba.
     */
    private void pedirFocoAudio() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                //noinspection deprecation
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignore) {}
    }

    /** Suelta el foco de audio: lo que estaba sonando antes (música, podcast) recupera su volumen normal. */
    private void soltarFocoAudio() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                //noinspection deprecation
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignore) {}
    }

    // ---- voz Mia (AWS Polly) con caché local; respaldo al TTS de Android ----

    /** Reproduce la frase con la voz del backend (Mia o Ximena, según el sistema de `linea` -- ver
     *  Modos.vozActual()), cacheada. Si falla/offline, usa TTS. */
    private void decirConVoz(String texto, int linea, String estacion) {
        if (destruido) { vozOcupada = false; return; }
        java.io.File cache = archivoVoz(texto, linea, estacion);
        if (cache != null && cache.exists() && cache.length() > 0) { reproducir(cache); return; }   // ya en caché: no gasta datos
        // Modo ahorro de datos (activo por defecto en datos móviles, desactivable en "Acerca de"):
        // si esta frase no está ya cacheada, no se descarga la voz del backend -se habla directo con
        // la voz local del teléfono (TTS, sin conexión, cero datos).
        // Wi-Fi "conectada" pero sin internet real (router sin servicio, portal cautivo) con datos
        // móviles también encendidos: Android puede seguir marcando la Wi-Fi muerta como red activa,
        // así que sin este chequeo se intentaba la descarga sobre una red que nunca va a responder
        // -tardando mucho más en fallar (o colgándose) que "sin red" de plano, y dejando el aviso
        // varado sin avanzar a los siguientes de la cola.
        if (Red.ahorrarAhora(this) || !Red.hayInternetReal(this)) { hablar(texto); return; }
        // Descarga la voz del backend PERO con un límite (VOZ_TIMEOUT_MS): si no llega a tiempo, se
        // habla ya con el TTS de Google para no dejar esperando; la descarga sigue y queda cacheada
        // para la próxima. Este aviso YA está "en curso" (vozOcupada=true): el siguiente de la cola espera.
        final boolean[] resuelto = {false};
        handler.postDelayed(() -> {
            if (destruido || resuelto[0]) return;
            resuelto[0] = true;
            hablar(texto);   // se pasó del timeout: TTS inmediato
        }, VOZ_TIMEOUT_MS);
        new Thread(() -> {
            java.io.File out = descargarVoz(texto, linea, estacion);
            handler.post(() -> {
                if (destruido || resuelto[0]) return;   // ya habló el TTS por timeout
                resuelto[0] = true;
                if (out != null && out.exists() && out.length() > 0) reproducir(out);
                else hablar(texto);   // respaldo: TTS de Android (offline o error)
            });
        }, "voz-mia").start();
    }

    // Almacenamiento externo PROPIO de la app (Android/data/<paquete>/files/voz/, NO getCacheDir()):
    // igual que el interno, el sistema NUNCA lo borra bajo presión de espacio (solo al desinstalar
    // la app), pero a diferencia de getFilesDir() sí es accesible con un explorador de archivos --
    // el usuario puede revisar/copiar los .mp3 ya descargados. Sin permisos especiales: una carpeta
    // "de datos de la app" no necesita permiso de almacenamiento en ninguna versión de Android.
    // Si por lo que sea no hay almacenamiento externo disponible (SD extraíble desmontada, etc.),
    // cae al interno para no perder la función.
    // Subcarpetas voz/<voz>/<sistema>/L<línea>/<estación>/: para poder navegar/filtrar a mano con un
    // explorador de archivos (p. ej. ver solo lo de Mexibús, solo lo de Ximena, o solo una estación
    // dentro de una línea larga). El nombre del archivo ya no necesita llevar la voz ni la estación
    // (viven en la carpeta), solo el hash del texto. 'estacion' va sin sanitizar: carpetaEstacion()
    // se encarga (null/vacío -> "_general", ver su comentario).
    private java.io.File archivoVoz(String texto, int linea, String estacion) {
        java.io.File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        String voz = Modos.vozActual(this, linea);
        java.io.File dir = new java.io.File(base, "voz/" + voz + "/" + Sistemas.carpeta(Sistemas.sistemaDe(linea))
                + "/L" + Sistemas.numeroDeLinea(linea) + "/" + Locuciones.carpetaEstacion(estacion));
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, Integer.toHexString(texto.hashCode()) + ".mp3");
    }

    private java.io.File descargarVoz(String texto, int linea, String estacion) {
        java.io.File out = archivoVoz(texto, linea, estacion);
        if (out == null) return null;
        java.net.HttpURLConnection c = null;
        try {
            String u = "https://geomb.duckdns.org/api/tts?voz=" + Modos.vozActual(this, linea)
                    + "&texto=" + android.net.Uri.encode(texto);
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() / 100 != 2) return null;
            try (java.io.InputStream in = c.getInputStream();
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return out.length() > 0 ? out : null;
        } catch (java.io.IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void reproducir(java.io.File f) {
        try {
            soltarActual();   // no encimar con audio previo
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mpActual = mp;
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getAbsolutePath());
            float vs = volumenSistema();
            mp.setVolume(VOZ_VOL * vs, VOZ_VOL * vs);   // respeta el volumen de medios puesto por el usuario
            mp.setOnCompletionListener(m -> {
                try { m.release(); } catch (IllegalStateException ignore) {}
                if (mpActual == m) mpActual = null;
                vozTerminada();   // terminó la voz: acción pendiente de fin + siguiente aviso en cola
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            vozTerminada();   // si el mp3 falla, no dejar colgada la acción de fin ni la cola
        }
    }

    /** Nombre para HABLAR: sin el prefijo interno "MXB " ni el paréntesis de conexión
     *  ("Pantitlán (conexión Metrobús L4)" → "Pantitlán"). */
    private static String nom(Planificador.Parada p) {
        if (p == null) return null;
        String s = Planificador.sinMxb(p.nombre);
        int par = s.indexOf('(');
        return respellVoz(par >= 0 ? s.substring(0, par).trim() : s);
    }

    /** Respeta el nombre EXACTO para mostrar, pero corrige la ortografía SOLO para la voz de siglas/
     *  palabras que el TTS lee mal (acrónimos sin vocales que pronunciar, o palabras en inglés leídas con
     *  reglas fonéticas del español): debe mantenerse en sincronía con Locuciones.nom() y
     *  DescargaVoz.nomDe(), sus réplicas para pre-descargar/cachear el mismo audio. */
    private static String respellVoz(String nombre) {
        if (nombre == null) return null;
        return nombre
                .replace("ISSEMYM", "I-semim")        // "Isemim" (sin guión) sonaba "esemim": se perdía la I inicial
                .replace("CCH", "Cecehache")          // sin vocales que leer: intentaba deletrearlo mal
                .replace("SCOP", "Escop")             // el español no empieza palabra con "sc-"
                .replace("New's Divine", "Niuz Diváin")   // nombre en inglés leído letra por letra
                // "1ro"/"1°" leído literal ("uno Ro", "uno grado") en vez de "Primero" -- defecto real
                // confirmado en "1ro de Mayo" (Mexibús L1/L1 Exprés); "1° de Mayo" (L2) es el mismo caso.
                .replace("1ro de Mayo", "Primero de Mayo")
                .replace("1° de Mayo", "Primero de Mayo")
                // Nombres de 2 localidades unidas por guión (p. ej. "Bandera - Tultitlán", "Héroes -
                // Canosas", "Lindavista - Vallejo"): el TTS leía el guión como una pausa/separación
                // rara ("guion largo"). Coma: pausa natural de enumeración, sin el símbolo.
                .replace(" - ", ", ");
    }

    /** Nombre para MOSTRAR (notificación): sin MXB y con nº de línea si el nombre se repite. */
    private String vis(Planificador.Parada p) {
        return p != null ? Planificador.nombreMostrar(this, p.nombre, p.linea) : null;
    }

    /** Elige entre el string normal y su variante "_ximena" (redacción + pausas largas calcadas del
     *  anuncio real del Metrobús) según si ESTA línea tiene la voz Ximena activa (Modos.vozActual,
     *  por sistema: Metrobús/Mexibús/Mexicable son independientes). Mia/TTS del sistema nunca ven
     *  la variante Ximena. */
    private int vozTxt(int normal, int ximena, int linea) {
        return "Ximena".equals(Modos.vozActual(this, linea)) ? ximena : normal;
    }

    /** Aviso de PRÓXIMA estación. Si tiene correspondencia (líneas cercanas) la nombra; y si el usuario
     *  transbordará ahí, añade la preparación ("atención… favor de irte preparando"). */
    private String vozProxima(List<Planificador.Parada> seq, int idx, boolean prepararse) {
        Planificador.Parada prox = seq.get(idx);
        if (esTerminal(prox) && !esAbordaje(seq, idx)) return vozTerminal(prox, false);   // terminal de línea (al llegar)
        String tf = transferenciaTexto(prox.linea, basesCorresp(prox));
        StringBuilder v = tf.isEmpty()
                ? new StringBuilder(getString(vozTxt(R.string.voz_proxima, R.string.voz_proxima_ximena, prox.linea), nom(prox)))
                : new StringBuilder(getString(vozTxt(R.string.voz_prox_base, R.string.voz_prox_base_ximena, prox.linea), nom(prox))).append(tf);
        // "Favor de irte preparando" cuando te ACERCAS a la estación donde bajarás a hacer la
        // correspondencia (no cuando ya llegaste al andén de la otra línea).
        if (prepararse) v.append(". ").append(getString(R.string.voz_transbordo_prep));
        return v.toString();
    }

    /** ¿La parada es de ABORDAJE (subes a esta línea aquí y sigues)? Es decir, cambiaste de línea en ella
     *  y continúas. En ese caso NO debe sonar el aviso de terminal ("no permanezca a bordo"): tú apenas
     *  abordas. El aviso de terminal solo aplica cuando LLEGAS/bajas en la terminal. */
    private boolean esAbordaje(List<Planificador.Parada> seq, int idx) {
        int base = baseLinea(seq.get(idx).linea);
        boolean previaDistinta = idx == 0 || baseLinea(seq.get(idx - 1).linea) != base;
        boolean sigueMisma = idx + 1 < seq.size() && baseLinea(seq.get(idx + 1).linea) == base;
        return previaDistinta && sigueMisma;
    }

    /** Aviso de LLEGADA a la estación best. Terminal → mensaje de terminal; con correspondencia → nombra
     *  las líneas con su palabra (transbordo/correspondencia/conexión); si no, aviso simple + consejo. */
    private String vozLlegada(List<Planificador.Parada> seq, int best) {
        Planificador.Parada p = seq.get(best);
        boolean esFinal = best == seq.size() - 1;   // última estación del recorrido
        if (esTerminal(p) && !esAbordaje(seq, best)) {
            String vt = vozTerminal(p, true);
            if (esFinal) vt += ". " + getString(ultimaEst(p.linea));
            return vt;
        }
        // ¿Bajas aquí a cambiar? La parada siguiente arranca otro tramo (línea/conexión, unidad o ruta).
        int tcSig = best + 1 < seq.size() ? tipoCambio(seq, best + 1) : 0;
        boolean bajas = tcSig != 0 || (best + 1 < seq.size() && seq.get(best + 1).transbordo);
        String tf = transferenciaTexto(p.linea, basesCorresp(p));
        if (tf.isEmpty() && !bajas) {
            StringBuilder v = new StringBuilder(getString(vozTxt(R.string.voz_llegando_est, R.string.voz_llegando_est_ximena, p.linea), nom(p)));
            if (esFinal) {
                v.append(". ").append(getString(ultimaEst(p.linea)));   // "última estación de tu recorrido"
            } else {
                String tip = tipAleatorio(p.linea);
                if (tip != null) v.append(". ").append(tip);
            }
            return v.toString();
        }
        StringBuilder v = new StringBuilder(getString(vozTxt(R.string.voz_lleg_base, R.string.voz_lleg_base_ximena, p.linea), nom(p))).append(tf);
        if (bajas) {
            // Instrucción de bajada según el cambio: transbordo/correspondencia, conexión (camina) o
            // cambio de servicio/ruta (baja y toma el servicio con dirección).
            v.append(". ").append(instruccionBajada(seq, best, tcSig != 0 ? tcSig : 1));
        }
        if (esFinal) v.append(". ").append(getString(ultimaEst(p.linea)));
        return v.toString();
    }

    /** Total de estaciones del viaje: saltos reales entre paradas físicas DISTINTAS (los transbordos
     *  co-ubicados no se cuentan dos veces). */
    private int contarEstaciones(List<Planificador.Parada> seq) {
        int n = 0;
        for (int i = 1; i < seq.size(); i++)
            if (!mismaEstacion(seq.get(i - 1), seq.get(i))) n++;
        return Math.max(n, 1);
    }

    /** Tipo de cambio de trazo al ENTRAR a la parada i (respecto de la anterior):
     *  1 = cambio de línea (dirección), 2 = cambio de unidad (ordinario↔exprés), 3 = cambio de ruta
     *  (L4 Norte↔Sur), 0 = sin cambio. */
    private int tipoCambio(List<Planificador.Parada> seq, int i) {
        if (i <= 0 || i >= seq.size()) return 0;
        Planificador.Parada a = seq.get(i - 1), b = seq.get(i);
        if (baseLinea(a.linea) != baseLinea(b.linea)) return 1;                 // otra línea → dirección
        boolean exprA = a.linea >= 121 && a.linea <= 124, exprB = b.linea >= 121 && b.linea <= 124;
        if (exprA != exprB) return 2;                                            // ordinario↔exprés → unidad
        if (baseLinea(b.linea) == 4) {
            int ra = RutasMixtas.rutaL4(a.nombre), rb = RutasMixtas.rutaL4(b.nombre);
            if (ra != 0 && rb != 0 && ra != rb) return 3;                        // Norte↔Sur → ruta
        }
        return 0;
    }

    /** Mexicable (línea >= 200) se viaja en CABINA (teleférico), no "unidad": elige el string correcto. */
    private static int ultimaEst(int linea) {
        return linea >= 200 ? R.string.voz_ultima_est_cabina : R.string.voz_ultima_est;
    }
    private static int terminalEst(int linea) {
        return linea >= 200 ? R.string.voz_terminal_cabina : R.string.voz_terminal;
    }

    /** Sufijo al ABORDAR el nuevo tramo (primera parada de la línea nueva): "Toma la unidad/el autobús/
     *  la cabina con dirección {terminal}". Solo para cambio de LÍNEA (transbordo/correspondencia/
     *  conexión); los cambios de unidad/ruta ya se anuncian en la bajada. "" si aquí no empieza otra línea. */
    private String sufijoCambio(List<Planificador.Parada> seq, int i) {
        if (tipoCambio(seq, i) != 1) return "";
        int lineaNueva = seq.get(i).linea;
        boolean conexion = palabraTransferencia(seq.get(i - 1).linea, lineaNueva)
                == R.string.voz_palabra_conexion;
        String terminal = direccionTerminal(seq, i);
        int str = lineaNueva >= 200 ? R.string.voz_toma_cabina        // Mexicable: cabina, no unidad
                : conexion ? R.string.voz_toma_autobus : R.string.voz_toma_unidad;
        StringBuilder v = new StringBuilder(getString(str, terminal));
        // Defecto real confirmado (L1/L2/L3 Mexibús abordados por TRANSBORDO): este sufijo nunca
        // mencionaba el tipo de servicio (Ordinario/Exprés), a diferencia de avisoAbordaje() (primer
        // abordaje del viaje), que sí lo hace -- mismo patrón aquí, igual para las 4 líneas de Mexibús.
        String svc = servicioNombre(seq, i, esRosa());
        if (!svc.isEmpty()) v.append(getString(R.string.voz_aborda_servicio, svc));
        return ". " + v;
    }

    /** Aviso inicial de abordaje: "Aborda una unidad/cabina con destino a {terminal}[, servicio {X}]
     *  [, en unidad rosa…]. {N} estaciones." */
    private String avisoAbordaje(List<Planificador.Parada> seq, int i) {
        int str = seq.get(i).linea >= 200 ? R.string.voz_aborda_cabina : R.string.voz_aborda;   // Mexicable: cabina
        StringBuilder v = new StringBuilder(getString(str, direccionTerminal(seq, i)));
        String svc = servicioNombre(seq, i, esRosa());
        if (!svc.isEmpty()) v.append(getString(R.string.voz_aborda_servicio, svc));
        if (esRosa()) v.append(getString(R.string.voz_unidad_rosa));
        v.append(". ").append(getString(R.string.voz_n_estaciones, contarEstaciones(seq)));
        return v.toString();
    }

    /** ¿El viaje va en unidad Rosa? (se decidió al elegir el servicio; el texto lo incluye). */
    private boolean esRosa() {
        return servicioTexto != null && Planificador.norm(servicioTexto).contains("rosa");
    }

    /** Nombre de servicio Mexibús para la voz: tipo (Ordinario/Express) anteponiendo el CÓDIGO del
     *  servicio (p. ej. "TR1 Ordinario", "Express 3") cuando el tramo recorrido identifica uno solo.
     *  "" para Metrobús/Mexicable. */
    private String servicioNombre(List<Planificador.Parada> seq, int i, boolean rosa) {
        int linea = seq.get(i).linea;
        boolean express = linea >= 121 && linea <= 124;
        boolean ordinario = linea >= 101 && linea <= 104;
        if (!express && !ordinario) return "";
        String tipo = getString(express ? R.string.servicio_express : R.string.servicio_ordinario);
        int base = Servicios.base(linea);
        String cod = null;
        if (express) {   // identifica la variante por las paradas del tramo actual (misma base)
            int fin = i;
            while (fin + 1 < seq.size() && Servicios.base(seq.get(fin + 1).linea) == base) fin++;
            java.util.List<String> tramo = new java.util.ArrayList<>();
            for (int k = i; k <= fin; k++) tramo.add(seq.get(k).nombre);
            cod = ServiciosMexibus.codigoPorTramo(this, base, true, rosa, tramo);
        }
        if (cod == null) cod = ServiciosMexibus.codigoUnico(this, base, express, rosa);
        return cod != null ? cod + " " + tipo : tipo;
    }

    /** Instrucción de bajada al terminar el tramo (best), según el cambio hacia best+1:
     *  conexión → "Camina hasta la conexión con {estación} {línea}"; unidad → "Baja y toma el servicio
     *  {X} con dirección a {terminal}"; ruta → "Baja y toma la unidad con dirección a {terminal}";
     *  transbordo/correspondencia → "Baja y realiza tu {palabra}". */
    private String instruccionBajada(List<Planificador.Parada> seq, int best, int tc) {
        int i = best + 1;
        if (i >= seq.size()) return "";
        String terminal = direccionTerminal(seq, i);
        if (tc == 2) return getString(R.string.voz_baja_servicio, servicioNombre(seq, i, esRosa()), terminal);
        if (tc == 3) return getString(seq.get(i).linea >= 200   // Mexicable: cabina, no unidad
                ? R.string.voz_baja_cabina_dir : R.string.voz_baja_unidad_dir, terminal);
        int palabra = palabraTransferencia(seq.get(best).linea, seq.get(i).linea);
        if (palabra == R.string.voz_palabra_conexion)
            return getString(R.string.voz_camina_conexion, nombreConexion(seq, i));
        return getString(R.string.voz_baja_realiza, getString(palabra));   // transbordo / correspondencia
    }

    /** "{estación} {etiqueta de línea}" de la parada de conexión (para "camina hasta la conexión con …"). */
    private String nombreConexion(List<Planificador.Parada> seq, int i) {
        Planificador.Parada p = seq.get(i);
        return nom(p) + " " + etiquetaLinea(baseLinea(p.linea), p.nombre);
    }

    /** Terminal de DIRECCIÓN del tramo que inicia en i. Metrobús: terminal de la RUTA (horarios) más
     *  cercana en tu sentido (mixtas adelante); respaldo y demás sistemas: terminal de la línea. */
    private String direccionTerminal(List<Planificador.Parada> seq, int i) {
        Planificador.Parada p = seq.get(i);
        int base = baseLinea(p.linea);
        int fin = i;
        while (fin + 1 < seq.size() && baseLinea(seq.get(fin + 1).linea) == base) fin++;
        String t = null;
        // Metrobús de ORDEN LINEAL (1,3,5,6): terminal(es) por horarios, permitiendo la lista "A o B".
        boolean lineal = base == 1 || base == 3 || base == 5 || base == 6;
        if (lineal && Horarios.tieneLinea(this, base)) {
            Linea l = GtfsRepository.porNumero(this, base);
            if (l != null && l.estaciones != null && !l.estaciones.isEmpty()) {
                int ia = Horarios.idxEnLinea(l, seq.get(i).nombre);
                int ib = Horarios.idxEnLinea(l, seq.get(fin).nombre);
                if (ia >= 0 && ib >= 0 && ia != ib) t = Horarios.terminalHorario(this, base, l, ia, ib);
            }
        }
        // Couplets de Metrobús (L2/L4/L7): su línea base no es lineal, así que se usa la terminal que ya
        // calculó el planificador para este tramo (el conteo por transbordos casa con sus tramos).
        else if (base == 2 || base == 4 || base == 7) t = terminalPlanificador(seq, i);
        if (t == null) t = terminalSentido(seq, i);
        // Salvaguarda: la dirección nunca debe ser la MISMA estación donde vas/abordas; si lo fuera
        // (datos raros), usa el final del tramo (tu destino en esta línea).
        String aqui = Horarios.nomNombre(seq.get(i).nombre);
        if (t == null || Planificador.norm(t).equals(Planificador.norm(aqui))) t = Horarios.nomNombre(seq.get(fin).nombre);
        return t;
    }

    /** Terminal del SENTIDO de viaje del tramo que empieza en i: la estación extrema de la línea hacia la
     *  que avanza la secuencia. Respaldo: la última parada del tramo (donde bajas/terminas). */
    private String terminalSentido(List<Planificador.Parada> seq, int i) {
        Planificador.Parada p = seq.get(i);
        int base = baseLinea(p.linea);
        int fin = i;
        while (fin + 1 < seq.size() && baseLinea(seq.get(fin + 1).linea) == base) fin++;
        Linea l = GtfsRepository.porNumero(this, p.linea);
        if (l != null && l.estaciones != null && !l.estaciones.isEmpty()) {
            int ia = Horarios.idxEnLinea(l, seq.get(i).nombre);
            int ib = Horarios.idxEnLinea(l, seq.get(fin).nombre);
            if (ia >= 0 && ib >= 0 && ia != ib) {
                // Preferir el nombre CANÓNICO de terminal (Planificador.terminales, curado a mano)
                // sobre el nombre crudo de la primera/última estación registrada en los datos de la
                // línea: en un exprés ese extremo puede ser un andén intermedio compartido con otra
                // línea (p. ej. "Puente de Fierro" en Mexibús L2 Exprés) en vez de la terminal real
                // ("Ecatepec"), que es justo lo que ya usa el texto en pantalla -- la voz decía el
                // nombre equivocado. Si la línea no está en la tabla canónica, cae al respaldo de siempre.
                String[] canon = Planificador.terminales(p.linea);
                if (canon != null) return Horarios.nomNombre(ib > ia ? canon[1] : canon[0]);
                Estacion term = ib > ia ? l.estaciones.get(l.estaciones.size() - 1) : l.estaciones.get(0);
                return Horarios.nomNombre(term.nombre);
            }
        }
        return Horarios.nomNombre(seq.get(fin).nombre);   // respaldo: última parada del tramo
    }

    /** Terminal (dirección) que el planificador calculó para el tramo que contiene la parada i. El índice
     *  de tramo = nº de transbordos hasta i (así se alinea con {@link #terminalesTramo}). null si no hay. */
    private static String terminalPlanificador(List<Planificador.Parada> seq, int i) {
        java.util.List<String> ts = terminalesTramo;
        if (ts == null || ts.isEmpty()) return null;
        int leg = 0;
        for (int k = 1; k <= i && k < seq.size(); k++) if (seq.get(k).transbordo) leg++;
        return (leg >= 0 && leg < ts.size()) ? ts.get(leg) : null;
    }

    /**
     * Aviso de estación TERMINAL de línea (llegada o próxima). Ej.:
     *  · Metrobús L1 Indios Verdes: "…estación terminal, Indios Verdes, transbordo con Línea 7.
     *    Conexión con Meksibús Línea 4 y Meksicable Línea 2".
     *  · Mexibús L4 La Raza: "…estación terminal, La Raza, conexión con Metrobús Líneas 1 y 3".
     */
    private String vozTerminal(Planificador.Parada p, boolean llegada) {
        int base = llegada ? R.string.voz_term_lleg : R.string.voz_term_prox;
        int ximena = llegada ? R.string.voz_term_lleg_ximena : R.string.voz_term_prox_ximena;
        StringBuilder v = new StringBuilder(getString(vozTxt(base, ximena, p.linea), nom(p)));
        return v.append(transferenciaTexto(p.linea, basesCorresp(p))).toString();
    }

    /** Bases de correspondencia de la parada, respetando la visibilidad de cada capa (Mexibús/Mexicable
     *  se controlan por separado: si una está apagada, no se anuncia ESE sistema, no ambos). */
    private java.util.TreeSet<Integer> basesCorresp(Planificador.Parada p) {
        java.util.TreeSet<Integer> bases = lineasEnEstacion(p);
        correspManuales(p, bases);   // enlaces que no tienen estación física del otro sistema en los datos
        boolean mxb = Modos.mostrarMexibus(this);
        boolean mxc = Modos.mostrarMexicable(this);
        java.util.Iterator<Integer> it = bases.iterator();
        while (it.hasNext()) {
            int sis = Sistemas.sistemaDe(it.next());
            if ((sis == Sistemas.MEXIBUS && !mxb) || (sis == Sistemas.MEXICABLE && !mxc)) it.remove();
        }
        return bases;
    }

    /** Correspondencias declaradas en el propio nombre de la estación (p. ej. Pantitlán / Calle 6 de
     *  Mexibús L3 → "(conexión Metrobús L4)"): se leen del texto y se añaden a las bases. */
    private void correspManuales(Planificador.Parada p, java.util.TreeSet<Integer> bases) {
        String nn = Planificador.norm(p.nombre);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(metrobus|mexibus|mexicable)\\s+l\\s*([0-9]+)").matcher(nn);
        while (m.find()) {
            int off = m.group(1).equals("metrobus") ? 0 : (m.group(1).equals("mexibus") ? 100 : 200);
            try { bases.add(off + Integer.parseInt(m.group(2))); } catch (NumberFormatException ignore) {}
        }
        bases.remove(baseLinea(p.linea));
    }

    /**
     * Texto de transferencia de una estación: agrupa las líneas por PALABRA (transbordo Metrobús↔Metrobús,
     * correspondencia Mexibús/Mexicable entre sí, conexión entre Metrobús y Edomex) y, dentro de cada palabra,
     * por sistema, anteponiendo el nombre del sistema cuando difiere del que se viaja. Ej. desde Mexibús:
     * ", conexión con Metrobús Líneas 1 y 3".
     */
    private String transferenciaTexto(int lineaActual, java.util.TreeSet<Integer> bases) {
        // claveTerminal() (NO baseLinea()): 'bases' ahora puede traer un ramal (111/112/113) distinto
        // de su ordinario (ver lineasEnEstacion()) -- si aquí se quitara con baseLinea(), viajando en
        // el RAMAL (p. ej. 112) esto borraba por error la mención real del ordinario (102), porque
        // baseLinea(112) también da 102. claveTerminal() solo quita lo que de verdad es "yo mismo"
        // (colapsa ordinario↔exprés, deja los ramales distintos).
        bases.remove(Planificador.claveTerminal(lineaActual));
        if (bases.isEmpty()) return "";
        int[] orden = {R.string.voz_palabra_transbordo, R.string.voz_palabra_correspondencia, R.string.voz_palabra_conexion};
        java.util.Map<Integer, java.util.TreeSet<Integer>> porPalabra = new java.util.HashMap<>();
        for (int r : orden) porPalabra.put(r, new java.util.TreeSet<>());
        for (int n : bases) porPalabra.get(palabraTransferencia(lineaActual, n)).add(n);
        StringBuilder v = new StringBuilder();
        boolean primero = true;
        for (int r : orden) {
            java.util.TreeSet<Integer> g = porPalabra.get(r);
            if (g.isEmpty()) continue;
            String lista = listaLineas(lineaActual, g);
            String palabra = getString(r);
            if (primero) { v.append(getString(vozTxt(R.string.voz_tf_primera, R.string.voz_tf_primera_ximena, lineaActual), palabra, lista)); primero = false; }
            else { v.append(getString(vozTxt(R.string.voz_tf_sig, R.string.voz_tf_sig_ximena, lineaActual), cap(palabra), lista)); }
        }
        return v.toString();
    }

    /** Lista de líneas agrupadas por sistema: "Líneas 1 y 3" (mismo sistema) o "Metrobús Líneas 1 y 3"
     *  / "Meksibús Línea 4 y Meksicable Línea 2" (otro sistema, con su nombre). */
    private String listaLineas(int lineaActual, java.util.TreeSet<Integer> bases) {
        int sisAct = Sistemas.sistemaDe(lineaActual);
        java.util.LinkedHashMap<Integer, java.util.List<String>> porSis = new java.util.LinkedHashMap<>();
        for (int n : bases) porSis.computeIfAbsent(Sistemas.sistemaDe(n), k -> new java.util.ArrayList<>()).add(numLinea(n));
        java.util.List<String> partes = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.List<String>> e : porSis.entrySet()) {
            java.util.List<String> nums = e.getValue();
            boolean varias = nums.size() > 1;
            if (e.getKey() == sisAct) {
                partes.add(getString(varias ? R.string.voz_lineas_num : R.string.voz_linea_num, unir(nums)));
            } else {
                String sis = getString(e.getKey() == 0 ? R.string.voz_sis_mb
                        : e.getKey() == 1 ? R.string.voz_sis_mxb : R.string.voz_sis_mxc);
                partes.add(getString(varias ? R.string.voz_sis_lineas : R.string.voz_sis_linea, sis, unir(nums)));
            }
        }
        return unir(partes);
    }

    /** Número visible de una base de línea: Metrobús 1..7, Mexibús 10X→X, Mexicable 20X→X. */
    private static String numLinea(int base) {
        return String.valueOf(base >= 200 ? base - 200 : (base >= 100 ? base - 100 : base));
    }

    /** Capitaliza la primera letra (para iniciar frase tras un punto). */
    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Une una lista en "a", "a y b" o "a, b y c". */
    private static String unir(java.util.List<String> ls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return sb.toString();
    }

    /** Lista de líneas de correspondencia (por cercanía) de la estación, en orden; null si ninguna. */
    private String lineasCorrespLista(Planificador.Parada p) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(p);
        lineas.remove(baseLinea(p.linea));
        if (lineas.isEmpty()) return null;
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (int n : lineas) ls.add(etiquetaLinea(n, p.nombre));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return sb.toString();
    }

    /** Primera línea (base) cercana, para decidir la palabra (correspondencia/transbordo/conexión). */
    private int primeraLineaCercana(Planificador.Parada p) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(p);
        lineas.remove(baseLinea(p.linea));
        return lineas.isEmpty() ? p.linea : lineas.first();
    }

    /** Arma el aviso de llegada: "Llegando a estación: X" (+ correspondencia / terminal / consejo). */
    private String avisoLlegada(List<Planificador.Parada> seq, int best) {
        Planificador.Parada p = seq.get(best);
        boolean term = esTerminal(p);
        StringBuilder v = new StringBuilder(getString(
                term ? R.string.voz_llegando_terminal : R.string.voz_llegando_est, nom(p)));
        // transferenciaTexto() elige la palabra correcta según los sistemas (transbordo Metrobús↔Metrobús,
        // correspondencia Mexibús/Mexicable↔Mexibús/Mexicable, conexión Metrobús↔Edomex); antes se usaba
        // transbordoTexto(), que decía siempre "Correspondencia con..." sin importar el sistema.
        String tf = transferenciaTexto(p.linea, basesCorresp(p));
        if (!tf.isEmpty()) v.append(tf);
        if (term) v.append(". ").append(getString(terminalEst(p.linea)));
        String tip = tipAleatorio(p.linea);
        if (tip != null) v.append(". ").append(tip);
        return v.toString();
    }

    /** Palabra del cambio de servicio según sistemas: Metrobús=transbordo, Mexibús/Mexicable=correspondencia, mixto=conexión. */
    private int palabraTransferencia(int lineaActual, int lineaOtra) {
        boolean aMetro = lineaActual < 100, bMetro = lineaOtra < 100;
        if (aMetro && bMetro) return R.string.voz_palabra_transbordo;
        if (!aMetro && !bMetro) return R.string.voz_palabra_correspondencia;
        return R.string.voz_palabra_conexion;
    }

    /**
     * Aviso completo al acercarse a la estación de cambio: "Atención, estás llegando a la estación donde
     * realizarás tu {palabra}, favor de irte preparando. Estación {nombre}. {palabra} con {líneas}."
     * La palabra (correspondencia/conexión/transbordo) depende de los sistemas involucrados.
     */
    private String avisoCorrespondencia(Planificador.Parada prox) {
        java.util.TreeSet<Integer> lineas = lineasEnEstacion(prox);
        lineas.remove(baseLinea(prox.linea));
        if (lineas.isEmpty()) return null;
        String palabra = getString(palabraTransferencia(prox.linea, lineas.first()));
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (int n : lineas) ls.add(etiquetaLinea(n, prox.nombre));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (i == 0) sb.append(ls.get(i));
            else if (i == ls.size() - 1) sb.append(" y ").append(ls.get(i));
            else sb.append(", ").append(ls.get(i));
        }
        return getString(R.string.voz_prep_corresp, palabra, nom(prox), sb.toString());
    }

    /** Etiqueta de línea para la correspondencia; L4 añade su ruta (Norte/Sur) según la estación. */
    private String etiquetaLinea(int n, String estacion) {
        if (n >= 200) return getString(R.string.voz_linea_mxc, n - 200);   // Mexicable Línea X
        // Ramales Mexibús (111/112/113): "Línea 1A/2A/3A", no "Línea 11/12/13" (n-100 a secas). Defecto
        // real confirmado: sin este caso, un ramal llegado por correspondencia MANUAL (ver
        // corrManualLineas(), que SÍ preserva el número exacto del ramal) se anunciaba como "Línea 12".
        if (n >= 111 && n <= 113) return getString(R.string.voz_linea_mxb, n - 110) + "A";
        if (n >= 100) return getString(R.string.voz_linea_mxb, n - 100);   // Mexibús Línea X
        if (n == 4) {
            int r = RutasMixtas.rutaL4(estacion);
            if (r == 1) return getString(R.string.voz_linea_l4, getString(R.string.voz_ruta_norte));
            if (r == 2) return getString(R.string.voz_linea_l4, getString(R.string.voz_ruta_sur));
            // ambas rutas (Buenavista, San Lázaro, …): "L4" a secas
        }
        return getString(R.string.voz_linea_n, n);
    }

    private static final double CORRESP_VOZ_M = 600.0;   // radio para considerar correspondencia por voz

    /** Línea base (agrupa ordinario/ramal/exprés de una misma línea, y Mexicable por su número). */
    private static int baseLinea(int n) {
        if (n >= 200) return 200 + (n % 10);
        if (n >= 100) return 100 + (n % 10);
        return n;
    }

    // Palabras a ignorar al extraer el NÚCLEO de un nombre de estación (prefijos de sistema, "conexión", etc.).
    private static final java.util.Set<String> STOP_NUCLEO = new java.util.HashSet<>(java.util.Arrays.asList(
            "mxb", "mxc", "mb", "conexion", "mexibus", "meksibus", "mexicable", "meksicable",
            "metrobus", "linea", "y", "e"));

    /** Núcleo del nombre: sin prefijos de sistema, "conexión", ni tokens de línea (l1, l4, l1a). */
    private static String nucleo(String nombre) {
        StringBuilder sb = new StringBuilder();
        for (String w : Planificador.norm(nombre).split(" ")) {
            if (w.isEmpty() || STOP_NUCLEO.contains(w) || w.matches("l[0-9]+a?")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** ¿Coinciden los núcleos de dos nombres (igual o uno contiene al otro)? */
    private static boolean nucleoCoincide(String a, String b) {
        String na = nucleo(a), nb = nucleo(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** Clave de servicio: une ordinario↔exprés de la misma línea (104↔124), pero NO los ramales. */
    private static int claveServicio(int n) { return (n >= 121 && n <= 124) ? (100 + n % 10) : n; }

    /** ¿Dos paradas son ANDENES CO-UBICADOS del mismo lugar (para no repetir avisos)? Igual que
     *  {@link #mismaEstacion} pero además acepta el MISMO NÚCLEO de nombre a corta distancia, para casar
     *  andenes de distinto sistema cuyo nombre difiere por el paréntesis de conexión (p. ej. Indios Verdes
     *  "…" ↔ "Indios Verdes (conexión Metrobús L1 y L7)"). El límite de distancia evita casar estaciones
     *  homónimas lejanas. */
    static boolean coUbicada(Planificador.Parada a, Planificador.Parada b) {
        if (mismaEstacion(a, b)) return true;
        if (a == null || b == null || a.pos == null || b.pos == null) return false;
        return nucleoCoincide(a.nombre, b.nombre) && Linea.distancia(a.pos, b.pos) < 350.0;
    }

    /** ¿Dos paradas son la MISMA estación física? Ordinario↔exprés de la misma línea, o mismo nombre
     *  normalizado (correspondencia con el mismo nombre, p. ej. Puente de Fierro L2↔L4). */
    private static boolean mismaEstacion(Planificador.Parada a, Planificador.Parada b) {
        if (a == null || b == null) return false;
        // OJO: claveServicio(n) devuelve n para cualquier línea no exprés, así que comparar solo la
        // clave sin exigir líneas DISTINTAS haría "misma estación" a cualquier par de la misma línea
        // (a.linea == b.linea siempre cumple), rompiendo el cálculo de la próxima parada a anunciar.
        if (a.linea != b.linea && claveServicio(a.linea) == claveServicio(b.linea)) return true;
        return Planificador.norm(Planificador.sinMxb(a.nombre))
                .equals(Planificador.norm(Planificador.sinMxb(b.nombre)));
    }

    /**
     * Líneas con las que ESTA estación tiene correspondencia. Recorre TODAS las líneas (Metrobús +
     * Mexibús + Mexicable). Las correspondencias se aferran a estaciones reales: DENTRO del mismo
     * sistema (Metrobús↔Metrobús o Mexibús↔Mexibús) exige MISMO NOMBRE (así Puente de Fierro L2↔L4 sí,
     * pero Casa de Morelos —única de L2— no inventa correspondencia con L4 a 500 m). ENTRE sistemas se
     * enlaza por cercanía (≤ CORRESP_VOZ_M) aunque el nombre difiera (Santa Clara/Periférico ↔ Mexicable).
     */
    private java.util.TreeSet<Integer> lineasEnEstacion(Planificador.Parada p) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        if (p == null || p.pos == null) return s;
        // claveTerminal() (NO baseLinea()): baseLinea() colapsa ordinario↔ramal además de
        // ordinario↔exprés (101/111/121 dan los 3 el mismo 101), así que esta comparación se
        // saltaba por error cualquier ramal que comparte estación física con su ordinario (p. ej.
        // "Ojo de Agua" L1↔L1A, "Las Américas" L2↔L2A, "Acuitlapilco" L3↔L3A -- defecto real
        // confirmado: ninguno de esos 3 avisaba la correspondencia con el ramal). claveTerminal()
        // SÍ colapsa exprés (121→101) pero deja los ramales (111/112/113) distintos.
        int bp = Planificador.claveTerminal(p.linea);
        int sisP = Sistemas.sistemaDe(p.linea);
        try {
            String pn = Planificador.norm(Planificador.sinMxb(p.nombre));
            java.util.List<Linea> todas = new java.util.ArrayList<>(GtfsRepository.getLineas(this));
            todas.addAll(GtfsRepository.getMexibus(this));
            for (Linea l : todas) {
                if (Planificador.claveTerminal(l.numero) == bp) continue;   // mismo servicio: no es transbordo
                boolean mismoSistema = Sistemas.sistemaDe(l.numero) == sisP;
                for (Estacion e : l.estaciones) {
                    if (e.soloMapa) continue;
                    // Mismo sistema: MISMO nombre, o mismo NÚCLEO como respaldo (p. ej. Buenavista L1/L4 vs
                    // "Buenavista II"/"Buenavista III" de L3: mismo andén, nombre con sufijo distinto). Entre
                    // sistemas (Mexibús↔Mexicable/Metrobús): exige que el NÚCLEO del nombre coincida (Santa
                    // Clara↔Santa Clara, Periférico↔Periférico), NO solo cercanía — así Cerro Gordo ya no
                    // inventa correspondencia con un Mexicable a 500 m.
                    boolean nombreOk = mismoSistema
                            ? (Planificador.norm(Planificador.sinMxb(e.nombre)).equals(pn) || nucleoCoincide(p.nombre, e.nombre))
                            : nucleoCoincide(p.nombre, e.nombre);
                    if (!nombreOk) continue;
                    double d = haversine(p.pos.latitude, p.pos.longitude,
                            e.posicion.latitude, e.posicion.longitude);
                    if (d <= CORRESP_VOZ_M) { s.add(Planificador.claveTerminal(l.numero)); break; }
                }
            }
        } catch (Exception ignore) {}
        // Correspondencias MANUALES (Planificador.CORRESP_MANUAL): transbordos reales que el A* SÍ usa
        // (ver Planificador.corrManual()) pero cuyo nombre no comparte ninguna palabra con el de la otra
        // estación (p. ej. "1ro de Mayo"↔"Las Américas") -- el bucle de arriba nunca los encuentra, así
        // que sin esto el aviso de llegada quedaba mudo sobre a qué línea conectas, aunque la ruta sí
        // transborde ahí. Línea EXACTA (no baseLinea()): preserva ramales (112 = "2A") distintos de su
        // ordinario (102 = "2"), ver Planificador.corrManualLineas() y RecorridoService.etiquetaLinea().
        s.addAll(Planificador.corrManualLineas(p.linea, Planificador.norm(p.nombre)));
        return s;
    }

    /** ¿La parada es terminal de su línea? (incluye terminales por servicio de L4/L7). */
    private boolean esTerminal(Planificador.Parada p) {
        // Indios Verdes es estación de PASO para Mexibús L4 (terminal solo nominal del exprés): al llegar
        // ahí no debe sonar el aviso de "estación terminal", solo la correspondencia/conexión.
        if ((p.linea == 104 || p.linea == 124) && Planificador.norm(p.nombre).contains("indios verdes"))
            return false;
        return Planificador.esTerminalDe(p.linea, p.nombre);
    }

    /** Consejo de seguridad aleatorio (o null, para que no salga siempre). */
    private String tipAleatorio(int linea) {
        if (Math.random() < 0.5) return null;
        java.util.List<Integer> tips = new java.util.ArrayList<>();
        tips.add(R.string.voz_tip_espacios);
        tips.add(R.string.voz_tip_objetos);
        tips.add(R.string.voz_tip_correr);
        if (linea >= 100 && linea < 200) tips.add(R.string.voz_tip_rosa);   // unidades rosas: exclusivas de Mexibús
        return getString(tips.get((int) (Math.random() * tips.size())));
    }

    /**
     * Elige la mejor voz femenina en español (México &gt; EE.UU. &gt; España) entre las que
     * ofrezca el motor TTS instalado. Es la aproximación más cercana a la voz "Ximena"
     * (Loquendo es software propietario y no puede empaquetarse en la app).
     */
    private void seleccionarVozFemenina() {
        try {
            java.util.Set<Voice> voces = tts.getVoices();
            if (voces == null || voces.isEmpty()) return;
            Voice mejor = null;
            int mejorPuntaje = -1;
            for (Voice v : voces) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage();
                if (!"es".equalsIgnoreCase(lang)) continue;
                int p = 0;
                String pais = v.getLocale().getCountry();
                if ("MX".equalsIgnoreCase(pais)) p += 40;
                else if ("US".equalsIgnoreCase(pais)) p += 25;
                else if ("ES".equalsIgnoreCase(pais)) p += 15;
                String n = v.getName() != null ? v.getName().toLowerCase(Locale.ROOT) : "";
                if (n.contains("fem") || n.contains("female") || n.contains("-f-")
                        || n.matches(".*-x-\\w*f\\w*")) p += 30;   // pistas de voz femenina
                if (!v.isNetworkConnectionRequired()) p += 5;      // prioriza voz local
                if (v.getQuality() >= Voice.QUALITY_HIGH) p += 5;
                if (p > mejorPuntaje) { mejorPuntaje = p; mejor = v; }
            }
            if (mejor != null) tts.setVoice(mejor);
        } catch (Throwable ignore) {}
    }

    private Bitmap pictograma(String icono) {
        // El pictograma se muestra a ~44dp; se decodifica reducido (helper compartido).
        int destPx = Math.round(48 * getResources().getDisplayMetrics().density);
        return Iconos.pictograma(this, icono, destPx);
    }

    /** Icono de estación para la notificación: pictograma (nuevos) o PUNTO del color (antiguos). */
    private Bitmap pico(Planificador.Parada p) {
        if (p == null) return null;
        Bitmap b = pictograma(p.icono);
        if (b != null) return b;
        int px = Math.round(48 * getResources().getDisplayMetrics().density);
        Bitmap dot = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(dot);
        android.graphics.Paint pa = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pa.setColor(0xFFFFFFFF); c.drawCircle(px / 2f, px / 2f, px * 0.30f, pa);
        pa.setColor(p.color);    c.drawCircle(px / 2f, px / 2f, px * 0.22f, pa);
        return dot;
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CANAL,
                    getString(R.string.canal_recorrido), NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent piAbrir() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ABRIR_RUTA, true);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent piDetener() {
        Intent i = new Intent(this, RecorridoService.class).setAction(ACCION_DETENER);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification construir(String proxima, String estado, Bitmap picProx,
                                  String anterior, Bitmap picAnt, String posterior, Bitmap picPost,
                                  String origenViaje, String destinoViaje, int colorLinea, int lineaProxima,
                                  int lineaAnterior, int lineaPosterior) {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_recorrido);
        // Nombres de estación (bitmap, no TextView de RemoteViews): Tipo Metro para Metrobús/
        // Mexicable; Mexibús imita su señalética real según línea y modo de iconografía (ver
        // Tipografia.fuenteEstacion()), igual en el nombre grande de arriba y en las 3 etiquetas
        // anterior/próxima/posterior bajo cada pictograma -- así el nombre siempre combina con
        // el icono que tiene encima. SIN negrita: Gotham Black ya es un peso muy grueso de por
        // sí, y pedirle además la negrita SINTÉTICA de Android (Typeface.create(tf, BOLD) sobre
        // una fuente que no trae una variante "bold" propia) hace que algunos fabricantes
        // (Xiaomi/MIUI en particular) descarten la tipografía personalizada y caigan de vuelta a
        // su propia fuente del sistema -- de ahí que se viera "MI" en vez de Gotham/Rounded Elegance.
        Bitmap nombreBmp = Tipografia.render(this, proxima, 20f, 0xFFC8103E, false, lineaProxima);
        if (nombreBmp != null) {
            rv.setImageViewBitmap(R.id.nr_estacion, nombreBmp);
            rv.setContentDescription(R.id.nr_estacion, proxima);
        }
        Bitmap nomProxBmp = Tipografia.render(this, proxima, 9f, 0xFFC8103E, false, lineaProxima);
        Bitmap nomAntBmp = Tipografia.render(this, anterior, 9f, 0xFF8A8A8A, false, lineaAnterior);
        Bitmap nomPostBmp = Tipografia.render(this, posterior, 9f, 0xFF8A8A8A, false, lineaPosterior);
        if (nomProxBmp != null) { rv.setImageViewBitmap(R.id.nr_nom_prox, nomProxBmp); rv.setContentDescription(R.id.nr_nom_prox, proxima); }
        if (nomAntBmp != null) { rv.setImageViewBitmap(R.id.nr_nom_ant, nomAntBmp); rv.setContentDescription(R.id.nr_nom_ant, anterior); }
        if (nomPostBmp != null) { rv.setImageViewBitmap(R.id.nr_nom_post, nomPostBmp); rv.setContentDescription(R.id.nr_nom_post, posterior); }
        rv.setTextViewText(R.id.nr_estado, estado);
        rv.setTextViewText(R.id.nr_origen, getString(R.string.notif_origen_fmt, origenViaje));
        rv.setTextViewText(R.id.nr_destino, getString(R.string.notif_destino_fmt, destinoViaje));
        if (picProx != null) rv.setImageViewBitmap(R.id.nr_ic_prox, picProx);
        if (picAnt != null) rv.setImageViewBitmap(R.id.nr_ic_ant, picAnt);
        if (picPost != null) rv.setImageViewBitmap(R.id.nr_ic_post, picPost);

        // Vista COLAPSADA / pantalla de bloqueo: contenido ESTÁNDAR (título + texto + barra de progreso
        // nativa). Así se ve siempre, aunque la pantalla esté bloqueada o el sistema no renderice el
        // layout propio. El RemoteViews bonito queda SOLO para la vista expandida (setCustomBigContentView).
        String titulo = getString(R.string.notif_proxima) + ": " + proxima;
        int color = colorLinea != 0 ? colorLinea : 0xFFC8103E;   // cambia con la línea actual del tramo
        List<Planificador.Parada> seq = paradas;
        int total = (seq != null && seq.size() > 1) ? seq.size() - 1 : 1;
        int avance = Math.max(0, Math.min(total, actualIdx));
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_route)
                .setContentTitle(titulo)
                .setContentText(estado)
                .setProgress(total, avance, false)   // barra de progreso estándar (visible bloqueada)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(rv)          // solo la vista EXPANDIDA usa el layout propio
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piAbrir())
                .addAction(R.drawable.ic_route, getString(R.string.recorrido_detener), piDetener())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setColorized(true)
                .setColor(color)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private boolean tienePermiso() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Corta el stream continuo de ubicación (batería). */
    private void detenerUbicacion() {
        if (!recibiendo) return;
        recibiendo = false;
        cadenciaIniciada = false;   // el próximo pedirUbicacion() debe arrancar de nuevo en modo rápido
        cadenciaRapida = true;
        if (loc != null) loc.removeLocationUpdates(locCb);
    }

    @Override public void onDestroy() {
        activo = false; actualIdx = -1; ultimaPos = null;
        corresPendienteIdx = -1;
        detenerUbicacion();
        handler.removeCallbacksAndMessages(null);   // cancela tick y el stopSelf diferido
        destruido = true;                            // invalida cualquier voz pendiente/en cola
        colaVoz.clear();
        soltarActual();
        soltarFocoAudio();   // por si se detiene el recorrido a media reproducción
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        // Quita la notificación al terminar/detener la ruta (finalizar ruta o llegada al destino).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
