#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Empareja, por NOMBRE, cada estación de estaciones.json con su icono en la
carpeta MB (Downloads/MB/1..7), lo redimensiona a 96 px y lo guarda en
res/drawable con el nombre ic_est_L_N que ya usa la app.
Uso:  python mapear_iconos.py <MB_dir> <drawable_dir> <estaciones.json> [linea]
"""
import os, re, sys, json, glob, unicodedata
from difflib import get_close_matches
from PIL import Image

MB = sys.argv[1]; DRAW = sys.argv[2]; EST = sys.argv[3]
solo = int(sys.argv[4]) if len(sys.argv) > 4 else 0

def norm(s):
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    s = re.sub(r'\bmb\s*l?\d+\b', '', s.lower())
    return re.sub(r'[^a-z0-9]+', ' ', s).strip()

# variante de ARCHIVO -> variante de estación (KML)
ALIAS = {
 'polyforum':'poliforum','c u':'ciudad universitaria','c c u':'ccu','doctor galvez':'dr galvez',
 'corregidoria':'corregidora','teatro insurgentes':'teatro de los insurgentes','verdadero etiopia':'etiopia',
 'verdadero etiopia 1':'etiopia plaza de la transparencia','calzada de la viga':'la viga',
 'antonio de leon':'general antonio de leon','avenida ipn':'i p n','d 18 de marzo':'deportivo 18 de marzo',
 'hospital la villa':'hospital general la villa','pueblo san juan aragon':'pueblo san juan de aragon',
 'avenida talisman':'av talisman','aeropuerto terminal 1':'1 aeropuerto','aeropuerto terminal 2':'2 aeropuerto',
 'ingeniero eduardo molina':'eduardo molina','mercado sonora':'mercado de sonora','merced':'la merced',
 'mercado de san juan':'mercados san juan','mixhiuca':'mixiuhca','cannaverales':'canaverales',
 'magdalena de las salinas':'magdalena la salinas','314 memorial news divine':'314 memorial new s divine',
 'gertrudis sanchez':'oriente 101','doctor marquez':'dr marquez','alcaldia cuauhtemoc':'delegacion cuauhtemoc',
 'alcaldia venustiano carranza':'venustiano carranza','archivo general de la nacion':'archivo general',
 'alcaldia gustavo a madero':'gustavo a madero',
}
def canon(s):
    n = norm(s); return ALIAS.get(n, n)

# archivos por línea: normName -> ruta
mapa = {}
for L in range(1, 8):
    d = {}
    for f in glob.glob(os.path.join(MB, str(L), "*.png")):
        b = os.path.basename(f)[:-4]
        mo = re.match(r'^\d+\s+(.*)$', b)
        nm = canon(mo.group(1)) if mo else canon(b)
        if nm and nm not in d:
            d[nm] = f
    mapa[L] = d

est = json.load(open(EST, encoding='utf-8'))
ok = 0; sin = []
for l in est['lineas']:
    L = l['numero']
    if solo and L != solo:
        continue
    d = mapa[L]
    for e in l['estaciones']:
        nn = canon(e['n'])
        ruta = d.get(nn)
        if not ruta:
            cm = get_close_matches(nn, list(d.keys()), n=1, cutoff=0.86)
            ruta = d[cm[0]] if cm else None
        if not ruta:
            sin.append(f"L{L}: {e['n']}"); continue
        try:
            im = Image.open(ruta).convert("RGBA")
            im.thumbnail((96, 96), Image.BILINEAR)
            im.save(os.path.join(DRAW, e['icono'] + ".png"), optimize=True)
            ok += 1
        except Exception as ex:
            sin.append(f"L{L}: {e['n']} (ERR {ex})")

print("copiados+redimensionados:", ok)
print("sin match (quedan como estaban):", len(sin))
for s in sin:
    print("  ", s)
