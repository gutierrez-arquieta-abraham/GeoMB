"""Apaga el proceso si nadie lo usa por un rato -- pensado para correr como servicio con
ACTIVACIÓN POR SOCKET de systemd (ver deploy/geomb-gemini.socket): el socket se queda
escuchando el puerto SIEMPRE, pero el proceso de Python (uvicorn + el modelo cargado) solo
existe mientras hay actividad. Al pasar GEOMB_IDLE_TIMEOUT_S sin ninguna solicitud, este
módulo apaga el proceso con SIGTERM (uvicorn cierra limpio); systemd lo deja apagado
(Restart=no) hasta que llega la SIGUIENTE solicitud al socket, momento en el que systemd
levanta el proceso de nuevo automáticamente -- sin esto, correría 24/7 aunque nadie lo abra.

Con GEOMB_IDLE_TIMEOUT_S=0 (el default) queda desactivado: útil para desarrollo local, donde
no hay socket de systemd y no tendría caso apagarse solo.
"""
from __future__ import annotations

import asyncio
import logging
import os
import signal
import time

logger = logging.getLogger("geomb.gemini.idle")

IDLE_TIMEOUT_S = float(os.environ.get("GEOMB_IDLE_TIMEOUT_S", "0"))
_INTERVALO_REVISION_S = 30.0

_ultima_actividad = time.monotonic()


def registrar_actividad() -> None:
    global _ultima_actividad
    _ultima_actividad = time.monotonic()


async def vigilar_inactividad() -> None:
    """Tarea de fondo: si nadie llama a registrar_actividad() en IDLE_TIMEOUT_S, apaga el
    proceso. No hace nada si IDLE_TIMEOUT_S <= 0 (desactivado)."""
    if IDLE_TIMEOUT_S <= 0:
        return
    while True:
        await asyncio.sleep(_INTERVALO_REVISION_S)
        inactivo_por = time.monotonic() - _ultima_actividad
        if inactivo_por > IDLE_TIMEOUT_S:
            logger.info(
                "Sin actividad por %.0fs (límite %.0fs): apagando hasta la próxima solicitud "
                "(activación por socket).",
                inactivo_por, IDLE_TIMEOUT_S,
            )
            os.kill(os.getpid(), signal.SIGTERM)
            return
