# UML — GeoMB Backend (Servidor EC2)

Diagrama de componentes/clases del backend (repo `metrobus_app`, en el EC2 `https://geomb.duckdns.org`).
Muestra los 3 procesos (servicios systemd), sus módulos, los archivos de datos que comparten y las fuentes externas.

> Relaciones: `..>` usa/llama · `-->` escribe/lee archivo · `o--` registra (blueprint).

```mermaid
classDiagram
  direction LR

  %% ===================== PROCESO 1: WEB (metrobus-web) =====================
  class app_py["app.py — Flask (gunicorn :8000)"] {
    +/ index.html
    +/data/vehicles.json
    +/data/afectaciones_mexibus.json  (FUSIÓN)
    +/data/modelos.csv
    +/health
    +/admin/rt_url
    +poll_loop()  feed Sonda GTFS-rt
  }
  class admin_afect["admin_afect.py — Blueprint"] {
    +GET/POST /admin/afectacion
    +_guardar_override()  expira
    +mTLS X-Client-Verify
  }
  class didit_backend["didit_backend.py — Blueprint KYC"] {
    +/api/didit/session
    +/api/didit/webhook
    +/api/didit/status
  }
  class tts_backend["tts_backend.py — Blueprint Polly"] {
    +/api/tts (voz Mia)
  }
  app_py o-- admin_afect : blueprint
  app_py o-- didit_backend : blueprint
  app_py o-- tts_backend : blueprint

  %% ===================== PROCESO 2: PUSH METROBÚS (metrobus-push) =====================
  class push_metrobus["push_metrobus.py"] {
    +iniciar_monitor()
    +_ciclo()  raspa estado gov
    +_push(topic,...)  FCM
    +_escribir_estado_metrobus()
    +enviar_actualizacion()
  }

  %% ===================== PROCESO 3: AFECTACIONES MEXIBÚS (mexibus-afectaciones) =====================
  class mexibus_afect["mexibus_afectaciones.py"] {
    +fetch_posts()  RSS
    +parse_post()  #ampliacion->L3A
    +computar_estado()  TTL + 23:59
    +enviar_fcm()
    +webhook /webhook/afectacion
  }

  %% ===================== ARCHIVOS DE DATOS (data/) =====================
  class vehicles_json["data/vehicles.json"]
  class afect_mxb["data/afectaciones_mexibus.json"]
  class afect_metro["data/afect_metrobus.json"]
  class afect_manual["data/afect_manual.json"]

  app_py --> vehicles_json : escribe/sirve
  mexibus_afect --> afect_mxb : escribe
  push_metrobus --> afect_metro : escribe
  admin_afect --> afect_manual : escribe
  app_py --> afect_mxb : lee (fusión)
  app_py --> afect_metro : lee (fusión)
  app_py --> afect_manual : lee (fusión)
  admin_afect ..> push_metrobus : _push (FCM)

  %% ===================== INFRA / EXTERNOS =====================
  class nginx["nginx + Certbot"] {
    +:443 público (proxy a :8000)
    +:8443 mTLS (solo /admin)
    +/admin -> 404 en :443
  }
  class FCM["Firebase Cloud Messaging"]
  class Sonda["API Sonda GTFS-rt"]
  class RSS["Feeds RSS.app (SITRAMYTEM)"]
  class GobMB["Páginas gob (estado Metrobús)"]

  nginx ..> app_py : proxy
  app_py ..> Sonda : feed unidades
  push_metrobus ..> GobMB : scraping
  push_metrobus ..> FCM
  mexibus_afect ..> RSS
  mexibus_afect ..> FCM
  admin_afect ..> FCM
```

## Notas
- **La app Android consume** `/data/afectaciones_mexibus.json` (fusión Mexibús + Metrobús + avisos manuales, calculada al vuelo)
  y `/data/vehicles.json`. El panel de estado ingiere cualquier línea (Metrobús 1–7 y Mexibús 10X+).
- **Seguridad:** el panel admin solo entra por `:8443` con certificado cliente (mTLS); `/admin` está bloqueado (404) en el `:443` público.
  Secretos (`firebase.json`, `.pem`, tokens) solo en el EC2, nunca al repo.
- **Servicios systemd:** `metrobus-web` (app.py), `metrobus-push` (push_metrobus.py), `mexibus-afectaciones` (mexibus_afectaciones.py).
- Detalle completo: `metrobus_app/CLAUDE.md` y `docs/SESION_2026-09_backend.md`.
