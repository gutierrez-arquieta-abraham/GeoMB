# GeoMB — Memoria del proyecto

> Notas de contexto para retomar el desarrollo. Última actualización: 2026-09-09.

## Visión general
App Android (Java, package `com.memegrados.GeoMB`, Google Maps SDK) para transporte del Valle de México:
**Metrobús CDMX (L1–L7)**, **Mexibús (Edomex, L1–L4 + ramales)** y **Mexicable**. Incluye:
mapa de unidades en tiempo real, buscador, líneas/estaciones, **planificador de rutas (Dijkstra)**,
**recorrido con voz** (turn-by-turn), afectaciones del servicio y notificaciones push (FCM).

## Backend / servidor (EC2, FastAPI, `https://geomb.duckdns.org`)
- Endpoints (todos vivos, verificados 2026-09-09):
  - `/data/vehicles.json` — **posiciones GPS** de unidades. Campos: `id,label,plate,route_id,line,destino,origen,direction_id,lat,lon,bearing,speed,timestamp`. `speed` en **km/h**; `timestamp` epoch(s). ~283+ unidades; **incluye "fantasmas"** (timestamps de hasta ~69 días).
  - `/data/routes.json` — rutas.
  - `/data/afectaciones_mexibus.json` — `{"actualizado":epoch,"afectaciones":[{linea,estado,lugar,info,[circuito]}]}`. Lo escribe `mexibus_afectaciones.py`.
- **`mexibus_afectaciones.py`** (systemd `mexibus-afectaciones`, en EC2 y en `outputs/` + `Escritorio\GeoMB`): sondea **3 feeds RSS.app** (X/FB de Mexibús Informa / SITRAMYTEM / Mexicable), parsea afectaciones y empuja **FCM** (topic `afectaciones`, data-only) + escribe el JSON del panel. poll=60s. Dedup por post id+línea. `.env` (systemd NO ignora comentarios en línea → comentarios en su propia línea).
- Deploy: scp DESDE la PC (no desde EC2). IP real 78.14.99.249. Firebase service-account JSON secreto (chmod 600, solo EC2).
- Metrobús/CDMX **sí** publica GTFS-Realtime (VehiclePositions + **TripUpdates/arribos por estación** + alertas, ~30s, registro en portal datos abiertos) — vía posible para replicar las pantallas de estación (incluye unidades sin GPS), pendiente de integrar.

## Notificaciones de afectación (evitar duplicados)
- **EC2 push** → `MensajesService.notificarAfectacion` (topic `afectaciones`). Autoritativo cuando el EC2 vive.
- **Local Metrobús** → `ManifestacionesService` (WebView scraping incidentesmovilidad.cdmx) — solo cuando EC2 caído.
- **Local Mexibús** → `AfectMexibusFeed` (port a Java del parser de `mexibus_afectaciones.py`, lee los MISMOS 3 RSS) — solo cuando EC2 caído.
- Gate: `ManifestacionesService.ec2Activo()` (fresco = `afectaciones_mexibus.json` `actualizado` ≤ 240s).
  - **BUG resuelto (importante):** `ec2Activo()` descargaba en el **hilo principal** (notificar() corre en el UI thread por el callback del WebView) → `NetworkOnMainThreadException` → devolvía SIEMPRE false → el local notificaba SIEMPRE junto al push → **2 notificaciones** (para Metrobús los IDs difieren: local 43100+hash vs push 4510+hash). **Fix:** liveness en **segundo plano** (`refrescarEc2()` en un executor, cada ciclo) y `ec2Activo()` lee solo un cache; asume "vivo" hasta lectura confiable; caído tras epoch >240s o 3 fallos seguidos.
- IDs unificados push ↔ AfectMexibusFeed: `4510 + hash(linea|lugar)` (mismo → se reemplazan, no duplican). `MensajesService.etiquetaLineaNotif`: "Línea N"/"Mexibús L2"/"Mexicable L1". Ícono: `Tipografia.bitmapLineaLogo`.

## Servicios finos de Mexibús — `assets/servicios_mexibus.json` + `ServiciosMexibus.java`
- **Rosa y Mixto son PREFIJOS** (no patrones de parada); el patrón real es Ordinario o Express.
- Con lista de paradas propia (se **rutean** como variante): L1 **TR3/TR4** (express), L3 **Express 1/2/3/Express Rosa**. (TR1 = L1 ordinario = línea 101 completa.)
- **Ruteo por variante** en `Planificador.agregarVariantes()`: cada exprés real es una **subsecuencia de la línea 12X** (121/123). Se omite la exprés genérica de L1/L3 y se agregan las variantes como rutas por secuencia (geometría de la 12X). `codigoPorTramo`/`codigoUnico` nombran la variante en la voz.
- **L4 exprés (124):** ya trae solo las 13 paradas express (UMB↔La Raza) con manejo especial del couplet (La Raza "únicamente descenso", Indios Verdes); NO se toca (`estaciones_ref` en el JSON es solo referencia).
- **L2:** Ordinario/Express genérico; Mixto es prefijo. Falta patrón de paradas del express L2.

## Horarios — `assets/horarios.json` + `Horarios.java`
- Rutas de servicio (Metrobús L1–L7 + Mexibús + Mexicable) con primera/última salida por día (LJ/V/S/D/LV) y sentido. Usado para: ventana de servicio, validar circulación, y **dirección/terminal en la voz**.
- Ojo: varios origen/destino de horarios NO casan literal con los nombres de estación (alias): "IPN"→"Instituto Politécnico Nacional", "Dep. 18 de marzo"→"Dep. 18 Mzo", y en L4/L7 terminales de **otras ramas** del couplet (Pantitlán, Alameda Oriente, etc.). `RecorridoService.idxEnLinea` aplica alias (IPN, 18 de marzo).

## Recorrido con voz — `RecorridoService.java`
- Voz Mia (AWS Polly) cacheada en `getCacheDir()/voz/` (`Integer.toHexString(("Mia|"+texto).hashCode())+".mp3"`); endpoint `/api/tts?voz=Mia&texto=`; respaldo TTS Android. Jingle `tururu_mxb` (≥100) / `tururu_mb`; **usa la línea que VAS VIAJANDO** (`seq.get(best).linea`), no la de la próxima parada (evita tururu Metrobús en Indios Verdes yendo en Mexibús).
- **Avisos:** abordaje inicial "Aborda una unidad con destino a {terminal}, servicio {X}[, unidad rosa]. {N} estaciones"; cambios de tramo: conexión "Camina hasta la conexión con {estación} {línea}" + "Toma el autobús con dirección {terminal}"; transbordo/correspondencia "Baja y realiza tu {palabra}" + dirección; unidad (ordinario↔exprés) "Baja y toma el servicio {X} con dirección a {terminal}".
- **Dirección/terminal** (`direccionTerminal`): líneas lineales Metrobús (1,3,5,6) → `terminalHorario` que LISTA las terminales del sentido ("IPN o El Rosario", más cercana→lejana; si >3 rutas cubren la parada, solo la más cercana). Couplets Metrobús (2,4,7) → **terminal del planificador** (`RecorridoService.terminalesTramo`, poblado desde `Ruta.instrucciones` en `arrancarRecorrido`; alineado por conteo de transbordos). Mexibús/otros → `terminalSentido`. Salvaguarda: la dirección NUNCA es la estación donde vas.
- **Indios Verdes:** los andenes co-ubicados tienen nombres distintos por sistema ("Indios Verdes" vs "Indios Verdes (conexión…)"). La "próxima" **salta andenes co-ubicados** (mismo núcleo + <350 m, `coUbicada`) para no repetir "próxima Indios Verdes" ni pisar el aviso de conexión.
- **Puente de Fierro:** la llegada considera además el **acceso peatonal** (19.602869, −99.033683), usando el mínimo entre andén y acceso.
- Descarga de audios offline (`DescargaVoz`/`Locuciones`) — Fase 1 base + Fase 2 correspondencia/terminal; por línea / todas / borrar.

## Planificador — `Planificador.java` / `PlanificadorFragment.java`
- Grafo cacheado (rutas líneas + mixtas + variantes exprés) + Dijkstra con preferencia de servicio (svcPref) y cortes por afectación. `Ruta` = pasos + instrucciones + trazo + secuencia.
- **panel_resultado (descripción):** SOLO indicaciones en texto (una por tramo: "{línea/servicio} → {terminal} · N estaciones") + filas de transferencia. Se itera `pasos`/`instrucciones` (1:1). **Ya NO lista estaciones** (`mismoTrazo` une ordinario↔exprés; ramales sí son trazo distinto).
- **panel_estaciones (deslizador):** estaciones con **pictograma** (Iconos.pictograma, respeta nuevo/antiguo, respaldo a punto de color) + chips de instrucción por tramo ("Aborda/Toma · dirección {terminal}") + centrado/resaltado de la estación actual durante el recorrido.
- **panel_zoom_ruta:** acotado por arriba (constrainedHeight + bias) para no cubrir las tarjetas superiores.

## Unidades en tiempo real
- `RealtimeRepository` baja `vehicles.json` (JSON, no .proto) → `UnidadReal` (ahora con `velMs` m/s y `timestamp`).
- **Llegadas** (`Llegadas.java`): **por ubicación** — proyecta unidad y estación sobre la polilínea (`Linea.distanciaEn`), ETA = distancia / velocidad fija (`Config.LLEGADA_VEL_MS=5 m/s`). No hay feed de predicciones.
- **Animación** (`UnidadAnimador.java`, compartido mapa general + planificador): PEGA la unidad al **grafo** (`distanciaEn`→`puntoEn`, no dibuja GPS crudo) y avanza por **velocidad** entre updates (dead-reckoning), con corrección en cada update. Avanza al **60% de la velocidad** reportada (`FACTOR_VEL=0.6`) para no sobrepasar y evitar "regresones". Detenidas (speed≈0) no se mueven. Sin línea o a >150 m del trazo → respaldo en recta.
- **Filtro de fantasmas** (`RealtimeRepository.filtrarFantasmas`): descarta unidades cuyo `timestamp` está a >**240 s** de la unidad **más fresca del lote** (referencia relativa, inmune a desfase de reloj). Afecta mapa y llegadas. Sin timestamp → se conservan.

## Pendientes / ideas
- **L2 Express:** capturar su patrón de paradas para rutearlo como variante.
- **TripUpdates GTFS-rt** de Metrobús: integrarlo para arribos "tipo pantalla" (incluye unidades sin GPS) — requiere registro en portal datos abiertos + ingestión en el backend.
- Animador en **couplets** (L2/L6/L7 ida/vuelta en calles separadas): hoy usa la polilínea única `ruta`; podría afinarse con la sublínea por sentido.
- ETA de llegadas con **velocidad real por tramo** (ya se tiene `speed`) en vez de fija.
- Chips de la carta: mostrar la lista "A o B" (hoy muestran la terminal única del planificador; la voz sí da la lista en líneas lineales).
- Feed vehicles.json: ~57 unidades sin `line`; y el filtro de fantasmas es cliente — idealmente también limpiar en el server.

## Convenciones / gotchas
- No manejar tokens en claro (X Bearer, Firebase JSON): solo env vars / archivos seguros.
- `baseLinea`: 12X→10X (exprés→ordinaria); ramales (111..) distintos. `Servicios.base` mapea 111→101 (NO usar para distinguir ramal en transbordos).
- minSdk soporta `computeIfAbsent`/`removeIf`/`List.sort` (API 24+).
- Cambios de app requieren recompilar; cambios del módulo Python requieren re-subir a `/opt/geomb-afect/` + `systemctl restart mexibus-afectaciones`.
