"""Contrato comun de una fuente de posts."""
from __future__ import annotations

import abc

from ..models import RawPost


class Source(abc.ABC):
    #: nombre corto de la plataforma ("x", "facebook", "mock")
    plataforma: str = "base"

    def __init__(self, cuentas: list[dict]):
        self.cuentas = cuentas

    @abc.abstractmethod
    async def obtener(self) -> list[RawPost]:
        """Devuelve los posts recientes de todas las cuentas de esta plataforma."""
        raise NotImplementedError
