# GeoMB — Memoria del proyecto (consolidada)

> Contexto para retomar sin re-derivar. Consolida `MEMORIA_PROYECTO.md` (fundacional) + trabajo reciente.
> Última actualización: 2026-09-09. Cuando algo se marcó "pendiente" antes y ya se hizo, aquí manda lo reciente.

## Visión general
App Android (Java, package `com.memegrados.GeoMB`, Google Maps SDK). Cubre **Metrobús CDMX (L1–L7)**,
**Mexibús (Edomex, L1–L4 + ramales 1A/2A/3A)** y **Mexicable (L1/L2)**. Funciones: mapa de unidades en
tiempo real, buscador, líneas/estaciones, **planificador (Dijkstra)**, **recorrido con voz** (turn-by-turn),
afectaciones del servicio, reportes y notificaciones push (FCM).

## Convenciones / gotchas clave
- Ajustes en **Acerca de** (`AcercaFragment` + `fragment_acerca.xml`); persisten en `Modos` (SharedPreferences).
- **Numeración interna:** Metrobús 1–7; Mexibús 10X (ordinario), 11X (ramales 1A/2A/3A), 12X (exprés);
  Mexicable 20X. `baseLinea(n)`: ≥200→200+n%10; ≥100→100+n%10 (une ordinario/exprés/ramal de una troncal);
  else n. **`Servicios.base` mapea 111→101** (NO usar para distinguir ramales en transbordos; usar claveTerminal/mismoTrazo).
- **Prefijo `MXB `/`MXC `** se guarda en los datos (unicidad/matching) pero NO se muestra (`Planificador.sinMxb`);
  `Planificador.nombreMostrar(ctx,nombre,linea)` agrega el nº de línea si el nombre existe en varias ("1o de Mayo 2").
  La **voz** usa nombre limpio sin número (`nom()`).
- Terminología por sistemas: **transbordo** Metrobús↔Metrobús; **correspondencia** Mexibús/Mexicable entre sí;
  **conexión** entre Metrobús y Edomex (`palabraTransferencia`).
- minSdk ≥24 (usa `computeIfAbsent`/`removeIf`/`List.sort`). Cambios de app requieren recompilar.
- Seguridad: nunca tokens en claro (X Bearer, Firebase JSON): solo env vars / archivos seguros (chmod 600, solo EC2).

## Datos / assets
- `lineas.json` (Metrobús), `estaciones.json` (campo `icono`=drawable `ic_est_<l>_<n>`), `segmentos.json` (trazo por
  tramos), `sublineas.json` (couplets L7), `mexibus.json` (7 líneas Mexibús + exprés 12X + Mexicable 20X), `horarios.json`,
  `servicios_mexibus.json`.
- `RutasMixtas.java`: secuencias exactas de servicios/couplets (L4, L7, A31, C2, C3, C21, H72; `rutaL4()` da Ruta Norte/Sur).

## Mexibús (capa aparte, bajo toggle "Mostrar Mexibús")
- `GtfsRepository.getMexibus()` / `getRuteables()` (= Metrobús + Mexibús **solo si el ajuste está activo**);
  el caché del grafo se invalida con `grafoMexibus`. Mapa: `MapFragment.dibujarMexibus()`/`aplicarMexibus()` (onResume).
- Líneas: 101 L1 #009D57 (Cd. Azteca↔Ojo de Agua), 102 L2 #E4022D, 103 L3 #0BA9CC (trazo **híbrido**, ver abajo),
  104 L4 #F8971B (Indios Verdes↔U. Mexiquense/UMB), ramales 111 L1A (Ojo de Agua↔AIFA/Terminal Pasajeros),
  112 L2A (Las Américas↔Libertadores, circuito), 113 L3A (Acuitlapilco↔CEDA Chicoloapan, circuito).
- **Correspondencias por cercanía** ≤ `RADIO_CORRESP=800 m` (ya no por igualdad de nombre; el prefijo MXB evita homónimos).
  Reales >800 m en `CORRESP_MANUAL` (Las Américas L1↔L2/L2A ~1 km). Cruces Metrobús↔Mexibús: Indios Verdes (L4↔MB L1/L7),
  Pantitlán y Calle 6 (L3↔MB L4), Río de los Remedios (L2A↔MB L5, 105 m). Nombres con "(conexión Metrobús Lx)" en los datos.
- **Espera por frecuencia** (`esperaExtra`): Metrobús 0; troncales Mexibús +120 s; ramales +420 s; exprés +240 s; Mexicable +60 s.
- **L3 híbrida:** el KML de L3 tiene desfase (~9 estaciones seguidas lejos del LineString). Se usa geometría del KML donde
  cubre ambas estaciones del tramo y **recta** en el tramo divergente (todas las estaciones caen sobre la línea).

## Mexicable (MXC)
- 201 L1 rosa #C2185B (Santa Clara↔La Cañada, 7 est), 202 L2 verde #62AF44 (Indios Verdes↔Hank González II, 7 est).
  Trazo sólido. Conexiones por cercanía (Santa Clara↔Mexibús L4, Hank González L1↔L2, Indios Verdes↔MB L1/L7 + Mexibús L4,
  Periférico↔Mexibús L4). L3 del Mexicable pendiente (en obra). Jingle recortado `res/raw/tururu.mp3`.

## Servicios finos de Mexibús + exprés — `assets/servicios_mexibus.json` + `ServiciosMexibus.java`
- **Rosa y Mixto son PREFIJOS** (no patrones de parada); el patrón real es Ordinario o Express.
- Exprés = líneas 12X (121 L1, 122 L2, 123 L3, 124 L4), moradas/punteadas en el mapa; paradas = las marcadas "Servicio Exprés".
- **Servicios con lista de paradas propia (se RUTEAN como variante):** L1 **TR3/TR4**, L3 **Express 1/2/3/Express Rosa**.
  (TR1 = L1 ordinario = línea 101 completa.) L4 exprés (124) ya trae solo las 13 paradas express (UMB↔La Raza) con manejo
  especial del couplet (La Raza "únicamente descenso", Indios Verdes) → NO se toca. L2 exprés: patrón de paradas **pendiente**.
- **Ruteo por variante** (`Planificador.agregarVariantes()`): cada exprés real es una **subsecuencia de la línea 12X**; se
  omite la exprés genérica de L1/L3 y se agregan las variantes como rutas por secuencia (geometría de la 12X). La voz nombra
  la variante (`ServiciosMexibus.codigoPorTramo`/`codigoUnico`).
- **Ruteo por KILOMETRAJE:** arista intra-línea `dist/6 m/s + 20 s` (no nº de paradas); correspondencias ≤800 m suman
  caminata (`dist/1.4 s`).

## Planificador — `Planificador.java` / `PlanificadorFragment.java`
- Grafo cacheado (líneas dirigidas + mixtas + variantes exprés) + Dijkstra con preferencia de servicio (svcPref) y cortes por
  afectación. `Ruta` = pasos + instrucciones + trazo + secuencia. Trazo por paso vía `geomSentido`→`subRuta`; respaldo a rectas.
- **panel_resultado:** SOLO indicaciones en texto (una por tramo: "{línea/servicio} → {terminal} · N estaciones") + filas de
  transferencia. Se itera `pasos`/`instrucciones` (1:1; `mismoTrazo` une ordinario↔exprés, ramales sí son trazo distinto).
  **Ya NO lista estaciones.**
- **panel_estaciones (deslizador):** estaciones con **pictograma** (`Iconos.pictograma`, respeta nuevo/antiguo, respaldo a punto
  de color) + chips de instrucción por tramo ("Aborda/Toma · dirección {terminal}") + centrado/resaltado de la estación actual.
- **panel_zoom_ruta** acotado por arriba para no cubrir las tarjetas superiores.

## Recorrido con voz — `RecorridoService.java`
- Foreground service; ubicación continua (~1 s / mín 0.5 s). Sigue en segundo plano al cerrar (`onTaskRemoved`). Puntero pegado
  al trazo (snap) + brújula propia (`ic_compass`).
- Voz **Mia** (AWS Polly) cacheada `getCacheDir()/voz/` (`Integer.toHexString(("Mia|"+texto).hashCode())+".mp3"`), endpoint
  `/api/tts?voz=Mia&texto=`, timeout ~4 s → respaldo TTS Android (connect 3 s/read 8 s). **Tururu 70%**; jingle `tururu_mxb`(≥100)
  /`tururu_mb`, elegido por la **línea que VAS VIAJANDO** (`seq.get(best).linea`), no la de la próxima parada.
- **Radios (Haversine), valores actuales:** `CERCA_M=50`/`PASO_M=100` (Metrobús) y `CERCA_MXB_M=50`/`PASO_MXB_M=100` (Mexibús),
  `COBERTURA_EXTRA_M=50`, `CAMBIO_LINEA_M=15`, `FIN_M=30`; Indios Verdes usa ZONA (corredor de andén `ZONA_CERCA_M=70`) y andenes
  largos `ANDEN_LARGO_M=55`. (Los valores viejos 60/20 y 500/80 quedaron obsoletos.)
- **Avisos:** abordaje inicial "Aborda una unidad con destino a {terminal}, servicio {X}[, unidad rosa]. {N} estaciones";
  conexión "Camina hasta la conexión con {estación} {línea}" + "Toma el autobús con dirección {terminal}"; transbordo/
  correspondencia "Baja y realiza tu {palabra}" + dirección; unidad (ordinario↔exprés) "Baja y toma el servicio {X} con dirección a {terminal}".
- **Dirección/terminal** (`direccionTerminal`): líneas lineales Metrobús (1,3,5,6) → `terminalHorario` que LISTA las terminales del
  sentido ("IPN o El Rosario", más cercana→lejana; si >3 rutas cubren la parada, solo la más cercana). Couplets Metrobús (2,4,7)
  → terminal del planificador (`terminalesTramo`, poblado desde `Ruta.instrucciones` en `arrancarRecorrido`; alineado por conteo de
  transbordos). Mexibús/otros → `terminalSentido`. Salvaguarda: la dirección NUNCA es la estación donde vas. Alias de nombres:
  IPN→"Instituto Politécnico Nacional", "Dep. 18 de marzo"→"Dep. 18 Mzo".
- **Indios Verdes:** andenes co-ubicados con nombres distintos por sistema. La "próxima" **salta andenes co-ubicados** (mismo
  núcleo + <350 m, `coUbicada`) para no repetir "próxima Indios Verdes" ni pisar el aviso de conexión.
- **Puente de Fierro:** la llegada considera además el **acceso peatonal** (19.602869, −99.033683), usando el mínimo entre andén y acceso.
- L4 nombra su **ruta** en la voz (Norte/Sur/L4 en tramo compartido). Descarga de audios offline (`DescargaVoz`/`Locuciones`): base + correspondencia/terminal; por línea/todas/borrar.

## Afectaciones + notificaciones (evitar duplicados)
- **EC2 push** → `MensajesService.notificarAfectacion` (topic `afectaciones`), autoritativo cuando el EC2 vive.
- **Local Metrobús** → `ManifestacionesService` (WebView scraping incidentesmovilidad.cdmx). **Local Mexibús** → `AfectMexibusFeed`
  (port a Java del parser de `mexibus_afectaciones.py`, lee los MISMOS 3 RSS). Ambos solo cuando EC2 caído.
- **BUG resuelto:** `ec2Activo()` descargaba en el **hilo principal** (notificar() corre en el UI thread por el callback del
  WebView) → `NetworkOnMainThreadException` → SIEMPRE false → el local notificaba SIEMPRE junto al push → **2 notificaciones**.
  **Fix:** liveness en **segundo plano** (`refrescarEc2()` en executor, cada ciclo); `ec2Activo()` lee solo cache; asume "vivo" hasta
  lectura confiable; caído tras epoch >240 s o 3 fallos seguidos.
- IDs unificados push ↔ AfectMexibusFeed: `4510 + hash(linea|lugar)`. Etiqueta `etiquetaLineaNotif`; ícono `Tipografia.bitmapLineaLogo`.
  Panel de estado alimentado por `AfectacionesMexibus` (JSON EC2) / local; bloqueo de ruteo por sin servicio / circuito / paso de largo.

## Backend / servidor (EC2, FastAPI, `https://geomb.duckdns.org`)
- Endpoints vivos: `/data/vehicles.json` (posiciones), `/data/routes.json`, `/data/afectaciones_mexibus.json`.
- `mexibus_afectaciones.py` (systemd `mexibus-afectaciones`): sondea 3 feeds RSS.app → FCM (data-only, topic `afectaciones`) +
  escribe el JSON del panel. poll=60 s; dedup por post id+línea. `.env`: comentarios en su propia línea (systemd no los ignora).
  Deploy scp DESDE la PC (IP 78.14.99.249). Firebase service-account solo en EC2.
- Metrobús/CDMX publica GTFS-Realtime (VehiclePositions + **TripUpdates/arribos por estación** + alertas, ~30 s, registro en portal
  datos abiertos) → vía posible para arribos "tipo pantalla" (incluye unidades sin GPS). Pendiente de integrar.

## Unidades en tiempo real
- `RealtimeRepository` baja `vehicles.json` (JSON, no .proto) → `UnidadReal` (con `velMs` m/s y `timestamp`). `speed` del feed en **km/h**.
- **Llegadas** (`Llegadas.java`): **por ubicación** — proyecta unidad y estación sobre la polilínea (`Linea.distanciaEn`), ETA =
  distancia / velocidad fija (`Config.LLEGADA_VEL_MS=5 m/s`). No hay feed de predicciones.
- **Animación** (`UnidadAnimador.java`, compartido mapa general + planificador): PEGA la unidad al **grafo** (`distanciaEn`→`puntoEn`,
  no dibuja GPS crudo) y avanza por **velocidad** entre updates, con corrección en cada update. Avanza al **60%** de la velocidad
  (`FACTOR_VEL=0.6`) para no sobrepasar y evitar "regresones". Detenidas (speed≈0) no se mueven. Sin línea o a >150 m del trazo → recta.
- **Filtro de fantasmas** (`RealtimeRepository.filtrarFantasmas`): descarta unidades cuyo `timestamp` está a >**240 s** de la unidad
  MÁS FRESCA del lote (referencia relativa, inmune a desfase de reloj). Sin timestamp → se conservan. Afecta mapa y llegadas.

## Pendientes / ideas
- **L2 Express:** capturar patrón de paradas para rutearlo como variante.
- **TripUpdates GTFS-rt** de Metrobús para arribos "tipo pantalla" (unidades sin GPS) — registro portal + ingestión en backend.
- Animador en **couplets** (L2/L6/L7 ida/vuelta en calles separadas): hoy usa polilínea única `ruta`; afinar con sublínea por sentido.
- ETA de llegadas con **velocidad real** (ya se tiene `speed`) en vez de fija.
- Chips de la carta: mostrar la lista "A o B" (hoy muestran la terminal única; la voz sí da la lista en líneas lineales).
- Feed vehicles.json: ~57 unidades sin `line`; el filtro de fantasmas es cliente — idealmente también limpiar en el server.
- Pictogramas: existen por estación en Drive (PNG ~1 MB); pendiente bundlear los que falten y llenar `icono` en `mexibus.json`
  (el código ya usa el pictograma si está, y respaldo a anillo/punto de color). Mexicable sin pictogramas aún.
- Correspondencias Metrobús↔Mexibús declaradas en el nombre hoy solo etiquetan; forzar ruteo si se desea.

## Archivos clave (referencia)
- Datos: `assets/mexibus.json`, `assets/horarios.json`, `assets/servicios_mexibus.json`, `lineas/estaciones/segmentos/sublineas.json`.
- Núcleo: `GtfsRepository`, `Planificador`, `RutasMixtas`, `Servicios`, `ServiciosMexibus`, `Horarios`, `Linea` (`distanciaEn`/`puntoEn`).
- Tiempo real: `RealtimeRepository`, `UnidadReal`, `UnidadAnimador`, `Llegadas`, `MapFragment`, `PlanificadorFragment`.
- Recorrido/voz: `RecorridoService`, `DescargaVoz`, `Locuciones`, `Tipografia`, `Iconos`.
- Afectaciones: `MensajesService`, `ManifestacionesService`, `AfectMexibusFeed`, `AfectacionesMexibus`, `Manifestaciones`.
- Backend: `mexibus_afectaciones.py` (+ `.env.example`, `requirements.txt`, `mexibus-afectaciones.service`).
