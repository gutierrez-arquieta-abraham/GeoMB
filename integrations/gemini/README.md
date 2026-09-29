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
     -H "X-Device-ID: prueba-local" \
     -d '{"mensaje": "¿cómo llego de Tacubaya a Tenayuca?"}'
```
(`X-Device-ID` es obligatorio -- identifica la sesión y la cuota diaria del dispositivo,
ver "Sesiones por dispositivo y control de costo" más abajo.)

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

## Sesiones por dispositivo y control de costo

`POST /chat` requiere el header `X-Device-ID` (un identificador estable por instalación de
la app, ver el lado Android más abajo). Con ese id:

- **`session_manager.py`** le da a cada dispositivo su propia instancia de `GeoMBAgent` (su
  propio historial de conversación), en vez de compartir uno global entre todos los
  usuarios. Las sesiones expiran a los 30 min de inactividad (TTL) y hay un tope duro de
  500 sesiones simultáneas en memoria (LRU: se descarta la menos reciente si se llena).
  El historial de cada sesión además se recorta a los últimos `MAX_HISTORIAL_TURNOS` (6)
  turnos en `agent.py`, para no acumular tokens de contexto sin límite.
- **`ratelimit.py`** aplica un límite DIARIO por dispositivo (`GEOMB_LIMITE_MENSAJES_DIA`,
  default 30), persistido en SQLite (`uso.db`, generado en runtime, no se sube al repo).
  Si se agota, `/chat` responde `HTTP 429` con `{"error": "cuota_agotada", "mensaje": "..."}`
  en vez de llamar a Gemini. Un mensaje solo se cuenta contra la cuota si Gemini respondió
  con éxito.
- **`ip_limiter.py`** es un freno secundario en memoria (60 solicitudes/hora por IP) para
  mitigar que alguien rote `X-Device-ID` artificialmente y se salte el límite diario.
  `/chat` responde `HTTP 429` sin tocar Gemini si se excede.

**Importante:** tanto las sesiones como el límite por IP viven en memoria del proceso, así
que el servicio debe correr con **un solo worker de uvicorn** (ver
`deploy/geomb-gemini.service`). El límite diario en SQLite sí sobreviviría varios workers,
pero las sesiones y el límite por IP no.

## Apagado automático cuando nadie lo usa (activación por socket)

El servicio NO corre 24/7 en el EC2: se despliega con **activación por socket de systemd**
(`deploy/geomb-gemini.socket`). El socket queda escuchando el puerto siempre, pero el
proceso de Python (uvicorn + el cliente de Gemini) solo existe mientras hay tráfico.

- Cada solicitud marca actividad (`idle_shutdown.registrar_actividad()`, vía un middleware
  en `server.py`).
- Una tarea de fondo (`idle_shutdown.vigilar_inactividad()`) apaga el proceso con `SIGTERM`
  si pasan `GEOMB_IDLE_TIMEOUT_S` segundos sin ninguna solicitud.
- El socket (`Accept=no`) se queda escuchando de todos modos: la SIGUIENTE solicitud hace
  que systemd levante el proceso de nuevo automáticamente, sin intervención manual.

`GEOMB_IDLE_TIMEOUT_S=0` (el default en `.env.example`) desactiva esto -- úsalo en desarrollo
local con `uvicorn --reload`, donde no hay socket de systemd. En el EC2, ponle un valor como
`600` (10 min). El primer mensaje después de estar "apagado" tarda un poco más (arranca
Python desde cero antes de responder); los siguientes son normales hasta el próximo periodo
de inactividad.

## Despliegue en el EC2 (systemd + Nginx)

El servidor va en el MISMO EC2 donde corre `metrobus_app`, detrás del mismo dominio
`geomb.duckdns.org`, sin exponer el puerto de uvicorn directamente.

```bash
# En el EC2, dentro del clon de este repo:
cd integrations/gemini
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # llenar GEMINI_API_KEY, GEOMB_LIMITE_MENSAJES_DIA y GEOMB_IDLE_TIMEOUT_S
deactivate
```

1. Copia `deploy/geomb-gemini.socket` Y `deploy/geomb-gemini.service` a
   `/etc/systemd/system/`, ajusta en el `.service` el `User=` y las rutas
   (`WorkingDirectory`, `EnvironmentFile`, `ExecStart`) a la carpeta real del clon, y
   habilita el **socket** (no el service -- el service lo arranca el socket solo):
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now geomb-gemini.socket
   sudo systemctl status geomb-gemini.socket    # debe quedar "active (listening)" SIEMPRE
   sudo systemctl status geomb-gemini.service   # "inactive (dead)" hasta la primera solicitud
   ```
2. Agrega el contenido de `deploy/nginx-geomb-gemini.conf` dentro del `server{}` HTTPS
   que ya sirve `geomb.duckdns.org` (el mismo que usa `metrobus_app`, no un `server{}`
   nuevo ni un subdominio), y recarga Nginx:
   ```bash
   sudo nginx -t && sudo systemctl reload nginx
   ```
3. Probar desde fuera del EC2:
   ```bash
   curl https://geomb.duckdns.org/gemini/salud
   curl -X POST https://geomb.duckdns.org/gemini/chat \
        -H "Content-Type: application/json" -H "X-Device-ID: prueba-123" \
        -d '{"mensaje": "¿cómo llego de Tacubaya a Tenayuca?"}'
   ```
   Justo después de ese `curl`, `sudo systemctl status geomb-gemini.service` debe verse
   `active (running)`; pasados `GEOMB_IDLE_TIMEOUT_S` segundos sin más tráfico, vuelve solo a
   `inactive (dead)` (el socket sigue `active (listening)`).

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
- **El `X-Device-ID` no está firmado ni verificado criptográficamente**: es solo un UUID
  que genera la app (ver lado Android). Sirve para repartir cuota y aislar sesiones entre
  usuarios normales, no para autenticación fuerte -- un atacante que decida gastar tiempo
  en rotarlo manualmente sigue limitado por `ip_limiter.py`, pero no es infalible.
- **Seguridad**: la `GEMINI_API_KEY` se lee de `.env` -- nunca la subas al repo (mismo
  criterio del proyecto, ver `CLAUDE.md`).
