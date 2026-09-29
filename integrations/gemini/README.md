# GeoMB + Gemini (Function Calling)

Asistente conversacional para los usuarios de GeoMB: el usuario pregunta en lenguaje natural
("¿cómo llego de Tacubaya a Tenayuca?", "¿hay afectaciones en Mexibús L3?", "¿dónde está la
unidad 9507?") y Gemini decide, con **Function Calling**, qué herramienta de GeoMB llamar
para responder con datos reales (no inventados).

## Por qué es un backend nuevo, no la app Android

La app Android (este repo) no tiene servidor propio: el backend real vive en
`github.com/gutierrez-arquieta-abraham/metrobus_app` y solo expone datos en vivo (posiciones
de unidades, afectaciones). Cosas como el **planificador de ruta** (Dijkstra + exprés +
circuitos) viven como código Java dentro de la app, no como un endpoint. Como pediste un
asistente para usuarios finales, este backend en Python:

- Lee los **mismos assets** que empaqueta la app (`app/src/main/assets/lineas.json` y
  `mexibus.json`) para el catálogo de líneas/estaciones y un planificador de ruta propio
  (simplificado, ver más abajo).
- Llama al **backend real** (`https://geomb.duckdns.org`, con el mismo failover a Railway que
  usa la app) para datos en vivo: posiciones de unidades y afectaciones.

## Estructura

```
integrations/gemini/
  config.py          Variables de entorno (.env)
  data_loader.py      Carga lineas.json/mexibus.json, búsqueda de estaciones/líneas
  graph.py            Planificador de ruta simplificado (grafo + Dijkstra)
  backend_client.py   Cliente HTTP al backend real (con failover)
  tools.py            Las funciones de GeoMB + su esquema para Gemini (Function Calling)
  agent.py            Bucle Gemini <-> herramientas <-> Gemini hasta la respuesta final
  server.py           API REST (FastAPI): endpoints propios + POST /chat
  cli.py              Chat de prueba por terminal, sin levantar el servidor
```

## Instalación

```bash
cd integrations/gemini
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # y llena GEMINI_API_KEY
```

## Uso

**Chat por terminal** (más rápido para probar):
```bash
python -m integrations.gemini.cli
```

**Servidor** (API REST + `/chat`):
```bash
uvicorn integrations.gemini.server:app --reload --port 8000
curl -X POST localhost:8000/chat -H "Content-Type: application/json" \
     -d '{"mensaje": "¿cómo llego de Tacubaya a Tenayuca?"}'
```

Endpoints propios de GeoMB (sin pasar por Gemini, útiles para probar cada herramienta
por separado): `GET /lineas`, `GET /lineas/{sistema}:{numero}`, `GET /estaciones?q=`,
`GET /unidades/{economico}`, `GET /estado-servicio?linea=`, `GET /ruta?origen=&destino=`.

## Las 5 herramientas (Function Calling)

| Función | Qué hace | Fuente de datos |
|---|---|---|
| `buscar_unidad` | Posición/estado de una unidad por económico | Backend en vivo (`/data/vehicles.json`) |
| `estado_servicio` | Afectaciones activas (toda la red o una línea) | Backend en vivo (`/data/afectaciones_mexibus.json`, ya trae Metrobús + Mexibús + avisos manuales) |
| `info_linea` | Nombre, terminales y estaciones de una línea | Assets locales |
| `buscar_estacion` | En qué línea(s) existe una estación | Assets locales |
| `planificar_ruta` | Ruta sugerida entre dos estaciones | Grafo local (ver limitaciones) |

El identificador de línea que Gemini debe mandar es `"<sistema>:<numero>"`
(`metrobus:2`, `mexibus:3`, `mexibus_ramal:1`, `mexibus_expres:2`, `mexicable:1`) -- se
describe así en el esquema para que el modelo lo arme bien solo, sin adivinar la
numeración interna (1-7 Metrobús, 10X/11X/12X Mexibús, 20X Mexicable).

## Limitaciones conocidas (léelas antes de usarlo con usuarios reales)

- **`planificar_ruta` NO es un puerto de `Planificador.java`.** Es un grafo simple
  (líneas troncales + transbordos por coincidencia de nombre/cercanía) con Dijkstra por
  distancia en línea recta. No conoce exprés, ramales, circuitos, horarios, ni
  correspondencias declaradas a mano con nombres distintos (p. ej. "Indios Verdes"
  Metrobús ↔ Mexibús). Para una réplica fiel, lo correcto a mediano plazo es exponer el
  Planificador REAL de la app como un endpoint (Java/Kotlin, o un puerto completo a
  Python) en vez de reimplementarlo aquí.
- **Estado del servicio de Metrobús** en la app real se obtiene con scraping (WebView) en
  el propio teléfono (`ManifestacionesService`) cuando el backend está caído; aquí solo
  se usa el JSON del backend (que la mayor parte del tiempo ya trae todo fusionado).
- **Sesión de chat única** en `server.py` (una sola instancia de `GeoMBAgent` para toda la
  API): para producción con múltiples usuarios simultáneos hace falta una sesión por
  usuario (p. ej. guardando el historial en Redis/DB, indexado por `session_id`).
- **Seguridad**: la `GEMINI_API_KEY` se lee de `.env` -- nunca la subas al repo (mismo
  criterio del proyecto, ver `CLAUDE.md`). El endpoint `/chat` no tiene autenticación ni
  límite de uso; agrégalos antes de exponerlo públicamente.
