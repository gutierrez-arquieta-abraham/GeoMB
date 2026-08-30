# Regenerar el trazado de las líneas (segmentos.json)

Ya generé `app/src/main/assets/segmentos.json` con el trazado oficial del
mapa de Metrobús (Google My Maps), y la app ya lo dibuja. Este script sirve
para **volver a generarlo** en el futuro (por si el mapa oficial cambia).

`generar_lineas.py` reconstruye `app/src/main/assets/segmentos.json`: el
trazado de cada línea por tramos (ida/vuelta y ramales). Arregla los huecos
y ramales incompletos (L4 ramal norte, L6 dirección Villa de Aragón, etc.).
No toca `lineas.json` (la app sigue usando sus estaciones).

## Cómo correrlo

1. Necesitas Python 3 (nada de `pip`, solo la librería estándar).
2. Desde esta carpeta (`herramientas/`):

   ```bash
   python generar_lineas.py
   ```

   En Windows también sirve `py generar_lineas.py`.

3. Verás un resumen por línea, por ejemplo:

   ```
   L1: 2 tramos, 46 puntos
   L4: 11 tramos, 159 puntos
   ...
   Listo -> ...\app\src\main\assets\segmentos.json
   ```

4. Recompila la app en Android Studio para ver el trazado nuevo.

El script guarda un respaldo del archivo anterior como `segmentos.json.bak`.

## Si la descarga falla

Google a veces limita la descarga automática. Si eso pasa:

1. Abre en el navegador:
   `https://www.google.com/maps/d/u/0/kml?mid=1K850htztydKaWRlEgz7Qh5uLN8HIF8s&forcekml=1`
2. Guarda el resultado como `doc.kml` dentro de esta carpeta `herramientas/`.
3. Vuelve a correr `python generar_lineas.py` — usará el archivo local.

## Notas

- Excluye automáticamente la carpeta "Maquinas MB" (máquinas de recarga).
- Cada línea trae varios tramos (ida/vuelta y ramales); la app dibuja todos,
  por eso ya no hay huecos.
- El color de cada línea lo sigue tomando la app de `lineas.json`.
- Las **rutas mixtas**, el degradado diagonal de las unidades y el filtro de
  destinos por estación son lógica de la app (Ruta / RutasRepository /
  MapFragment), no del trazado; se trabajan aparte una vez que el trazado base
  quede completo con este script.
