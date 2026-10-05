"""Catálogo de marca/modelo por número económico -- MISMA fuente de verdad que ya usa Android
(Modelos.java / Config.MODELOS_URL): un Google Sheet publicado como CSV ("Publicar en la
web"), público y sin autenticación -- no es una credencial, ya viaja en el código fuente
abierto de la app (ver Config.java). No se duplica el catálogo a mano ni se mete al prompt de
Gemini: esta función solo lee la MISMA hoja remota, bajo demanda, con un caché corto en memoria.

Formato del CSV (idéntico al que ya parsea Modelos.java#parse): columnas ubicadas por
ENCABEZADO para tolerar que cambie el orden -- economico, empresa, marca, modelo, imagen,
foto/url, credito(s). Si no hay encabezado reconocible, cae al orden posicional
economico,marca,modelo,empresa,imagen,credito (mismo criterio que el lado Android).
"""
from __future__ import annotations

import csv
import io
import time
from typing import Any

import httpx

from . import config

# El catálogo es colaborativo y cambia poco -- un TTL corto evita pegarle a Google Sheets en
# cada mensaje del chat sin dejar de reflejar ediciones recientes en un tiempo razonable.
_TTL_S = 300.0
_cache: dict[str, Any] = {"ts": 0.0, "tabla": {}}


def _normalizar_encabezado(s: str) -> str:
    return "".join(c for c in s.strip().lower() if c.isalnum())


async def _descargar_csv() -> str:
    async with httpx.AsyncClient(timeout=config.HTTP_TIMEOUT_S) as client:
        r = await client.get(config.MODELOS_URL)
        r.raise_for_status()
        return r.text


def _valor(fila: list[str], idx: dict[str, int], clave: str) -> str:
    i = idx.get(clave, -1)
    return fila[i].strip() if 0 <= i < len(fila) else ""


def _parsear(texto_csv: str) -> dict[str, dict[str, str]]:
    """economico (str, solo dígitos) -> {"empresa","marca","modelo","imagen","credito"}."""
    filas = list(csv.reader(io.StringIO(texto_csv)))
    tabla: dict[str, dict[str, str]] = {}
    if not filas:
        return tabla

    idx = {"economico": 0, "marca": 1, "modelo": 2, "empresa": 3, "imagen": 4, "credito": 5}
    primera = [_normalizar_encabezado(c) for c in filas[0]]
    primera_celda = primera[0] if primera else ""
    hay_encabezado = not primera_celda.isdigit()
    inicio = 0
    if hay_encabezado:
        encontrados = {"economico": -1, "empresa": -1, "marca": -1, "modelo": -1, "imagen": -1, "credito": -1}
        for i, h in enumerate(primera):
            if h.startswith("eco"):
                encontrados["economico"] = i
            elif h.startswith("empres"):
                encontrados["empresa"] = i
            elif h.startswith("marca"):
                encontrados["marca"] = i
            elif h.startswith("modelo"):
                encontrados["modelo"] = i
            elif h.startswith("imag") or h.startswith("foto") or h == "url":
                encontrados["imagen"] = i
            elif h.startswith("cred"):
                encontrados["credito"] = i
        if encontrados["economico"] < 0:
            encontrados["economico"] = 0
        if encontrados["marca"] < 0 and encontrados["modelo"] < 0:
            encontrados.update({"marca": 1, "modelo": 2, "empresa": 3, "imagen": 4, "credito": 5})
        idx = encontrados
        inicio = 1

    for fila in filas[inicio:]:
        if not fila or not fila[0].strip() or fila[0].strip().startswith("#"):
            continue
        eco = "".join(c for c in _valor(fila, idx, "economico") if c.isdigit())
        if not eco:
            continue
        tabla[eco] = {
            "empresa": _valor(fila, idx, "empresa"),
            "marca": _valor(fila, idx, "marca"),
            "modelo": _valor(fila, idx, "modelo"),
            "imagen": _valor(fila, idx, "imagen"),
            "credito": _valor(fila, idx, "credito"),
        }
    return tabla


async def obtener_tabla() -> dict[str, dict[str, str]]:
    """Catálogo completo, cacheado _TTL_S segundos. De mejor esfuerzo: si la descarga falla
    (red, formato inesperado), sirve el caché anterior (vacío la primera vez) en vez de
    lanzar -- un catálogo sin datos es un resultado válido, nunca se inventa información."""
    ahora = time.monotonic()
    if ahora - _cache["ts"] < _TTL_S and _cache["tabla"]:
        return _cache["tabla"]
    try:
        tabla = _parsear(await _descargar_csv())
    except Exception:
        return _cache["tabla"]
    if tabla:
        _cache["tabla"] = tabla
        _cache["ts"] = ahora
    return _cache["tabla"]


async def ficha(economico: str) -> dict[str, str] | None:
    """empresa/marca/modelo/imagen/credito del económico dado, o None si no está catalogado."""
    eco = "".join(c for c in (economico or "") if c.isdigit())
    if not eco:
        return None
    return (await obtener_tabla()).get(eco)
