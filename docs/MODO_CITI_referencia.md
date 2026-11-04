# Modo estilo CITI — referencia de la app original

Investigación previa para un "modo CITI" en GeoMB (posible función de suscripción). Las capturas
originales NO se guardan en el repo (son de Indra/Metrobús); solo esta descripción.

## Qué app era
- **"Metrobús CDMX - App oficial"**, de **Indra Sistemas, S.A.** (la misma empresa del SAE/CITI del Metrobús).
- Android `com.indra.apps.mb`. Pantalla "Acerca de": *"App Android Metrobús v1.0 © Indra 2017"*.
- Versiones: **1.0** (23-ene-2018) y **1.1** (30-oct-2018, "Actualización de iconos y plano de la red, incluye nueva Línea 7").
  Android 4.0+, ~5 MB, sin anuncios, español/inglés. 10 000+ descargas.
- Ya no está en Google Play, y la versión iOS (id1169069659) se retiró de la App Store. Solo quedan copias en sitios de terceros (APKPure).
- No debe confundirse con: el CITI en sí (centro de control interno, no es app), la App CDMX (gobierno, actual) ni
  apps de terceros "Metrobús CDMX: Metro y Rutas".

## Funciones (según su ficha)
Ubicación de estaciones · rutas y destinos · estimación de llegadas y horario programado de los próximos días ·
estaciones cercanas · avisos de cambios de servicio · **escáner del QR de cada estación** (abre sus llegadas/rutas) ·
buscar un autobús para ver su ubicación, ruta y llegadas a las siguientes estaciones.

## Diseño (de las capturas)
**Marco común a todas las pantallas**
- Franja blanca superior con logos Metrobús | CITI. **En GeoMB NO se copian los logos** (riesgo de suplantación en Google Play).
- Barra de título **gris claro** con el nombre de la pantalla en negro (negrita) a la izquierda y botón **"Menú"** rojo vino
  con esquinas redondeadas a la derecha.
- Rojo de botones ≈ `#B31C3F`. Fondo blanco, tipografía tipo Roboto, sin tarjetas ni sombras.

**Inicio**: rejilla de 8 botones circulares (contorno rojo grueso + ícono rojo) con texto debajo:
Próximo autobús · Rutas · Mapa · Mi trayecto · Estaciones cercanas · Puntos de recarga · Favoritos · Información.

**Estimaciones de llegadas**: pictograma de la estación + nombre arriba; lista con **cuadro del color de la línea con su
número**, "Origen-Destino" y **"N min"** a la derecha; botón grande rojo **"Refrescar"** abajo.

**Seleccione ruta**: lista de rutas, cada una con el cuadro numerado del color de la línea y "Origen-Destino".
Colores medidos: L1 `#983223`, L2 `#7C109A`, L3 `#739500`, L4 `#F8901F`, L5 `#223C77`, L6 `#E73F97`.

**Mapa**
- Google Maps **claro estándar** (con calles y puntos de interés), botón de ubicación arriba a la derecha y **+ / −**
  en recuadro blanco abajo a la derecha.
- Solo se ve **la ruta elegida**: trazo del color de la línea con **flechas de sentido**.
- Recuadro flotante arriba a la izquierda con lo que se está filtrando (ícono de autobús o el cuadro con el número de la
  ruta, p. ej. "2") y una **X roja** para quitar el filtro.
- Autobús: **ícono de bus negro sobre blanco**; al tocarlo, globo blanco "**Autobús 123**" + "*Línea 1 El Caminero-Indios Verdes*" (cursiva).
- Estación: **pictograma cuadrado**, uno por andén; al tocarla, globo con el nombre y abajo una pila de botones rojos
  anchos: **Ver tiempos · Ver rutas · Añadir a favoritos · Cancelar**.

## Cómo se traduce a GeoMB (resumen)
| Original | GeoMB hoy | Para el modo |
|---|---|---|
| Barra gris + "Menú" rojo | Navegación inferior | Barra superior estilo CITI (sin logos) |
| Mapa solo de la ruta elegida + chip con X | Toda la red; el filtro solo oculta unidades | Filtrar trazo/estaciones por ruta |
| Flechas de sentido en el trazo | No hay | Stamp de flecha en la polilínea (Maps SDK 19 lo soporta) |
| Bus negro/blanco, globo "Autobús N / Línea…" | Bus de color con número | Ícono y globo estilo CITI |
| Botones rojos al tocar estación | Tarjeta flotante | Pila de botones rojos |
| Mapa claro siempre | Oscuro de noche | Forzar mapa claro |
| Lista de llegadas con cuadro numerado | Pestaña Llegadas | Variante de lista estilo CITI |
| Voz del anuncio real | Ya existe (voz Ximena, frase "Voz CITI Indra") | Incluirla en el modo |

Fuentes: ficha de APKPure de `com.indra.apps.mb`; notas de iWorld (jul-2016) e Indicador Automotriz (ago-2016) sobre el SAE
de Indra; El Universal (abr-2019).

## Medidas exactas (de la APK 1.1 descompilada)
- `header.xml`: fondo blanco, logo de 50dp de alto con margen 5dp, línea inferior 1dp `#A09D9E`.
- `bar_menu.xml`: fondo `#DDDDDD`, padding 12dp izq / 6dp resto, título 16sp negrita `#222222`, botón "Menú" 12sp
  negrita blanco sobre `#B31C3F` (radio 6dp, padding 14×10dp, mín. 60×30dp), línea inferior 1dp `#CCCCCC`.
- Mapa (`fragment_mapa.xml` + `FragmentMapa`): mapa normal, sin brújula/rotación/inclinación, **+/− nativos** de Google.
  Trazo `map_iti_width` = **3dp**; estación `map_stop_width` = **20dp**; autobús `map_bus_width` = **32dp** (`icon_bus`).
- **Flechas de sentido**: no son un patrón sobre la línea. Cada estación lleva detrás `app_icon_course56` (56dp) girada
  hacia el siguiente punto del itinerario (`Project.rumboLinea = true`). Dos andenes ⇒ dos flechas (↕).
- Caja de "modo mapa" (`vModoMapa`): fondo `#CCEEEEEE`, borde 0.5dp `#44222222`, radio 2dp, margen 10dp; dentro, cuadro
  de ruta (14sp negrita, 32dp de alto, radio 3dp) y X roja de 28dp.
- Globo (`map_popup.xml`): fondo blanco, padding 3dp, título negrita negro, subtítulo cursiva `#666666` (máx. 260dp).
  Autobús: "Autobús %s"; estación: su nombre.
- Al tocar estación (`fragment_menu_parada_mapa.xml`): botones `button_menu` (16sp negrita, mín. 260×40dp, margen inferior
  7dp): Ver tiempos · Ver líneas · Ver destinos · Añadir a favoritos · Cancelar. **(pendiente en GeoMB)**

## Implementación en GeoMB (paso 1: mapa + barra superior)
- Activación: modo personalizado → frase **`App Android Metrobús v1.0`** → switch "Estilo CITI" (`Modos.estiloCiti`).
- `layout/barra_citi.xml` (logos + barra "Mapa/Menú" + caja de ruta), `layout/map_info_window_citi.xml`, `EstiloCiti.java`
  (colores, trazo, estación con flechas de rumbo, autobús) y ramas `citi` en `MapFragment`.
- "Menú": lista de rutas (filtra trazo, estaciones y unidades, como `goToRoute()`), "Ver todas las rutas" y "Salir".
- Logos: Metrobús **actual** (`logo_mb.png`) + CITI recortado del `logo.png` original (`citi_logo_citi.png`).
- Imágenes originales copiadas tal cual (nostalgia): `citi_rumbo.png` (`app_icon_course56`), `citi_bus.png` (`icon_bus`),
  `citi_cerrar.png` (`app_icon_close32`). Son propiedad de Indra/Metrobús.
- En estilo CITI no se dibujan Mexibús/Mexicable, rutas mixtas ni los corredores de Indios Verdes (la app original no los tenía).
