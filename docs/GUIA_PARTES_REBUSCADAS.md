# Guía de partes "rebuscadas" — GeoMB

Índice de la lógica NO obvia del proyecto: dónde está, qué concepto usa y qué la explica.
Cada archivo tiene, EN EL CÓDIGO, un comentario de sección (bloque `// ====` o `#`) que desarrolla el porqué.
Esta guía es solo el mapa para encontrarlas rápido.

> Convención: `Clase.metodo()` → concepto → una línea de qué hace. El detalle completo está en el comentario del código.

---

## 1. Flujo de reportes

| Dónde | Concepto | Qué resuelve |
|---|---|---|
| `ReporteIrregularidad.enviar()` | Intents de Android + flags | Elige `ACTION_SEND_MULTIPLE` / `ACTION_SEND` / `mailto:` según los adjuntos; `message/rfc822` sesga a apps de correo; `FLAG_GRANT_READ_URI_PERMISSION` deja al correo leer los adjuntos de otra app. NO auto-envía (evita reportes falsos). |
| `ReportePdf.parrafo()` | Word-wrap a mano | Canvas no ajusta texto solo: arma la línea palabra por palabra y salta antes de que `measureText()` rebase el ancho. |
| `ReportePdf.dibujarIconoLinea()` | Contorno por estampado | No hay "stroke" para un bitmap: estampa 8 copias negras desfasadas para formar la silueta y encima el icono a color. |
| `ReportePdf.generar()` (return) | FileProvider | El correo es otra app y no puede leer nuestro `cacheDir`: FileProvider da un `content://` temporal que sí puede abrir. |
| `didit_backend.webhook()` | HMAC + canonicalización + tiempo constante | Verifica que el webhook lo mandó Didit (firma HMAC con secreto compartido); acepta firma sobre bytes crudos o cuerpo canonicalizado; `compare_digest` evita timing attacks; timestamp anti-replay. |
| `DiditKYC.consultarEstado()` / `abrir()` | Red fuera del hilo principal | Patrón `new Thread(...)` + `main.post(...)`: la red no puede ir en el hilo de UI (congelaría / `NetworkOnMainThreadException`). |

## 2. Geometría y tiempo real

| Dónde | Concepto | Qué resuelve |
|---|---|---|
| `Linea.proyectar()` | Grados→metros + proyección por producto punto | Convierte lat/lon a un plano local (equirectangular con `COS_LAT`) y proyecta un punto sobre un segmento (t recortado a [0,1]) para hallar el punto más cercano de la vía. |
| `Linea.puntoEn()` | Interpolación inversa | Dada una distancia a lo largo de la ruta, ubica el segmento (con `acumulado`) e interpola la coordenada. Junto con `distanciaEn` permite el "snap" a la vía. |
| `UnidadAnimador.animar()` | Dead-reckoning + token de cancelación | Mueve el marcador en 2 fases (deslizar hacia la posición reportada, luego avanzar por velocidad a fracción para no "regresar"); el token detiene el bucle viejo cuando llega un update más nuevo. |
| `Iconos.escalado()` | Anti-OOM con `inSampleSize` | Mide el bitmap primero (`inJustDecodeBounds`), decodifica reducido (potencia de 2) y luego escala fino, para no reventar la memoria con PNG grandes. |

## 3. Ruteo (Planificador)

| Dónde | Concepto | Qué resuelve |
|---|---|---|
| `Planificador.calcular()` (bloque Dijkstra) | Dijkstra + doble pasada | Camino de menor TIEMPO con cola de prioridad y relajación de aristas; `prev[]` reconstruye la ruta; corre 2 veces (restringido → respaldo). |
| `Planificador.agregarVariantes()` | Subsecuencia de la 12X | Cada servicio exprés (TR3/TR4, Express 1/2/3) es un subconjunto de paradas de la línea 12X: se crea una ruta que solo pasa por ellas pero reusa la geometría de la 12X; dedupe por patrón (Rosa = mismo trazo). |

## 4. Datos, concurrencia y parsing

| Dónde | Concepto | Qué resuelve |
|---|---|---|
| `GtfsRepository.getLineas()` | Double-checked locking + `volatile` | Lectura sin candado (rápida) y construcción una sola vez; `volatile` garantiza ver la lista completa, no a medio construir. |
| `GtfsRepository` (carga) | Streaming `JsonReader` | Parsea el JSON objeto por objeto (sin cargarlo entero a memoria) y publica listas inmutables en referencias `volatile`. |
| `ManifestacionesService.ec2Activo()` / `refrescarEc2()` | Liveness cacheado en 2º plano | Evita `NetworkOnMainThreadException` (la red no puede ir en el callback del WebView): cachea el epoch del último JSON del EC2 y decide "vivo/caído" sin red. Fix de las 2 notificaciones. |
| `mexibus_afectaciones._pat()` | Regex tolerante | Atrapa "Línea 4 / L4 / MexibúsLínea4…" con/sin acento; los límites de palabra evitan falsos positivos ("calle 1 a…" ≠ ramal 1a). |
| `mexibus_afectaciones.lineas_en_texto()` | Reglas de prioridad | `#ampliación` + línea 3 ⇒ L3A (113); "Servicio Eléctrico" ⇒ L2A. |
| `RecorridoService.procesar()` | Máquina de "qué aviso toca" | Búsqueda monótona hacia adelante, reglas finas de cambio de línea (Indios Verdes/zonas), próxima estación saltando andenes co-ubicados. Ver el mapa de alto nivel al inicio del método + los comentarios de cada bloque. |

---

## Notas
- Todo lo anterior son **comentarios** en el código (no cambian la lógica).
- Los módulos Python (`mexibus_afectaciones.py`, `didit_backend.py`) viven también en el repo `metrobus_app`; si editas en `Escritorio/GeoMB`, re-sincroniza con `cp`.
- Contexto general del proyecto: `MEMORIA.md`; arquitectura backend: `metrobus_app/CLAUDE.md`; diagramas: `docs/uml/`.
