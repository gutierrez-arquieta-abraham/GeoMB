# GeoMB — Memoria del proyecto

App Android (Java) del **Metrobús CDMX**: mapa en tiempo real de unidades, líneas/estaciones,
llegadas, planificador de rutas con recorrido guiado por voz, reportes, y capa del **Mexibús**.
Paquete: `com.memegrados.GeoMB`. Este archivo resume decisiones y estado para no re-derivar contexto.

---

## Convenciones clave

- **Líneas Metrobús**: números 1–7. Datos en `assets/lineas.json`, `assets/estaciones.json`
  (estaciones oficiales con campo `icono` = drawable `ic_est_<linea>_<n>`), `assets/segmentos.json`
  (trazo por tramos), `assets/sublineas.json` (couplets L7).
- **Rutas mixtas / servicios** (L4, L7, A31, C2, C3, C21, H72): en `RutasMixtas.java` como
  secuencias exactas (no vienen en el GTFS).
- **Planificador** (`Planificador.java`): grafo cacheado (rutas dirigidas + nodos + aristas),
  Dijkstra. Transbordos = nodos co-ubicados de distinta ruta. Trazo de cada paso vía
  `geomSentido`→`subRuta` (usa `Linea.ruta`); si la geometría no cubre, cae a **líneas rectas**
  entre estaciones (respaldo seguro).
- **Recorrido guiado** (`RecorridoService.java`): foreground service, voz TTS/Mia (AWS Polly),
  notificación con próxima estación. Revisa ubicación cada **1 s** (`INTERVALO_MS`).
- **Preferencias/ajustes**: `Modos.java` (SharedPreferences `geomb_modos`). Los switches viven
  en **Acerca de** (`AcercaFragment` + `fragment_acerca.xml`).

---

## Cambios de esta etapa (hechos)

### Recorrido / planificador
- Voz de **próxima estación con correspondencias** (transbordos). Para L4 el nombre indica su
  **ruta**: "Ruta Norte" (Hidalgo…Archivo General), "Ruta Sur" (pasando México Tenochtitlan/San
  Lázaro), y "L4" a secas en tramo compartido (Buenavista, San Lázaro). Lógica en
  `RutasMixtas.rutaL4()` + `RecorridoService.transbordoTexto/etiquetaLinea`.
- Voz de **afectaciones** (Mia).
- **Puntero de ubicación** = icono de norte, **rota** alineado al trazo y **pegado a la ruta**
  (snap al punto más cercano; si estás lejos, al punto aproximado dentro del trazo).
- **Botón brújula** propio (`ic_compass.xml`) reemplaza al icono de norte en el botón (mapa y
  planificador) para no confundirlo con el puntero.
- **Seguimiento en segundo plano** aunque cierren la app: `stopWithTask="false"` + `onTaskRemoved`.
- **Trazo de progreso** (gris estilo Maps) densificado a ~12 m (`PASO_TRAZO_M`) para avanzar suave
  entre estaciones (antes saltaba). Unidades del planificador filtradas a 50 m del trazo,
  con animación de movimiento (`moverMarcador`) y tipografía Metro (InfoWindow).

### Otros
- Filtrar unidades por área visible del mapa.
- Obstrucción de carril por un solo sentido; L1 no muestra 2 plataformas en el listado; borde
  negro en badges de línea; botón norte tipo brújula + localización.

---

## MEXIBÚS (feature grande)

Sistema del Estado de México agregado como **red aparte** (no choca con Metrobús).
Datos en `assets/mexibus.json`. Carga en `GtfsRepository.getMexibus()` /
`getRuteables()` (= Metrobús + Mexibús, **solo si el ajuste está activo**).

### Toggle global "Mostrar Mexibús"
- Ajuste en **Acerca de** (`sw_mexibus`), persistido en `Modos.mostrarMexibus()`.
- **Activo** → mapa muestra la capa Mexibús + planificador considera sus estaciones.
- **Apagado** (por defecto) → ni mapa ni planificador lo consideran.
- Mapa: `MapFragment.dibujarMexibus()` crea la capa; `aplicarMexibus()` (en `onResume`) aplica
  visibilidad. Se quitó el botón `btn_mexibus` del mapa.
- Planificador: `getRuteables` ramifica según el ajuste; el **caché del grafo** se invalida con
  la bandera `grafoMexibus`.

### Líneas (7) — numeración interna 101+ para no chocar con Metrobús 1–7
| # | Nombre | Color | Est | Trazo real (sigue calles) |
|---|--------|-------|-----|---------------------------|
| 101 | Mexibús L1 | #009D57 | 24 | ✅ (Ciudad Azteca→Ojo de Agua) |
| 102 | Mexibús L2 | #E4022D | 43 | ✅ |
| 103 | Mexibús L3 | #0BA9CC | 30 | ◑ híbrida (geometría donde cubre + rectas en el tramo divergente) |
| 104 | Mexibús L4 | #F8971B | 29 | ✅ (Indios Verdes→U. Mexiquense) |
| 111 | Mexibús L1A | #097138 | 12 | ✅ ramal Ojo de Agua→Terminal Pasajeros/AIFA |
| 112 | Mexibús L2A | #A52714 | 10 | ✅ ramal Las Américas→Libertadores |
| 113 | Mexibús L3A | #0097A7 | 16 | ✅ ramal Acuitlapilco→Chicoloapan |

Datos originales: Google My Maps oficiales (sitramytem.edomex.gob.mx). Solo **servicio ordinario**.
La geometría (`ruta`) se extrae del LineString del KML cuando cubre las estaciones; si no, rectas.

### Visualización de nombres (MXB oculto + desambiguación)
- El prefijo `MXB ` se **guarda en los datos** (para unicidad/matching) pero **NO se muestra**:
  `Planificador.sinMxb()` lo quita. `Planificador.nombreMostrar(ctx, nombre, linea)` además añade
  el nº de línea cuando el nombre existe en varias líneas → "1o de Mayo 2" (el de Mexibús L2).
- Aplicado en: slider, instrucciones, notificación/estado del recorrido, marcadores de mapa.
  La **voz** usa nombre limpio sin número (`nom()`), para no sonar raro.
- Campos origen/destino: muestran el nombre limpio pero **recuerdan el canónico** (`origenCanon`/
  `destinoCanon` + `resolver()`), así el ruteo va a la estación correcta aunque no se vea "MXB".
- Terminología: en la UI/voz se dice **"correspondencia"** en vez de "transbordo".
- Avisos Mexibús: `CERCA_MXB_M=150`, `PASO_MXB_M=100` (Metrobús sigue 60/20).

### Nombres de estaciones (interno)
- **Prefijo `MXB `** en todas (ej. "MXB Hidalgo") para que NO generen correspondencias falsas
  con estaciones homónimas del Metrobús (Hidalgo, Buenavista, Insurgentes, etc.).
- **Estaciones con conexión real a Metrobús** llevan la conexión en el nombre (sin prefijo MXB):
  - "Indios Verdes (conexión Metrobús L1 y L7)" — L4
  - "Pantitlán (conexión Metrobús L4)" — L3
  - "Calle 6 (conexión Metrobús L4)" — L3
  - (Estas hoy solo etiquetan; NO fuerzan ruteo Metrobús↔Mexibús. Pendiente si se quiere.)
- Renombrada: "San Mateo" → "MXB De la Cruz San Mateo" (L2).

### Transbordos / correspondencias (por CERCANÍA ≤ 800 m)
`RADIO_CORRESP = 800 m`: dos andenes de distinta ruta a ≤800 m se ligan (se caminan). El costo del
transbordo suma **caminata** (`caminata(d)=d/1.4 s`) + overhead `SEG_TRANSBORDO` + `esperaExtra`.
Ya NO se usa igualdad de nombre (el prefijo MXB + distancias evitan homónimos lejanos). Los reales
a >800 m van por `CORRESP_MANUAL` (Las Américas L1↔L2 y L1↔L2A, ~1 km).
Cruces Metrobús↔Mexibús que salen solos (reales): Indios Verdes (L4↔MB L1/L7), Pantitlán y Calle 6
(L3↔MB L4), y **Río de los Remedios (L2A↔MB L5, 105 m)**.

### Espera por frecuencia (`esperaExtra`, en Planificador)
Al abordar (en origen y en cada transbordo) se suma espera por baja frecuencia: Metrobús 0,
troncales Mexibús (100–110) +120 s, ramales (111+) +420 s → L2A tarda más que L4 en "mandar unidad".

### Nota L3 (trazo híbrido)
La fuente tiene **desfase**: el LineString de L3 NO pasa cerca de 9 estaciones seguidas
(Nezahualcóyotl → … → El Castillito), hasta ~1.8 km. Solución aplicada: **trazo híbrido** — por
cada par de estaciones se usa la geometría del KML si pasa cerca de ambas (11/29 tramos), y línea
recta en el tramo divergente. Así **todas las estaciones caen sobre la línea** (0 m) y sigue calles
donde se puede. Para dejarla 100% real se necesitan coords corregidas o un KML sin ese desfase.

### Avisos de voz / proximidad (RecorridoService)
- **Radio por sistema (Haversine)**: Metrobús `CERCA_M=60` / `PASO_M=20`; **Mexibús** (línea ≥100)
  `CERCA_MXB_M=500` / `PASO_MXB_M=80`, porque sus estaciones son mucho más extensas (L1/L4 tienen
  1 andén por sentido unidos por un paso; ida y vuelta gratis). Distancias con `haversine()`.
- **Voz Mia con timeout ~2 s**: si el mp3 no descarga en `VOZ_TIMEOUT_MS`, se habla ya con el TTS de
  Google (la descarga sigue y queda cacheada). `descargarVoz` connect=3 s / read=8 s.
- **Tururu al 70%** (`TURURU_VOL=0.7`) para no reventar oídos.

---

## Servicio EXPRÉS (121–124) e íconos
- Líneas exprés: 121 (L1), 122 (L2), 123 (L3), 124 (L4), color morado, punteadas en el mapa,
  paradas = las marcadas "Servicio Exprés" en los KML. `esperaExtra` exprés = 240 s.
  L2 exprés termina en **"Retorno Oriente"** (= Lechería exprés, 2 plataformas).
- `baseLinea(n)=100+(n%10)` agrupa ordinario/ramal/exprés de una misma línea para NO desambiguar
  entre sí en `nombreMostrar`. `etiquetaLineaCorta` da "1".."4", "1A/2A/3A".
- **Ruteo por KILOMETRAJE**: la arista intra-línea usa `costoTramo = dist/6 m/s + 20 s` (no nº de
  paradas). Correspondencias por cercanía ≤800 m suman caminata (`dist/1.4`).
- **Íconos reales**: 172 PNG `mexibus_XX[a]_N` (de Downloads\MB\Mexibus, redimensionados a 96px)
  en `res/drawable`. `mexibus.json` tiene `icono` por estación (índice hacia adelante: _1 = 1ª
  estación). 215/223 mapeadas; faltan empalmes de ramal/terminales (L1A/L2A/L3/L2exprés) por
  verificar el desfase (±1).
- Fix L2: "coyuya" añadido a `L2_IDA` (es de una vía hacia Tacubaya) → Coyuya→Tepalcates hace rodeo.

## MEXICABLE (MXC) — IMPLEMENTADO
Líneas 201 (L1 rosa #C2185B: Santa Clara→La Cañada, 7 est) y 202 (L2 verde #62AF44:
Indios Verdes→Hank González II, 7 est). Datos + trazos reales del KML
mid=1HntbJeOrqgqcod0CuzqPZ-hhjxwMSCF0. Prefijo MXC (oculto). Trazo SÓLIDO en el mapa. Bajo el
mismo toggle "Mostrar Mexibús y Mexicable". `esperaExtra(>=200)=60s`. `baseLinea(>=200)=200+(n%10)`.
Conexiones por cercanía (reales): Santa Clara↔Mexibús L4, Hank González (L1↔L2), Indios Verdes↔
Metrobús L1/L7 + Mexibús L4, Periférico↔Mexibús L4. L3 del Mexicable está PENDIENTE (en obra).
Faltan pictogramas del Mexicable (usa anillo de color). Audio del recorrido = jingle recortado
(`res/raw/tururu.mp3`, 2.7 s sin voz).

## PENDIENTE — datos Mexicable originales (referencia)
Sistema de cablebús. Prefijo interno **MXC** (como MXB), oculto en display. 2 líneas:
- **Mexicable L1** (rosa): Santa Clara, Hank González, Fátima, Tablas del Pozo, Los Bordos,
  Deportivo, La Cañada. Conexiones: Mexibús L4 (en Santa Clara), Mexicable L2.
- **Mexicable L2** (verde): Indios Verdes, Tanque de agua, Periférico, San Isidro, Dr. Jorge
  Jiménez Cantú, La Mesa, Hank González II. Conexiones: Mexibús L4, Mexicable L1, Metro L3,
  Metrobús L1 y 7, Cablebús L1.
Reglas de nombre pedidas: "Periférico 2" (Mexicable L2) y el Mexibús sin nº; igual Santa Clara.
Indios Verdes debe decir "correspondencia con Mexicable L2 y conexión con Metrobús L1 y L7".
La Raza / Indios Verdes: marcar conexión con Metrobús. Mapa oficial mid=1HntbJeOrqgqcod0CuzqPZ-hhjxwMSCF0.

## OTROS PENDIENTES
1. **Pictogramas por estación**: existen en Drive (carpeta por línea, PNG ~1 MB c/u, ej.
   "1 Ciudad Azteca MxBL1.png"). NO se pueden bajar por el conector (cada base64 ~400k tokens ×
   ~150 = inviable). **Solución: subir un ZIP al chat** → recortar/redimensionar a ~96–128 px,
   bundlear como drawables y llenar el campo `icono` en `mexibus.json`. El código ya está listo:
   si una estación trae `icono`, `MapFragment.dibujarMexibus` usa `iconoEstacion()`; si no, el
   anillo de color (`iconoEstacionMexibus`).
2. **Servicio exprés** (1A/2A/3A exprés): se derivan de las descripciones ("Servicio Exprés") de
   cada estación en los KML. Pendiente decidir cómo modelarlo.
3. **Trazo real de L3**: requiere datos corregidos.
4. Ramales A ya están como **líneas** (111/112/113). Nota: el usuario aclaró que "1A/2A/3A son
   ramales", no exprés.

---

## Archivos tocados (referencia rápida)
- `assets/mexibus.json` — datos de las 7 líneas Mexibús.
- `GtfsRepository.java` — `getMexibus`, `getRuteables`, `cargarArchivo`, `icono` opcional.
- `Planificador.java` — grafo con Mexibús, `CORRESP_MANUAL`, `CORRESP_NOMBRE_MAX`, `grafoMexibus`.
- `RutasMixtas.java` — `rutaL4()`.
- `RecorridoService.java` — voz correspondencias, `INTERVALO_MS=1000`, `ultimaPos`, `onTaskRemoved`.
- `PlanificadorFragment.java` — puntero norte rotatorio, progreso denso, unidades a 50 m, animación.
- `MapFragment.java` — capa Mexibús + `aplicarMexibus()` (sin botón; controlado por ajuste).
- `Modos.java` — `mostrarMexibus` / `setMostrarMexibus`.
- `AcercaFragment.java` + `fragment_acerca.xml` — switch "Mostrar Mexibús".
- Drawables: `ic_compass.xml`, `ic_mexibus.xml` (este último ya sin uso tras quitar el botón).
- Strings: `mexibus_switch`, `mexibus_switch_desc`, `voz_transbordo`, `voz_linea_l4`, etc.
