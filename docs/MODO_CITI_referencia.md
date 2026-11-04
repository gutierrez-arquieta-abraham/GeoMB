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
