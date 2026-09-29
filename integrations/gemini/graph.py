"""Planificador de ruta SIMPLIFICADO (grafo + A*) sobre los datos locales.

OJO -- esto NO es un puerto de Planificador.java. Esa clase (miles de líneas) maneja
exprés/ramales/circuitos, correspondencias declaradas a mano, horarios y afectaciones en
vivo. Aquí se construye un grafo simple para que el agente pueda responder "¿cómo llego
de A a B?" con una ruta razonable:

  - Solo líneas TRONCALES: Metrobús 1..7, Mexibús 101..104, Mexicable 201/202.
    Ramales (11X) y exprés (12X) se excluyen a propósito (son subconjuntos de su
    troncal; incluirlos duplicaría estaciones sin aportar rutas realmente distintas
    en este modelo simplificado).
  - Transbordo/correspondencia: dos estaciones de LÍNEAS DISTINTAS con el MISMO nombre
    normalizado y a menos de TRANSFER_MAX_M se enlazan con un costo fijo (penalización
    por caminar + esperar). Esto es una aproximación: la app real declara varias
    correspondencias a mano (nombres distintos, p. ej. "Indios Verdes" Metrobús vs.
    Mexibús) que este modelo simple no conoce.
  - Peso de cada tramo = distancia en línea recta (haversine), NO la ruta real de calles
    ni el tiempo real de viaje.

Para producción, lo ideal es que este backend llame al Planificador real (vía un
endpoint nuevo en la app/backend) en vez de reimplementarlo -- ver README.md.
"""
from __future__ import annotations

import heapq
from dataclasses import dataclass, field
from functools import lru_cache

from . import data_loader as dl

LINEAS_TRONCALES = {1, 2, 3, 4, 5, 6, 7, 101, 102, 103, 104, 201, 202}
TRANSFER_MAX_M = 400.0
TRANSFER_PENALTY_M = 250.0  # "costo" equivalente de caminar + esperar en un transbordo


def _clave(linea: int, nombre_norm: str) -> str:
    return f"{linea}|{nombre_norm}"


@dataclass
class Nodo:
    linea: int
    nombre: str  # nombre de exhibición (sin prefijo MXB/MXC)
    pos: dl.Estacion  # para el heurístico de A* (distancia en línea recta al destino)


@dataclass
class Grafo:
    nodos: dict[str, Nodo] = field(default_factory=dict)
    adyacencia: dict[str, list[tuple[str, float]]] = field(default_factory=dict)

    def agregar_nodo(self, clave: str, nodo: Nodo) -> None:
        self.nodos.setdefault(clave, nodo)
        self.adyacencia.setdefault(clave, [])

    def agregar_arista(self, a: str, b: str, peso: float) -> None:
        self.adyacencia[a].append((b, peso))
        self.adyacencia[b].append((a, peso))


@lru_cache(maxsize=1)
def construir_grafo() -> Grafo:
    g = Grafo()
    # 1) nodos + aristas dentro de cada línea troncal
    for linea in dl.todas_las_lineas():
        if linea.numero not in LINEAS_TRONCALES:
            continue
        claves = []
        for e in linea.estaciones:
            k = _clave(linea.numero, dl.normalizar(e.nombre))
            g.agregar_nodo(k, Nodo(linea=linea.numero, nombre=e.nombre, pos=e))
            claves.append((k, e))
        for i in range(len(claves) - 1):
            (ka, ea), (kb, eb) = claves[i], claves[i + 1]
            g.agregar_arista(ka, kb, dl.haversine_m(ea, eb))

    # 2) transbordos: mismo nombre normalizado, línea distinta, cerca físicamente
    por_nombre: dict[str, list[tuple[str, dl.Estacion]]] = {}
    for linea in dl.todas_las_lineas():
        if linea.numero not in LINEAS_TRONCALES:
            continue
        for e in linea.estaciones:
            nn = dl.normalizar(e.nombre)
            k = _clave(linea.numero, nn)
            por_nombre.setdefault(nn, []).append((k, e))
    for nn, ocurrencias in por_nombre.items():
        for i in range(len(ocurrencias)):
            for j in range(i + 1, len(ocurrencias)):
                (ka, ea), (kb, eb) = ocurrencias[i], ocurrencias[j]
                if ka == kb:
                    continue
                if dl.haversine_m(ea, eb) <= TRANSFER_MAX_M:
                    g.agregar_arista(ka, kb, TRANSFER_PENALTY_M)
    return g


@dataclass
class PasoRuta:
    linea: int
    estacion: str
    transbordo: bool  # ¿se cambió de línea al llegar aquí?


def _nodos_de_estacion(g: Grafo, nombre: str) -> list[str]:
    nn = dl.normalizar(nombre)
    return [k for k, nodo in g.nodos.items() if dl.normalizar(nodo.nombre) == nn]


def planificar(origen: str, destino: str) -> dict:
    """A* multi-origen/multi-destino (una estación puede existir en varias líneas). El
    heurístico (distancia en línea recta al destino más cercano de los válidos) nunca
    sobreestima el costo real restante -- todo tramo del grafo es, como mínimo, la línea
    recta entre sus dos extremos (los transbordos solo SUMAN una penalización encima), así
    que sigue garantizando la ruta óptima, igual que Dijkstra, pero explorando menos nodos
    al priorizar los que apuntan geográficamente hacia el destino.

    Devuelve un dict con la secuencia de paradas y un resumen legible, o un error si no se
    encontró alguna de las dos estaciones o no hay ruta conectada entre ellas."""
    g = construir_grafo()
    origenes = _nodos_de_estacion(g, origen)
    destinos = _nodos_de_estacion(g, destino)
    if not origenes:
        return {"error": f"No se encontró la estación de origen '{origen}' en el catálogo local."}
    if not destinos:
        return {"error": f"No se encontró la estación de destino '{destino}' en el catálogo local."}

    destino_posiciones = [g.nodos[k].pos for k in destinos]

    def heuristica(u: str) -> float:
        return min(dl.haversine_m(g.nodos[u].pos, dp) for dp in destino_posiciones)

    dist: dict[str, float] = {k: 0.0 for k in origenes}  # costo REAL acumulado (g), no f
    prev: dict[str, str] = {}
    visitado: set[str] = set()
    cola: list[tuple[float, str]] = [(heuristica(k), k) for k in origenes]
    heapq.heapify(cola)
    destinos_set = set(destinos)

    while cola:
        _f, u = heapq.heappop(cola)
        if u in visitado:
            continue
        visitado.add(u)
        if u in destinos_set:
            destino_final = u
            break
        for v, peso in g.adyacencia.get(u, []):
            nd = dist[u] + peso
            if nd < dist.get(v, float("inf")):
                dist[v] = nd
                prev[v] = u
                heapq.heappush(cola, (nd + heuristica(v), v))
    else:
        return {"error": f"No se encontró una ruta conectada entre '{origen}' y '{destino}' "
                          "con el catálogo simplificado (líneas troncales)."}

    # Reconstruye el camino
    camino = [destino_final]
    while camino[-1] in prev:
        camino.append(prev[camino[-1]])
    camino.reverse()

    pasos: list[PasoRuta] = []
    linea_prev = None
    for k in camino:
        nodo = g.nodos[k]
        transbordo = linea_prev is not None and nodo.linea != linea_prev
        pasos.append(PasoRuta(linea=nodo.linea, estacion=nodo.nombre, transbordo=transbordo))
        linea_prev = nodo.linea

    return {
        "distancia_aprox_m": round(dist[destino_final]),
        "num_transbordos": sum(1 for p in pasos if p.transbordo),
        "pasos": [p.__dict__ for p in pasos],
        "resumen": _resumen(pasos),
    }


def _resumen(pasos: list[PasoRuta]) -> str:
    if not pasos:
        return ""
    tramos = []
    inicio = pasos[0]
    for i in range(1, len(pasos) + 1):
        es_ultimo = i == len(pasos)
        cambia = es_ultimo or pasos[i].linea != inicio.linea
        if cambia:
            fin = pasos[i - 1]
            tramos.append(f"Línea {inicio.linea}: {inicio.estacion} → {fin.estacion}")
            if not es_ultimo:
                inicio = pasos[i]
    return "; luego transbordo a ".join(tramos)
