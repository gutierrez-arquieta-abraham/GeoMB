# UML — GeoMB App Móvil (Android)

Diagrama de clases (alto nivel) del paquete `com.memegrados.GeoMB`. Agrupado por subsistema.
Se muestran las clases principales y sus relaciones; los adapters/fragments secundarios se listan en el inventario al final.

> Relaciones: `o--` composición/agregación · `..>` usa/depende · `*--` contiene (host de fragments).

```mermaid
classDiagram
  direction LR

  %% ===================== MODELO DE DATOS =====================
  class Linea {
    +int numero
    +String nombre
    +String colorHex
    +List~Estacion~ estaciones
    +distanciaEn(LatLng) double
    +puntoEn(double) LatLng
  }
  class Estacion {
    +String nombre
    +LatLng pos
    +String icono
  }
  class Ruta {
    +List pasos
    +List instrucciones
    +trazo
    +recorrido() String
  }
  class UnidadReal {
    +String id
    +Integer linea
    +String ruta
    +float velMs
    +long timestamp
    +double lat
    +double lon
  }
  Linea "1" o-- "*" Estacion

  %% ===================== REPOSITORIOS / DATOS =====================
  class GtfsRepository
  class RutasRepository
  class RealtimeRepository
  class Horarios
  class Servicios
  class ServiciosMexibus
  class RutasMixtas
  class Modelos
  class Backend
  class Config
  class Iconos
  class Tipografia
  GtfsRepository ..> Linea : carga
  GtfsRepository ..> Estacion : carga
  RutasRepository ..> Ruta
  RutasRepository ..> Backend
  RealtimeRepository ..> UnidadReal
  RealtimeRepository ..> Backend
  Backend ..> Config
  Modelos ..> Backend

  %% ===================== RUTEO (Dijkstra) =====================
  class Planificador
  Planificador ..> GtfsRepository
  Planificador ..> Servicios
  Planificador ..> ServiciosMexibus
  Planificador ..> RutasMixtas
  Planificador ..> Horarios
  Planificador ..> Ruta : produce

  %% ===================== TIEMPO REAL =====================
  class UnidadAnimador
  class Llegadas
  class Filtro
  UnidadAnimador ..> UnidadReal : anima
  UnidadAnimador ..> Linea : snap al grafo
  Llegadas ..> UnidadReal
  Llegadas ..> Linea
  Filtro ..> UnidadReal : filtra

  %% ===================== RECORRIDO / VOZ =====================
  class RecorridoService
  class SeguimientoService
  class Locuciones
  class DescargaVoz
  class DescargaVozService
  RecorridoService ..> Ruta
  RecorridoService ..> Planificador
  RecorridoService ..> Locuciones
  RecorridoService ..> DescargaVoz
  RecorridoService ..> Tipografia
  RecorridoService ..> Iconos
  DescargaVozService ..> DescargaVoz

  %% ===================== AFECTACIONES =====================
  class MensajesService
  class ManifestacionesService
  class Manifestaciones
  class AfectMexibusFeed
  class AfectacionesMexibus
  MensajesService ..> Manifestaciones
  ManifestacionesService ..> Manifestaciones
  AfectMexibusFeed ..> Manifestaciones
  AfectacionesMexibus ..> Manifestaciones
  AfectacionesMexibus ..> Backend

  %% ===================== UI (Activities / Fragments) =====================
  class MainActivity
  class MapFragment
  class PlanificadorFragment
  class SearchFragment
  class LlegadasFragment
  class FiltrosSheet
  MainActivity *-- MapFragment
  MainActivity *-- PlanificadorFragment
  MainActivity *-- SearchFragment
  MainActivity *-- LlegadasFragment
  MapFragment ..> RealtimeRepository
  MapFragment ..> UnidadAnimador
  MapFragment ..> GtfsRepository
  MapFragment ..> FiltrosSheet
  PlanificadorFragment ..> Planificador
  PlanificadorFragment ..> RecorridoService
  LlegadasFragment ..> Llegadas
  FiltrosSheet ..> RutasRepository
```

## Inventario completo de clases (por subsistema)

- **Modelo de datos:** `Linea`, `Estacion`, `Ruta`, `UnidadReal`.
- **Repositorios / datos:** `GtfsRepository`, `RutasRepository`, `RealtimeRepository`, `Horarios`, `Servicios`, `ServiciosMexibus`, `RutasMixtas`, `Modelos`, `Backend`, `Config`, `Modos`, `Idiomas`, `Iconos`, `Tipografia`.
- **Ruteo:** `Planificador`.
- **Tiempo real:** `UnidadAnimador`, `Llegadas`, `Filtro`, `Red`, `RedViewModel`.
- **Recorrido / voz:** `RecorridoService`, `SeguimientoService`, `Locuciones`, `DescargaVoz`, `DescargaVozService`.
- **Afectaciones:** `MensajesService`, `ManifestacionesService`, `Manifestaciones`, `AfectMexibusFeed`, `AfectacionesMexibus`.
- **Actividades / arranque / servicios:** `MainActivity`, `SplashActivity`, `LoginActivity`, `ArranqueReceiver`, `LlegadaService`, `SincronizacionService`.
- **Fragments:** `MapFragment`, `PlanificadorFragment`, `SearchFragment`, `LinesFragment`, `RutasFragment`, `EstacionesLineaFragment`, `LlegadasFragment`, `UnidadesFragment`, `ReporteFragment`, `AcercaFragment`.
- **Adapters / UI:** `LinesAdapter`, `RutasAdapter`, `EstacionesAdapter`, `EstacionRutaAdapter`, `UnidadesAdapter`, `LlegadasAdapter`, `AfectacionesAdapter`, `FiltrosSheet`.
- **KYC / reportes / perfil:** `DiditKYC`, `Verificacion`, `Perfil`, `ReporteIrregularidad`, `ReportePdf`, `Traductor`.
