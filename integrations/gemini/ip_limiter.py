"""Límite secundario POR IP: freno de emergencia contra un cliente que rota X-Device-ID
artificialmente para saltarse el límite diario por dispositivo (ver ratelimit.py). Es una
ventana deslizante en memoria -- no necesita persistir entre reinicios del proceso.
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

VENTANA_SEGUNDOS = 60 * 60
MAX_POR_IP = 60  # generoso para un usuario real; frena la rotación masiva de device_id

_peticiones: dict[str, deque] = defaultdict(deque)
_lock = threading.Lock()


def permitir(ip: str) -> bool:
    ahora = time.monotonic()
    with _lock:
        cola = _peticiones[ip]
        while cola and ahora - cola[0] > VENTANA_SEGUNDOS:
            cola.popleft()
        if len(cola) >= MAX_POR_IP:
            return False
        cola.append(ahora)
        return True
