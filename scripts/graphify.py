#!/usr/bin/env python3
"""Genera docs/INDICE_CODIGO.md: un índice de métodos (nombre + línea) de los
archivos .java más grandes del proyecto, para consultarlo en vez de releer el
archivo completo cada vez que se busca una función.

Regenerar después de cambios grandes de estructura:
    python3 scripts/graphify.py
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "app/src/main/java/com/memegrados/GeoMB"
OUT = REPO / "docs/INDICE_CODIGO.md"

# Archivos grandes/con más acoplamiento cruzado -- los que de verdad cuestan
# contexto al releer completos. Ajustar esta lista si el proyecto crece.
ARCHIVOS = [
    "Planificador.java",
    "MapFragment.java",
    "RecorridoService.java",
    "ManifestacionesService.java",
    "PlanificadorFragment.java",
    "Locuciones.java",
    "GtfsRepository.java",
    "Linea.java",
]

METODO_RE = re.compile(
    r"^\s{4}(?:(?:public|private|protected|static|final|synchronized|abstract|native)\s+)+"
    r"[\w<>\[\],.? ]+?\s+(\w+)\s*\([^;]*\)\s*(?:throws\s+[\w.,\s]+)?\s*\{?\s*$"
)
CAMPO_RE = re.compile(
    r"^\s{4}(?:private|public|protected)?\s*static\s+final\s+[\w<>\[\],. ]+?\s+([A-Z][A-Z0-9_]+)\s*="
)
CONTROL = {"if", "for", "while", "switch", "catch", "try", "else", "do", "return", "new"}


def extraer(path: Path):
    metodos, campos = [], []
    for i, linea in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        m = METODO_RE.match(linea)
        if m and m.group(1) not in CONTROL:
            metodos.append((i, linea.strip().rstrip("{").strip()))
            continue
        c = CAMPO_RE.match(linea)
        if c:
            campos.append((i, linea.strip().rstrip("{").strip()))
    return metodos, campos


def main():
    partes = [
        "# Índice de código (generado)\n",
        "Métodos y constantes clave de los archivos más grandes/acoplados, con su línea actual --",
        "para buscar \"¿dónde está X?\" aquí en vez de releer el archivo completo. Puede quedar",
        "desfasado tras refactors grandes: regenerar con `python3 scripts/graphify.py`.",
        "No reemplaza MEMORIA.md (el porqué); esto es solo el mapa de ubicaciones.\n",
    ]
    for nombre in ARCHIVOS:
        path = SRC / nombre
        if not path.exists():
            continue
        metodos, campos = extraer(path)
        partes.append(f"## {nombre}\n")
        if campos:
            partes.append("**Constantes:**")
            for ln, sig in campos:
                partes.append(f"- `{ln}`: `{sig}`")
            partes.append("")
        partes.append("**Métodos:**")
        for ln, sig in metodos:
            partes.append(f"- `{ln}`: `{sig}`")
        partes.append("")
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text("\n".join(partes) + "\n", encoding="utf-8")
    total_m = sum(1 for _ in open(OUT))
    print(f"ok: {OUT.relative_to(REPO)} ({total_m} líneas)")


if __name__ == "__main__":
    main()
