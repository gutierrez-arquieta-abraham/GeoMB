"""Límite de uso diario POR DISPOSITIVO -- sin esto, un solo usuario (o un abuso) puede
consumir toda la cuota/presupuesto de la API de Gemini. Usa SQLite (nada que instalar ni
otro servicio que correr, igual de "ligero" que el resto del backend de GeoMB).
"""
from __future__ import annotations

import datetime
import sqlite3
import threading
from pathlib import Path

from . import config

_DB_PATH = Path(config.__file__).resolve().parent / "uso.db"
_lock = threading.Lock()


def _conexion() -> sqlite3.Connection:
    con = sqlite3.connect(_DB_PATH, timeout=5)
    con.execute(
        "CREATE TABLE IF NOT EXISTS uso_diario ("
        "  device_id TEXT NOT NULL,"
        "  dia TEXT NOT NULL,"  # 'YYYY-MM-DD' (UTC)
        "  mensajes INTEGER NOT NULL DEFAULT 0,"
        "  PRIMARY KEY (device_id, dia)"
        ")"
    )
    return con


def _hoy() -> str:
    return datetime.datetime.utcnow().strftime("%Y-%m-%d")


def mensajes_usados_hoy(device_id: str) -> int:
    with _lock, _conexion() as con:
        row = con.execute(
            "SELECT mensajes FROM uso_diario WHERE device_id = ? AND dia = ?",
            (device_id, _hoy()),
        ).fetchone()
        return row[0] if row else 0


def puede_enviar(device_id: str) -> bool:
    return mensajes_usados_hoy(device_id) < config.LIMITE_MENSAJES_DIA


def registrar_mensaje(device_id: str) -> int:
    """Suma un mensaje al contador de hoy para este dispositivo y devuelve el nuevo total."""
    with _lock, _conexion() as con:
        con.execute(
            "INSERT INTO uso_diario (device_id, dia, mensajes) VALUES (?, ?, 1) "
            "ON CONFLICT(device_id, dia) DO UPDATE SET mensajes = mensajes + 1",
            (device_id, _hoy()),
        )
        con.commit()
        row = con.execute(
            "SELECT mensajes FROM uso_diario WHERE device_id = ? AND dia = ?",
            (device_id, _hoy()),
        ).fetchone()
        return row[0] if row else 1


def limpiar_dias_viejos(dias_a_conservar: int = 3) -> int:
    """Borra contadores de días anteriores (mantenimiento simple, llamar de vez en cuando --
    p. ej. una vez al día desde un cron o al arrancar el servicio)."""
    corte = (datetime.datetime.utcnow() - datetime.timedelta(days=dias_a_conservar)).strftime("%Y-%m-%d")
    with _lock, _conexion() as con:
        cur = con.execute("DELETE FROM uso_diario WHERE dia < ?", (corte,))
        con.commit()
        return cur.rowcount
