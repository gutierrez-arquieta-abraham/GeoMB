"""Carga los MISMOS assets que empaqueta la app Android (lineas.json = Metrobús,
mexibus.json = Mexibús/Mexicable) para tener el catálogo de líneas/estaciones sin
depender de ningún servidor.

Formato de cada línea (igual en ambos archivos):
    {"numero": int, "nombre": str, "color": int, "ruta": ..., "estaciones": [
        {"n": str, "lat": float, "lon": float, "icono": str?}, ...
    ]}

Numeración (ver CLAUDE.md): Metrobús 1..7; Mexibús 10X ordinario / 11X ramales /
12X exprés; Mexicable 20X.
"""
from __future__ import annotations

import json
import math
import unicodedata
import re
from dataclasses import dataclass
from functools import lru_cache
from typing import Optional

from . import config


@dataclass(frozen=True)
class Estacion:
    nombre: str
    lat: float
    lon: float


@dataclass(frozen=True)
class Linea:
    numero: int
    nombre: str
    estaciones: tuple[Estacion, ...]


def normalizar(texto: str) -> str:
    """Sin acentos, minúsculas, sin puntuación -- mismo criterio que Planificador.norm()
    en la app Java, para que las búsquedas de estación toleren tildes/mayúsculas distintas."""
    if not texto:
        return ""
    t = unicodedata.normalize("NFD", texto)
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    t = re.sub(r"[^a-zA-Z0-9]+", " ", t).lower().strip()
    return re.sub(r"\s+", " ", t)


def sin_prefijo_mxb(nombre: str) -> str:
    """Quita el prefijo 'MXB '/'MXC ' (se guarda en los datos pero no se muestra, ver CLAUDE.md)."""
    for pref in ("MXB ", "MXC "):
        if nombre.startswith(pref):
            return nombre[len(pref):]
    return nombre


def haversine_m(a: Estacion, b: Estacion) -> float:
    """Distancia en metros entre dos estaciones (línea recta, no la ruta real de calles)."""
    r = 6371000.0
    p1, p2 = math.radians(a.lat), math.radians(b.lat)
    dphi = math.radians(b.lat - a.lat)
    dlmb = math.radians(b.lon - a.lon)
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def _cargar_archivo(ruta) -> list[Linea]:
    if not ruta.exists():
        return []
    with open(ruta, encoding="utf-8") as f:
        data = json.load(f)
    lineas = []
    for l in data.get("lineas", []):
        estaciones = tuple(
            Estacion(nombre=sin_prefijo_mxb(e["n"]), lat=e["lat"], lon=e["lon"])
            for e in l.get("estaciones", [])
            if "n" in e and "lat" in e and "lon" in e
        )
        lineas.append(Linea(numero=l["numero"], nombre=l["nombre"], estaciones=estaciones))
    return lineas


@lru_cache(maxsize=1)
def todas_las_lineas() -> tuple[Linea, ...]:
    """Metrobús (lineas.json) + Mexibús/Mexicable (mexibus.json), cacheado en memoria."""
    return tuple(_cargar_archivo(config.LINEAS_JSON) + _cargar_archivo(config.MEXIBUS_JSON))


def linea_por_numero(numero: int) -> Optional[Linea]:
    for l in todas_las_lineas():
        if l.numero == numero:
            return l
    return None


def buscar_lineas_por_nombre(consulta: str) -> list[Linea]:
    """Líneas cuyo nombre contiene la consulta (normalizada), p. ej. 'l2' o 'mexibus l2'."""
    nq = normalizar(consulta)
    return [l for l in todas_las_lineas() if nq in normalizar(l.nombre)]


_PREFIJOS_SISTEMA = {
    "metrobus": 0,
    "mexibus": 100,
    "mexibus_ramal": 110,
    "mexibus_expres": 120,
    "mexicable": 200,
}


def resolver_linea_id(identificador: str) -> Optional[int]:
    """Convierte un identificador '<sistema>:<numero>' (el formato que se le pide a Gemini
    en el esquema de la herramienta) al número INTERNO usado por lineas.json/mexibus.json.
    Ejemplos: 'metrobus:2' -> 2, 'mexibus:3' -> 103, 'mexibus_expres:2' -> 122,
    'mexicable:1' -> 201. None si el formato o el sistema no se reconoce."""
    if not identificador or ":" not in identificador:
        return None
    sistema, _, numero_str = identificador.partition(":")
    base = _PREFIJOS_SISTEMA.get(sistema.strip().lower())
    if base is None:
        return None
    try:
        numero = int(numero_str.strip())
    except ValueError:
        return None
    return base + numero if base else numero


def buscar_estaciones(consulta: str, limite: int = 15) -> list[tuple[Linea, Estacion]]:
    """Estaciones (con su línea) cuyo nombre contiene la consulta (normalizada, tolera
    tildes/mayúsculas). Puede devolver la misma estación física en varias líneas."""
    nq = normalizar(consulta)
    if len(nq) < 2:
        return []
    resultados: list[tuple[Linea, Estacion]] = []
    for l in todas_las_lineas():
        for e in l.estaciones:
            if nq in normalizar(e.nombre):
                resultados.append((l, e))
                if len(resultados) >= limite:
                    return resultados
    return resultados
