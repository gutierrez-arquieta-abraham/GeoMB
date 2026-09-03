"""Fuente de ejemplo (mock) para probar el servicio sin credenciales.

Genera posts representativos de como se ven los avisos oficiales, para validar el
parser y el endpoint end-to-end. Actívala con MXB_USAR_MOCK=true (por defecto).
"""
from __future__ import annotations

from datetime import datetime, timezone

from ..models import RawPost
from .base import Source

EJEMPLOS = [
    ("MexibusInforma", None,
     "#MexibusInforma Debido a una manifestación sobre Av. Central, la Línea 4 "
     "presenta servicio limitado dirección UMB Tecámac. Estaciones afectadas: "
     "Cerro Gordo y Santa Clara. 14:30 hrs."),
    ("Mexibus Linea 2 (X)", 102,
     "Buenas tardes, por trabajos de mantenimiento la estación Ecatepec permanecerá "
     "cerrada el 2 de septiembre. Tome precauciones."),
    ("Mexibus Linea 3 (Facebook)", 103,
     "Servicio suspendido temporalmente en Línea 3 por incidente a la altura de "
     "Nezahualcóyotl dirección Chimalhuacán."),
    ("MexibusInforma", None,
     "La Línea 1 opera con normalidad. ¡Buen viaje!"),   # <- normalidad: se descarta
    ("Mexibus Linea 4 (X)", 104,
     "El elevador de la estación Indios Verdes se encuentra fuera de servicio."),
]


class MockSource(Source):
    plataforma = "mock"

    async def obtener(self) -> list[RawPost]:
        ahora = datetime.now(timezone.utc)
        return [
            RawPost(
                fuente=fuente,
                plataforma="mock",
                linea_hint=hint,
                post_id=f"mock:{i}",
                texto=texto,
                url="https://example.org/mock",
                fecha=ahora,
            )
            for i, (fuente, hint, texto) in enumerate(EJEMPLOS)
        ]
