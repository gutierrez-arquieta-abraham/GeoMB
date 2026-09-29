"""Sesiones de conversación por dispositivo: reemplaza la instancia única y global de
GeoMBAgent (que mezclaba las conversaciones de TODOS los usuarios) por una sesión propia por
device_id, con expiración por inactividad (TTL) y un tope duro de memoria (LRU) para que miles
de device_id distintos no puedan agotar la RAM del proceso.

El cliente de Gemini (genai.Client) SÍ se comparte entre sesiones -- es solo un cliente HTTP,
no tiene estado de conversación; lo que se aísla por dispositivo es el historial de mensajes.
"""
from __future__ import annotations

import threading
import time
from collections import OrderedDict

from google import genai

from . import config
from .agent import GeoMBAgent

TTL_SEGUNDOS = 30 * 60
MAX_SESIONES = 500


class SessionManager:
    def __init__(self) -> None:
        self._cliente = genai.Client(api_key=config.GEMINI_API_KEY)
        self._sesiones: "OrderedDict[str, GeoMBAgent]" = OrderedDict()
        self._ultimo_uso: dict[str, float] = {}
        self._lock = threading.Lock()

    def obtener(self, device_id: str) -> GeoMBAgent:
        with self._lock:
            self._purgar_expiradas()
            agente = self._sesiones.get(device_id)
            if agente is None:
                agente = GeoMBAgent(client=self._cliente)
                self._sesiones[device_id] = agente
                if len(self._sesiones) > MAX_SESIONES:
                    lru_id, _ = self._sesiones.popitem(last=False)
                    self._ultimo_uso.pop(lru_id, None)
            else:
                self._sesiones.move_to_end(device_id)
            self._ultimo_uso[device_id] = time.monotonic()
            return agente

    def reiniciar(self, device_id: str) -> None:
        with self._lock:
            self._sesiones.pop(device_id, None)
            self._ultimo_uso.pop(device_id, None)

    def _purgar_expiradas(self) -> None:
        ahora = time.monotonic()
        vencidas = [did for did, t in self._ultimo_uso.items() if ahora - t > TTL_SEGUNDOS]
        for did in vencidas:
            self._sesiones.pop(did, None)
            self._ultimo_uso.pop(did, None)
