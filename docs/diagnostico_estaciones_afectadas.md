# Diagnóstico: por qué una estación aparece (o no) en gris en el mapa

Procedimiento para distinguir, SIN adivinar, cuál de las 4 causas posibles explica el color de
una estación concreta. No automatiza nada (en particular, no limpia `simulado` por sí solo): es
una guía para que una persona (o una sesión futura con acceso al dispositivo/backend en vivo)
decida con evidencia, no con suposición.

Contexto: esto documenta el comportamiento real del código en la rama `pruebas` después de los
commits de este round (ver `ManifestacionesService.itemsEstaciones()`,
`MapFragment.sirveRutaMixtaAlterna()`/`mismaParadaFisica()`, el candado de red de
`ManifestacionesService` y `Manifestaciones.origenesBloqueo()`). No reemplaza leer el código;
resume qué revisar y en qué orden.

**Actualización:** desde la auditoría integral, `Manifestaciones.origenesBloqueo(linea,
estacionNn, movilidadReducida)` responde directamente al Paso 2-3 de abajo sin inspeccionar cada
colección a mano -- devuelve `ORIGEN_AFECTADA`/`ORIGEN_MEXIBUS`/`ORIGEN_POR_SENTIDO`/
`ORIGEN_POR_SENTIDO_MR`/`ORIGEN_SIMULADO` según cuáles fuentes bloquean esa clave AHORA MISMO. Es
de solo lectura: no cambia el color del marcador ni el ruteo, solo expone la causa.

## Las 4 causas y cómo se ven

| Causa | Qué la mantiene viva | Cómo se limpia |
|---|---|---|
| **A. Afectación real vigente** | `Manifestaciones.afectadas`/`porSentido`: se reemplazan COMPLETOS cada ciclo de scraping (`Manifestaciones.actualizar()`, `ManifestacionesService` cada 60 s). | Sola, en el próximo ciclo, si la fila deja de estar en el feed. |
| **B. Simulación (`simulado`) sin limpiar** | `Manifestaciones.simulado`: el panel de pruebas (`ConfiguracionFragment.configurarSimulador()`). | SOLO con el botón "Quitar simulación" → `Manifestaciones.limpiarSimulado()`. El refresco del feed real NUNCA la toca. |
| **C. Colisión de nombre** | Un nombre real coincide con otro (mismo corredor homónimo entre líneas, o palabra compartida entre nombres compuestos). Corregido en este round para el caso `nEst.contains(nn)` de `ManifestacionesService`; sigue siendo posible en otras funciones que comparan por subcadena (`afectacionEstacion()`, `razonCierre()`, `idxEstacion()` -- ver limitaciones). | Depende de la fila de origen: si esa fila desaparece, la colisión desaparece con ella (causa A) o persiste si viene de `simulado` (causa B). |
| **D. Marcador no redibujado** | El ícono de un marcador ya creado no refleja el estado lógico actual. | `MapFragment.actualizarEstadoEstaciones()` debería corregirlo solo, en el siguiente ciclo guiado por `Manifestaciones.actualizado()`. |

## Procedimiento (en orden, cada paso descarta una causa)

### Paso 1 — ¿Sigue bloqueada después de un refresco completo del feed real?

1. Fuerza (o espera) un ciclo completo de `ManifestacionesService` (cada 60 s) sin tocar el panel
   de pruebas.
2. Si la estación **deja** de estar gris → la causa era **A** (afectación real que ya expiró) y
   ya está resuelta. Fin.
3. Si **sigue** gris → pasa al paso 2. Esto ya descarta que sea D por sí solo: si fuera solo un
   problema de redibujado, el siguiente ciclo (que SÍ llama a `actualizarEstadoEstaciones()`)
   lo habría corregido.

### Paso 2 — ¿Hay algo en `simulado`?

1. Abre el panel oculto de pruebas (`ConfiguracionFragment` → Modo personalizado).
2. Pulsa **"Quitar simulación"** (`Manifestaciones.limpiarSimulado()`).
3. Si la estación se aclara → la causa era **B** (simulación que nadie había limpiado). Esto es
   el comportamiento DISEÑADO (la simulación no se limpia sola a propósito, para poder probar
   el planificador sin que el feed real la pise) -- no es un defecto; solo hay que acordarse de
   limpiarla.
4. Si sigue gris → pasa al paso 3.

### Paso 3 — ¿La clave bloqueada corresponde realmente a esta estación, o a otra con nombre
parecido?

1. Mira qué fila(s) activa(s) de `Manifestaciones.lista()` mencionan un nombre que, al
   normalizar (`Planificador.norm`), produzca la MISMA clave `linea|estacion` que la estación
   gris (`Planificador.claveTerminal(linea) + "|" + Planificador.norm(nombre)`).
2. Si el `lugar` de esa fila es un nombre MÁS LARGO que contiene el nombre de la estación gris
   como subcadena de palabras completas (p. ej. "Teatro de los Insurgentes" conteniendo
   "Insurgentes") → es la causa **C**, y debería estar corregida por
   `ManifestacionesService.itemsEstaciones()` en la columna "estaciones" de la tabla de Estado
   del Servicio. Si el cruce viene de OTRA función (tarjeta de estación vía
   `Manifestaciones.afectacionEstacion()`/`razonCierre()`, o de un tramo "Servicio de A a B" vía
   `ManifestacionesService.idxEstacion()`), sigue siendo una colisión por subcadena **no
   corregida en este round** (ver Limitaciones abajo) -- documenta la fila exacta antes de tocar
   nada.
3. Si el `lugar` de la fila activa coincide EXACTO (no por subcadena) con el nombre de la
   estación gris, y la línea también coincide (`claveTerminal` igual) → es una afectación real
   legítima, no una colisión. No hay defecto; el marcador está correctamente gris.

### Paso 4 — ¿La estación tiene un recorrido mixto (RutasMixtas) que debería eximirla, y no lo
hace (o al revés)?

Solo si el marcador debería estar EXENTO (otro recorrido real sigue sirviendo esa estación) y no
lo está, o viceversa:

1. Revisa `MapFragment.sirveRutaMixtaAlterna()`: desde este round, exige nombre exacto Y
   `mismaParadaFisica()` (≤ `RADIO_MISMA_PARADA_M` = 100 m) entre la estación bloqueada y la
   candidata de otra línea en `RutasMixtas.SECUENCIAS`.
2. Si la estación candidata de la otra línea está a más de 100 m (nombres iguales, lugares
   distintos, como "Reforma" en Av. Insurgentes L1 vs. "Reforma" en Paseo de la Reforma L7) →
   NO debe eximirse, y con la corrección de este round ya no se exime.
3. Si crees que SÍ debería eximirse a pesar de estar a más de 100 m (una correspondencia física
   real que el dataset de coordenadas no refleja con precisión), documenta las coordenadas
   exactas de ambos puntos y la distancia calculada antes de ampliar `RADIO_MISMA_PARADA_M` --
   no se amplía a ciegas ni por el nombre de una estación concreta.

### Paso 5 — Si ninguno de los pasos anteriores lo explica

Es la causa **D**: revisa si `em.marker != null` para esa estación en el momento del ciclo
(`MapFragment.actualizarEstadoEstaciones()` solo repinta marcadores YA CREADOS -- uno fuera del
viewport/zoom en ese momento no se repinta hasta que `crearEstacionesVisibles()`/
`crearMexibusVisibles()` lo cree, pero en ese punto ya consulta el estado en vivo, así que debería
nacer correcto). Si encuentras un caso real donde esto falle, documenta el zoom/viewport exacto en
el momento del fallo -- no se ha demostrado ningún caso real de esta causa en este audit.

## Limitaciones conocidas, NO corregidas en este round

- `Manifestaciones.afectacionEstacion()` y `Manifestaciones.razonCierre()` (usadas por la tarjeta
  de estación al tocar un marcador, `CartaEstacion.java`/`PlanificadorFragment.java`) siguen
  comparando por subcadena (`contains()`), no por igualdad exacta. Pueden mostrar en la tarjeta
  información de una fila que en realidad es de OTRA estación con nombre parecido (p. ej. el
  elevador "Escaleras Insurgentes" aparece en la tarjeta de "Insurgentes" por este motivo). Esto
  NO afecta el color del marcador (que depende solo de `Manifestaciones.bloqueadas()`), pero sí
  puede mostrar una razón equivocada en la tarjeta. No se tocó porque no hay evidencia de que
  afecte al oscurecimiento del mapa, y cambiarlo sin esa evidencia sería "maquillar" un síntoma
  distinto del que se pidió corregir.
- `ManifestacionesService.idxEstacion()` (usada por `bloquearTramos()`/`cortarAlrededor()` para
  resolver los extremos de un tramo "Servicio de A a B") también compara por subcadena en ambos
  sentidos (`nn.contains(q) || q.contains(nn)`). En los casos reales revisados en este audit (texto
  con cola añadida tipo "...el caminero retraso en el servicio", o un alias coloquial como
  "Glorieta de Insurgentes" para la estación "Insurgentes") el resultado coincidió por suerte con
  la estación correcta, así que no hay un caso DEMOSTRADO de resultado equivocado -- no se tocó,
  siguiendo la instrucción de no corregir sin causa demostrada. Si aparece un caso real donde
  resuelva el extremo equivocado, documentarlo con el texto exacto de la fila antes de tocarlo.
- El caso "Deportivo 18 de Marzo → Potrero" (varias estaciones grises en ese tramo) no se pudo
  atribuir a una fila concreta desde este entorno: no hay acceso al feed en vivo ni al estado de
  `simulado` en un dispositivo real desde aquí. Sigue el Paso 1-2 de este procedimiento en el
  dispositivo para atribuirlo.
