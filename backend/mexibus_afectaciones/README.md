# Mexibús Afectaciones — endpoint (FastAPI)

Servicio que reúne las afectaciones del servicio **Mexibús** desde las redes
oficiales (X y Facebook por línea) y las expone en el **mismo formato JSON** que
ya consume la app GeoMB, para servirlas desde tu **EC2**.

## Qué hace

1. Cada `MXB_POLL_SEGUNDOS` consulta las cuentas configuradas (`data/accounts.json`).
2. Interpreta cada post (palabras clave + catálogo de líneas/estaciones de
   `data/mexibus_catalog.json`, generado del `mexibus.json` de la app).
3. Deduplica, filtra por ventana de vigencia y **cachea** el resultado.
4. Sirve `GET /mexibus/afectaciones` → `{"rows":[...]}` (respuesta instantánea desde caché).

El esquema de cada `row` es idéntico al de Metrobús que ya parsea la app:
`tipo` (`estado`|`mantenimiento`|`elevador`), `linea` (número Mexibús `101`–`113`),
`estacion`, `direccion`, `motivo`, `extra`.

## Estructura

```
mexibus_afectaciones/
  app/
    main.py         # FastAPI + endpoints
    config.py       # settings por env (prefijo MXB_)
    models.py       # Row / RawPost (Pydantic)
    catalog.py      # detección de línea/estación en texto
    parser.py       # post -> filas de afectación (heurística, fácil de afinar)
    aggregator.py   # poller + caché + dedupe
    sources/
      base.py       # contrato Source
      twitter_x.py  # X API v2 (necesita MXB_X_BEARER_TOKEN)
      facebook.py   # Graph API (necesita MXB_FB_PAGE_ACCESS_TOKEN)
      mock.py       # posts de ejemplo (modo demo, sin credenciales)
  data/
    accounts.json         # cuentas oficiales por línea (editable)
    mexibus_catalog.json  # líneas + estaciones
  deploy/mexibus-afectaciones.service  # unit systemd
  .env.example  requirements.txt  run.sh
```

## Correr local (modo demo, sin credenciales)

```bash
cd mexibus_afectaciones
cp .env.example .env      # MXB_USAR_MOCK=true por defecto
./run.sh                  # crea venv, instala y levanta en :8080
# prueba:
curl localhost:8080/health
curl localhost:8080/mexibus/afectaciones
```

## Conectar las redes reales

Edita `.env`:

- **X (Twitter):** pon `MXB_X_BEARER_TOKEN`. La API v2 para leer timelines es de
  **pago** (tier Basic+). Cuentas en `data/accounts.json` (`platform: "x"`).
  - **Sin pagar (no oficial, frágil, puede violar ToS):** `MXB_X_FREE=true`. Lee vía
    **Nitter** si defines `MXB_NITTER_BASE` (recomendado: tu propia instancia), o
    intenta el endpoint de **sindicación** de X como respaldo. Solo se usa cuando NO
    hay bearer token. Puede dejar de funcionar cuando X cambie algo.
- **Facebook:** pon `MXB_FB_PAGE_ACCESS_TOKEN` (Graph API). Solo funciona con
  páginas que administras o que te otorgan acceso; leer páginas públicas de
  terceros ya **no** es abierto en Graph API.
- Pon `MXB_USAR_MOCK=false` para usar las fuentes reales.

Sin token, esa plataforma queda **inactiva** (el servicio sigue con las demás).
Las cuentas que enviaste ya están en `data/accounts.json`; las dos de línea
"por confirmar" tienen `linea: null` (se detecta por texto) — ajústalas cuando
sepas a qué línea pertenecen.

## Desplegar en EC2

```bash
# en el EC2 (Ubuntu):
sudo apt update && sudo apt install -y python3-venv
git clone <tu-repo> && cd mexibus_afectaciones   # o sube la carpeta por scp
cp .env.example .env && nano .env                 # tokens + MXB_USAR_MOCK=false
python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt

# como servicio (systemd):
sudo cp deploy/mexibus-afectaciones.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mexibus-afectaciones
```

Abre el puerto `8080` (o pon Nginx/ALB al frente) en el **Security Group**.
Recomendado: Nginx como reverse proxy con TLS y cachear `GET /mexibus/afectaciones`.

## Integrar con la app GeoMB

La app ya parsea `{"rows":[...]}`. Para consumir este endpoint de Mexibús solo
hay que hacer un `GET` al JSON (a diferencia del feed de Metrobús, que se raspa por
WebView). En `ManifestacionesService` agrega una descarga HTTP a
`https://<tu-ec2>/mexibus/afectaciones` y mezcla esas filas con `linea` 101–113 en
la misma lista de `Manifestaciones`. (Dímelo y lo cableo en el cliente.)

## Afinar el parser

`parser.py` es heurístico. Ajusta:
- `KW_ESTADO / KW_MANTENIMIENTO / KW_ELEVADOR / KW_NORMAL` (palabras clave).
- `RX_DIRECCION / RX_HORA / RX_FECHA` (extracción de dirección/fecha).
El `mock.py` sirve de banco de pruebas: agrega ejemplos reales y valida la salida.
```
