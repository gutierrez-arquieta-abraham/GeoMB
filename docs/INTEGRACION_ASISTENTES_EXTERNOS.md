# Integración de GeoMB con asistentes/agentes externos (Android/Gemini del sistema)

> Decisión de arquitectura, no una guía de implementación: aquí se registra **por qué todavía
> no se conecta nada nuevo**, qué sí está listo para conectarse el día que sea viable, y qué
> haría falta cuando llegue ese día. Revisar y actualizar cuando cambie el estado de las APIs
> citadas abajo.

## Resumen de la decisión

**No se implementó ninguna integración nueva con AppFunctions/App Actions en este momento.**
Las dos vías oficiales de Android para esto están, respectivamente, en alfa + vista previa
privada (AppFunctions/Gemini) o no cubren el tipo de capacidad que necesita GeoMB (App
Actions/BII). Forzar una implementación ahora violaría la regla que el propio proyecto se puso:
*"no quiero implementar una tecnología experimental si existe una alternativa estable
adecuada"* — y en este caso **no existe** esa alternativa estable adecuada todavía.

Lo que SÍ existe, sin tocar nada, es la capa de adaptación en sí: el registry/tools de
`integrations/gemini/` (ver más abajo) y los endpoints REST de `server.py` YA son la "capa de
integración" pedida — ningún asistente externo necesitaría una implementación nueva de la
lógica de negocio, solo un transporte que la invoque.

## 1. Estado real de los mecanismos de Android (investigado, no asumido)

| Mecanismo | Estado | Notas |
|---|---|---|
| **AppFunctions** (`androidx.appfunctions`) | **Alfa** (`1.0.0-alpha12` a sept. 2026) | Jetpack en fase alfa: la API puede romperse en cualquier momento; Google no la recomienda para producción. Requiere Android 16+ (excluye la mayoría de usuarios actuales de GeoMB, que soporta minSdk 24). |
| **Integración AppFunctions ↔ Gemini del sistema** | **Vista previa privada** (mayo 2026, "trusted testers") | No está disponible para todos los desarrolladores — hace falta que Google admita a GeoMB en ese programa; no es algo que se "active" solo escribiendo código. Además exige que el *caller* (la app de Gemini) tenga el permiso `EXECUTE_APP_FUNCTIONS`. |
| **App Actions / Built-in Intents** (`shortcuts.xml`) | Estable pero de alcance fijo | Sigue documentado y funcional, pero son intents PREDEFINIDOS por categoría de app (Google decide el esquema); no admite una función arbitraria como "buscar una unidad por económico" o "catálogo de modelos" — está pensado para acciones genéricas tipo "pedir un viaje", no para el tipo de capacidad que tiene GeoMB. |
| **Conversational Actions** (Assistant standalone, el antecesor de App Actions) | **Dado de baja** (sunset 13 jun. 2023) | Ya no aplica; se menciona solo para no confundirlo con App Actions, que es distinto y sigue vivo. |
| **Android Protected Confirmation** | Estable, API 28+ | Es para confirmar transacciones sensibles con firma de hardware (pagos, etc.) — un mecanismo DISTINTO, no la forma en que AppFunctions maneja acciones que requieren confirmación. |

**Cómo AppFunctions modela una acción con efectos secundarios** (confirmado en la documentación
oficial, no inferido): el patrón recomendado por Google es que la función **no ejecute nada
directamente** y en vez de eso **devuelva un resultado que indique que se requiere
confirmación**, dejando la ejecución real a un paso posterior. Es decir, el día que esta
integración sea viable, el contrato sería *exactamente* el mismo `requiere_confirmacion` que ya
usa `tools_tracking.py` — no habría que inventar un modelo nuevo, solo exponerlo.

## 2. Auditoría: la capa de adaptación YA existe (no se duplicó nada)

Revisado el código real antes de proponer cualquier cosa:

- **`integrations/gemini/registry.py`** ya centraliza 16 tools (lectura + acción) con un único
  punto de verdad nombre↔función↔esquema. Es, literalmente, la "capa de capabilities" que pide
  este trabajo — solo que hoy el único consumidor es `GeoMBAgent` (nuestro propio chat).
- **`integrations/gemini/server.py`** ya expone, SIN pasar por Gemini ni por el chat, estos
  endpoints REST que envuelven las mismas funciones de `tools.py` sin duplicar nada:
  - `GET /lineas`, `GET /lineas/{id}` → `tools.info_linea`
  - `GET /estaciones?q=` → `tools.buscar_estacion`
  - `GET /unidades/{economico}` → `tools.buscar_unidad` (ya incluye catálogo + dirección
    cardinal + línea, de los últimos commits)
  - `GET /estado-servicio` → `tools.estado_servicio`
  - `GET /ruta?origen=&destino=` → `tools.planificar_ruta`
  - `GET /salud` → estado del proceso

  Ninguno de estos exige `X-Device-ID` ni pasa por `ratelimit`/`ip_limiter` (esos solo protegen
  `/chat`, que es el que paga la cuota de Gemini) — son lecturas públicas sin efectos
  secundarios, igual que los datos que la app Android ya descarga sin autenticación desde el
  backend real. **Cualquier agente HTTP de hoy, sin esperar a AppFunctions, ya podría consumir
  esto.**
- **Deliberadamente NO existe** un endpoint REST equivalente para `seguirUnidad`/
  `dejarDeSeguirUnidad`/`detenerRecorrido`/`crearReporte`. Abrir un POST público que devuelva
  `requiere_confirmacion` sin la sesión/cuota/rate-limit que ya protege `/chat` sería ampliar la
  superficie de ataque para un consumidor (AppFunctions-vía-Gemini) que hoy no puede ni llamarlo
  -- se audita esto explícitamente como **pendiente a propósito**, no como un olvido.
- **Android** (`RealtimeRepository`, `Modelos`, `SeguimientoService`, `GtfsRepository`,
  `AfectacionesMexibus`) ya tiene toda la lógica equivalente del lado del teléfono, sin pasar
  por el backend de Gemini en absoluto (así es como `MapFragment`/`CartaUnidad` ya responden
  hoy). El día que se implemente un `@AppFunction`, su cuerpo debe ser una llamada de una línea
  a estas clases -- nunca una reimplementación.

## 3. Qué haría falta el día que esto sea viable (boceto, NO implementado)

Condición de entrada: `androidx.appfunctions` en estable (no alfa) **y** GeoMB admitida en la
integración pública con Gemini (no vista previa privada) **y** aceptar el requisito de
Android 16+.

- **Lado Android**: una clase delgada (p. ej. `GeoMBAppFunctions.kt/java`) con un método
  `@AppFunction` por capability, cada uno delegando en UNA línea a la clase existente
  correspondiente (`RealtimeRepository.get().buscar(eco)`, `Modelos.paraEconomico(eco)`,
  `GtfsRepository`/búsqueda de estaciones, etc.). Cero lógica nueva.
- **Acciones** (`seguirUnidad`/`dejarDeSeguirUnidad`/similares): el `@AppFunction`
  correspondiente NUNCA llama a `SeguimientoService.iniciar()` directo — devuelve el resultado
  de confirmación (mismo patrón que ya usa Google para esto) y la ejecución real sigue pasando
  por el flujo que YA existe (`AccionPendiente` + diálogo + `confirmarAccion()`), igual que
  hoy pasa con el chat. Esta regla es la misma que ya rige en `ChatAsistenteActivity` y no debe
  relajarse por venir de un caller distinto.
- **Lado backend**: no haría falta tocar `registry.py`/`tools.py` — seguirían siendo el único
  punto de verdad; lo nuevo sería, a lo sumo, declarar el esquema de cada función en el formato
  que pida `androidx.appfunctions` (metadata, no lógica).

## Fuentes consultadas

- [Overview of AppFunctions](https://developer.android.com/ai/appfunctions)
- [androidx.appfunctions releases (versión y estado alfa)](https://developer.android.com/jetpack/androidx/releases/appfunctions)
- [Google Enables Android Apps With AppFunctions For Gemini](https://letsdatascience.com/news/google-enables-android-apps-with-appfunctions-for-gemini-8e56077a)
- [AppFunction Implementation and Configuration (patrón de confirmación para acciones destructivas)](https://developer.android.com/agents/skills/device-ai/appfunctions/references/implementation-configuration)
- [Built-in intents for App Actions](https://developer.android.com/guide/app-actions/intents)
- [Google to sunset Assistant's Conversational Actions as focus shifts to App Actions on Android](https://androidcentral.com/apps-software/google-shutting-down-conversational-actions)
- [Android Protected Confirmation](https://developer.android.com/privacy-and-security/security-android-protected-confirmation)
