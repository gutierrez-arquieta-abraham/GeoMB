# CLAUDE.md — GeoMB

App Android de transporte CDMX/Edomex: **Metrobús L1–L7, Mexibús (L1–L4 + ramales 1A/2A/3A), Mexicable (L1/L2)**.
Java, package `com.memegrados.GeoMB`, Google Maps SDK, minSdk 24. Gradle KTS.

> **Contexto completo del proyecto: leer [`MEMORIA.md`](./MEMORIA.md).** Es la memoria consolidada (arquitectura, convenciones,
> gotchas, datos/assets, planificador, recorrido con voz, afectaciones, tiempo real y backend). Este archivo es solo el mapa rápido.

## Estructura
- `app/src/main/java/com/memegrados/GeoMB/` — código de la app.
- `app/src/main/assets/` — datos (`lineas/estaciones/segmentos/sublineas.json`, `mexibus.json`, `horarios.json`, `servicios_mexibus.json`).
- `app/src/main/res/` — layouts, strings (es + values-en), drawables (pictogramas `ic_est_<l>_<n>`), `raw/` (jingles).
- `MEMORIA.md` — memoria del proyecto. `docs/` — registros de sesión.

## Núcleos (ver MEMORIA.md para detalle)
- **Datos/ruteo:** `GtfsRepository`, `Planificador` (Dijkstra + variantes exprés + circuitos), `RutasMixtas`, `Servicios`, `ServiciosMexibus`, `Linea` (`distanciaEn`/`puntoEn`).
- **Tiempo real:** `RealtimeRepository`, `UnidadReal`, `UnidadAnimador` (snap al grafo, 60% vel), `Llegadas`, `MapFragment`, `PlanificadorFragment`.
- **Recorrido/voz:** `RecorridoService` (foreground, voz Mia/Polly + respaldo Android), `DescargaVoz`, `Locuciones`, `Iconos`, `Tipografia`.
- **Afectaciones:** `MensajesService` (FCM), `ManifestacionesService` (scraping Metrobús local), `AfectMexibusFeed` (RSS Mexibús local), `AfectacionesMexibus` (panel del EC2), `Manifestaciones`.

## Backend (repo aparte)
El servidor NO está en este repo: **github.com/gutierrez-arquieta-abraham/metrobus_app** (`https://geomb.duckdns.org`, EC2).
Ver `metrobus_app/CLAUDE.md` y `docs/SESION_2026-09_backend.md` para arquitectura, servicios systemd, panel de afectaciones (fusión
Mexibús + Metrobús + avisos manuales), panel admin con mTLS (`:8443`) y deploy.

## Convenciones clave (resumen)
- Numeración interna: Metrobús 1–7; Mexibús 10X ordinario / 11X ramales / 12X exprés; Mexicable 20X. Prefijos `MXB `/`MXC ` se guardan pero no se muestran.
- Terminología: **transbordo** (Metrobús↔Metrobús), **correspondencia** (Mexibús/Mexicable), **conexión** (Metrobús↔Edomex).
- Seguridad: nunca tokens/llaves en claro ni al repo (Firebase, X Bearer, .pem) — solo en el EC2.
- Cambios de app requieren recompilar (Gradle). minSdk 24.
