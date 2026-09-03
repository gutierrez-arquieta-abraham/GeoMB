"""Modelos de datos.

`Row` es EXACTAMENTE el objeto que la app Android espera dentro de {"rows":[...]}:
campos tipo/linea/estacion/direccion/motivo/extra (mismo parser que Metrobus).
"""
from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field

Tipo = Literal["estado", "mantenimiento", "elevador"]


class RawPost(BaseModel):
    """Publicacion cruda tomada de una red social, antes de interpretarla."""
    fuente: str                      # nombre de la cuenta
    plataforma: str                  # "x" | "facebook" | "mock"
    linea_hint: Optional[int] = None # pista de linea segun la cuenta (101..104) o None
    post_id: str                     # id unico del post (para deduplicar)
    texto: str
    url: str = ""
    fecha: Optional[datetime] = None


class Row(BaseModel):
    """Fila de afectacion en el formato que consume la app."""
    tipo: Tipo = "estado"
    linea: str = ""                  # numero de linea Mexibus como texto: "101".."113"
    estacion: str = ""
    direccion: str = ""
    motivo: str = ""
    extra: str = ""                  # periodo/fecha/hora
    # Metadatos utiles (la app los ignora; sirven para depurar / auditar la fuente)
    fuente: str = ""
    url: str = ""
    detectado: str = ""              # ISO timestamp de cuando se detecto


class RowsResponse(BaseModel):
    rows: list[Row] = Field(default_factory=list)
    actualizado: Optional[str] = None
    total: int = 0
