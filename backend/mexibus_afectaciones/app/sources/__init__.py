"""Fuentes de posts (conectores por plataforma)."""
from __future__ import annotations

import json

from ..config import settings
from .base import Source
from .facebook import FacebookSource
from .mock import MockSource
from .twitter_free import XFreeSource
from .twitter_x import XSource


def cargar_cuentas() -> list[dict]:
    data = json.loads(open(settings.accounts_path, encoding="utf-8").read())
    return [a for a in data.get("accounts", []) if a.get("enabled", True)]


def construir_fuentes() -> list[Source]:
    """Instancia las fuentes activas segun credenciales disponibles.

    - Si MXB_USAR_MOCK=true -> solo la fuente mock (para probar sin credenciales).
    - X requiere MXB_X_BEARER_TOKEN; Facebook requiere MXB_FB_PAGE_ACCESS_TOKEN.
      Sin token, esa plataforma se omite (log de advertencia).
    """
    if settings.usar_mock:
        return [MockSource(cargar_cuentas())]

    cuentas = cargar_cuentas()
    fuentes: list[Source] = []
    x_cuentas = [c for c in cuentas if c["platform"] == "x"]
    fb_cuentas = [c for c in cuentas if c["platform"] == "facebook"]

    if x_cuentas:
        if settings.x_bearer_token.strip():
            fuentes.append(XSource(x_cuentas))          # oficial (recomendado)
        elif settings.x_free:
            fuentes.append(XFreeSource(x_cuentas))      # sin costo, no oficial (Nitter/sindicacion)
    if fb_cuentas:
        fuentes.append(FacebookSource(fb_cuentas))
    return fuentes
