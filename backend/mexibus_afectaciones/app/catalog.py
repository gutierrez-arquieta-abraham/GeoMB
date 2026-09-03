"""Catalogo de lineas y estaciones Mexibus (generado de mexibus.json de la app).

Sirve para reconocer, dentro del texto de un post, a QUE linea y estacion se
refiere una afectacion.
"""
from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Optional

from .config import settings


def norm(s: str) -> str:
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s.lower())).strip()


@dataclass
class Estacion:
    nombre: str
    norm: str


@dataclass
class Linea:
    numero: int
    etiqueta: str          # "1", "1A", "2", ...
    nombre: str
    sistema: str           # troncal|ramal|expres|mexicable
    estaciones: list[Estacion] = field(default_factory=list)


class Catalogo:
    def __init__(self, lineas: list[Linea]):
        self.lineas = lineas
        self._por_numero = {l.numero: l for l in lineas}

    @classmethod
    def cargar(cls, path=None) -> "Catalogo":
        path = path or settings.catalog_path
        data = json.loads(open(path, encoding="utf-8").read())
        lineas = []
        for l in data["lineas"]:
            ests = [Estacion(e["nombre"], e.get("norm") or norm(e["nombre"])) for e in l["estaciones"]]
            lineas.append(Linea(l["numero"], l["etiqueta"], l.get("nombre", ""), l.get("sistema", ""), ests))
        return cls(lineas)

    # ---- deteccion de linea en texto ----
    _RX_LINEA = re.compile(r"\b(?:l[ií]nea|l)\s*([1-4])\s*(a)?\b", re.IGNORECASE)

    def detectar_lineas(self, texto: str) -> list[int]:
        """Numeros de linea troncal/ramal mencionados: L1->101, L2A->112, etc."""
        out: list[int] = []
        for m in self._RX_LINEA.finditer(texto or ""):
            base = int(m.group(1))
            ramal = bool(m.group(2))
            num = (110 + base) if ramal else (100 + base)   # L2A->112, L2->102
            if num not in out:
                out.append(num)
        return out

    def detectar_estaciones(self, texto: str, numero: Optional[int] = None) -> list[str]:
        """Nombres de estacion mencionados en el texto (opcionalmente acotado a una linea)."""
        n = norm(texto)
        lineas = [self._por_numero[numero]] if numero in self._por_numero else self.lineas
        vistos: list[str] = []
        for l in lineas:
            for e in l.estaciones:
                if len(e.norm) < 4:
                    continue
                # coincidencia por palabra completa para evitar falsos positivos cortos
                if re.search(rf"\b{re.escape(e.norm)}\b", n) and e.nombre not in vistos:
                    vistos.append(e.nombre)
        return vistos

    def linea(self, numero: int) -> Optional[Linea]:
        return self._por_numero.get(numero)
