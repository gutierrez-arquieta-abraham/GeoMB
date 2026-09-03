"""Interpreta el texto de un post oficial y produce filas de afectacion (Row).

Heuristica por palabras clave + catalogo de estaciones. No pretende ser perfecta:
marca las afectaciones probables; el objetivo es alimentar la tabla de estado de
la app. Es facil de afinar agregando patrones.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone

from .catalog import Catalogo, norm
from .models import RawPost, Row

# --- Palabras clave por tipo (orden de prioridad) ---
KW_MANTENIMIENTO = ["mantenimiento", "cierre", "cerrada", "cerrado", "obras", "clausura"]
KW_ELEVADOR = ["elevador", "ascensor"]
# 'estado' = afectacion al servicio (lo mas comun en redes)
KW_ESTADO = [
    "sin servicio", "suspend", "suspension", "retras", "demora", "manifest",
    "bloqueo", "bloquead", "marcha", "planton", "incidente", "afecta",
    "servicio limitado", "sin paso", "no circula", "detenid", "varada",
]
# Frases que indican NORMALIDAD (para descartar el post como afectacion)
KW_NORMAL = [
    "servicio regular", "sin novedad", "opera con normalidad", "normalidad",
    "restablec", "reanud", "servicio normal", "buen viaje", "buenos dias",
]

RX_DIRECCION = re.compile(
    r"direcci[oó]n\s+(?:a\s+|hacia\s+)?([A-Za-zÁÉÍÓÚÑáéíóúñ0-9 .]{3,40})", re.IGNORECASE
)
RX_HORA = re.compile(r"\b(\d{1,2}:\d{2}\s*(?:a|p)?\.?m?\.?)\b", re.IGNORECASE)
RX_FECHA = re.compile(
    r"\b(\d{1,2}\s+de\s+[a-záéíóú]+(?:\s+de\s+\d{4})?)\b", re.IGNORECASE
)


def _tipo(texto_norm: str) -> str | None:
    if any(k in texto_norm for k in KW_ELEVADOR):
        return "elevador"
    if any(k in texto_norm for k in KW_MANTENIMIENTO):
        return "mantenimiento"
    if any(k in texto_norm for k in KW_ESTADO):
        return "estado"
    return None


def _direccion(texto: str) -> str:
    m = RX_DIRECCION.search(texto or "")
    return m.group(1).strip(" .,") if m else ""


def _extra(texto: str) -> str:
    partes = []
    mf = RX_FECHA.search(texto or "")
    if mf:
        partes.append(mf.group(1))
    mh = RX_HORA.search(texto or "")
    if mh:
        partes.append(mh.group(1))
    return " · ".join(partes)


def _motivo(texto: str) -> str:
    """Primera oracion util del post, recortada."""
    limpio = re.sub(r"https?://\S+", "", texto or "").strip()
    limpio = re.sub(r"\s+", " ", limpio)
    frase = re.split(r"(?<=[.!?])\s", limpio)[0] if limpio else ""
    return frase[:160].strip()


def interpretar(post: RawPost, cat: Catalogo) -> list[Row]:
    texto = post.texto or ""
    tn = norm(texto)
    if not tn:
        return []
    # Descarta posts de normalidad (salvo que tambien mencionen una afectacion clara)
    if any(k in tn for k in KW_NORMAL) and _tipo(tn) is None:
        return []

    tipo = _tipo(tn)
    if tipo is None:
        return []   # no parece afectacion

    # Lineas: primero las mencionadas en el texto; si no, la pista de la cuenta.
    lineas = cat.detectar_lineas(texto)
    if not lineas and post.linea_hint:
        lineas = [post.linea_hint]
    if not lineas:
        return []   # no se puede atribuir a una linea -> se ignora (evita ruido)

    direccion = _direccion(texto)
    extra = _extra(texto)
    motivo = _motivo(texto)
    detectado = (post.fecha or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()

    rows: list[Row] = []
    for numero in lineas:
        estaciones = cat.detectar_estaciones(texto, numero) or [""]
        for est in estaciones:
            rows.append(Row(
                tipo=tipo,
                linea=str(numero),
                estacion=est,
                direccion=direccion,
                motivo=motivo,
                extra=extra,
                fuente=post.fuente,
                url=post.url,
                detectado=detectado,
            ))
    return rows
