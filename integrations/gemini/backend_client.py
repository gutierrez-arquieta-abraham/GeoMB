"""Cliente HTTP al backend REAL de GeoMB (mismo que usa la app Android, ver Backend.java):
posiciones en vivo de las unidades y el estado de afectaciones (Metrobús + Mexibús +
avisos manuales, todo fusionado en un solo JSON). Con el mismo failover que la app:
si el servidor principal no responde, cae al de respaldo (Railway).
"""
from __future__ import annotations

import httpx

from . import config


async def _get_con_failover(path: str) -> dict:
    async with httpx.AsyncClient(timeout=config.HTTP_TIMEOUT_S) as client:
        try:
            r = await client.get(config.GEOMB_BASE_URL + path)
            r.raise_for_status()
            return r.json()
        except Exception:
            r = await client.get(config.GEOMB_FALLBACK_URL + path)
            r.raise_for_status()
            return r.json()


async def obtener_vehiculos() -> list[dict]:
    """Posiciones en vivo de todas las unidades (label/id, lat, lon, line, destino, etc.)."""
    data = await _get_con_failover(config.PATH_VEHICLES)
    return data if isinstance(data, list) else data.get("vehicles", data.get("data", []))


async def obtener_afectaciones() -> list[dict]:
    """Afectaciones actuales (Metrobús + Mexibús + avisos manuales), cada una con
    linea/estado/lugar/info -- mismo esquema que consume AfectacionesMexibus.java."""
    data = await _get_con_failover(config.PATH_AFECT_MXB)
    return data.get("afectaciones", []) if isinstance(data, dict) else data
