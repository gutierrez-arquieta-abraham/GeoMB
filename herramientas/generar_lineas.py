#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generar_lineas.py
=================
Regenera  app/src/main/assets/segmentos.json  a partir del KML oficial
"Plano de Sistema de Metrobús" (Google My Maps).

segmentos.json contiene el trazado COMPLETO de cada línea dividido en tramos
(ida/vuelta y ramales), tal como lo dibuja el mapa oficial. Con esto se
arreglan los huecos y ramales que faltaban (L4 ramal norte, L6 dirección
Villa de Aragón, etc.). El archivo lineas.json NO se toca: la app sigue
usando sus estaciones y sólo dibuja el trazado desde segmentos.json.

Uso:
  Requiere sólo Python 3 (nada de pip). Desde esta carpeta (herramientas/):
      python generar_lineas.py     (o  py generar_lineas.py  en Windows)

  Si la descarga falla, abre en el navegador la URL que imprime el script,
  guarda el resultado como  doc.kml  junto a este archivo, y vuelve a correrlo.

Notas:
  - Excluye la carpeta "Maquinas MB" (máquinas de recarga).
  - Los nombres de carpeta del KML son inconsistentes ("Línea 1",
    "Estaciones L4", "Metrobús El Rosario-Villa de Aragón", ...); el mapeo a
    número de línea contempla todos esos casos.
"""

import json
import math
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

KML_URL = ("https://www.google.com/maps/d/u/0/kml"
           "?mid=1K850htztydKaWRlEgz7Qh5uLN8HIF8s&forcekml=1")
KML_LOCAL = "doc.kml"

KMLNS = "{http://www.opengis.net/kml/2.2}"
M_LAT = 111320.0
COS_LAT = math.cos(math.radians(19.4))
EPS = 5.0  # metros de simplificación por tramo (menor = curvas más finas)


def descargar_kml() -> str:
    aqui = os.path.dirname(os.path.abspath(__file__))
    local = os.path.join(aqui, KML_LOCAL)
    if os.path.exists(local):
        with open(local, encoding="utf-8") as f:
            data = f.read()
        if "<LineString" in data:
            print(f"Usando KML local: {local}")
            return data
    print("Descargando KML oficial (puede tardar unos segundos)...")
    req = urllib.request.Request(KML_URL, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read().decode("utf-8", "replace")


def nombre(el) -> str:
    for c in el:
        if c.tag.endswith("}name") and c.text:
            return c.text.strip()
    return ""


def line_num(nom: str):
    low = nom.lower()
    if "maquina" in low or "máquina" in low:
        return None
    m = re.search(r"l[ií]nea\s*(\d+)", nom, re.I) or re.search(r"\bl\s*(\d+)\b", nom, re.I)
    if m:
        return int(m.group(1))
    if "rosario" in low and "arag" in low:   # carpeta de ruta de la L6
        return 6
    return None


def parse_coords(txt: str):
    pts = []
    for tok in txt.split():
        p = tok.split(",")
        if len(p) >= 2:
            try:
                lon, lat = float(p[0]), float(p[1])
            except ValueError:
                continue
            pts.append([round(lat, 5), round(lon, 5)])
    return pts


def _perp(p, a, b):
    ax, ay = a[1] * M_LAT * COS_LAT, a[0] * M_LAT
    bx, by = b[1] * M_LAT * COS_LAT, b[0] * M_LAT
    px, py = p[1] * M_LAT * COS_LAT, p[0] * M_LAT
    dx, dy = bx - ax, by - ay
    L = math.hypot(dx, dy)
    if L == 0:
        return math.hypot(px - ax, py - ay)
    return abs((px - ax) * dy - (py - ay) * dx) / L


def dp(pts, eps=EPS):
    if len(pts) < 3:
        return pts
    dmax, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        d = _perp(pts[i], pts[0], pts[-1])
        if d > dmax:
            dmax, idx = d, i
    if dmax > eps:
        return dp(pts[:idx + 1], eps)[:-1] + dp(pts[idx:], eps)
    return [pts[0], pts[-1]]


def main():
    kml = descargar_kml()
    try:
        root = ET.fromstring(kml.encode("utf-8"))
    except ET.ParseError as e:
        print(f"ERROR: KML inválido ({e}). Guarda doc.kml a mano y reintenta.")
        sys.exit(1)

    por_linea = {}
    for folder in root.iter(KMLNS + "Folder"):
        num = line_num(nombre(folder))
        if not num:
            continue
        segs = por_linea.setdefault(num, [])
        for pm in folder.findall(KMLNS + "Placemark"):
            if "maquina" in nombre(pm).lower() or "máquina" in nombre(pm).lower():
                continue
            ls = pm.find(".//" + KMLNS + "LineString/" + KMLNS + "coordinates")
            if ls is not None and ls.text:
                co = parse_coords(ls.text)
                if len(co) >= 2:
                    segs.append(dp(co, EPS))

    salida = [{"numero": n, "segmentos": por_linea[n]}
              for n in sorted(por_linea) if por_linea[n]]

    if not salida:
        print("ERROR: no se encontraron trazados en el KML.")
        sys.exit(1)

    aqui = os.path.dirname(os.path.abspath(__file__))
    destino = os.path.normpath(os.path.join(
        aqui, "..", "app", "src", "main", "assets", "segmentos.json"))
    if os.path.exists(destino):
        with open(destino, encoding="utf-8") as a, open(destino + ".bak", "w", encoding="utf-8") as b:
            b.write(a.read())
    with open(destino, "w", encoding="utf-8") as f:
        json.dump(salida, f, ensure_ascii=False, separators=(",", ":"))

    for l in salida:
        pts = sum(len(s) for s in l["segmentos"])
        print(f"L{l['numero']}: {len(l['segmentos'])} tramos, {pts} puntos")
    print(f"\nListo -> {destino}")
    print("Recompila la app para ver el trazado oficial completo.")


if __name__ == "__main__":
    main()
