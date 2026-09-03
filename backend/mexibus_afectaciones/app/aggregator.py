"""Agrega las fuentes: consulta cada N segundos, interpreta y cachea las filas.

El endpoint sirve SIEMPRE desde cache (respuesta instantanea); un task en segundo
plano refresca. Deduplica por (tipo, linea, estacion, direccion) y filtra por
ventana de vigencia (posts recientes).
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from .catalog import Catalogo, norm
from .config import settings
from .models import Row
from .parser import interpretar
from .sources import construir_fuentes

log = logging.getLogger("mxb.agg")


class Agregador:
    def __init__(self, catalogo: Catalogo):
        self.cat = catalogo
        self.fuentes = construir_fuentes()
        self._rows: list[Row] = []
        self._actualizado: datetime | None = None
        self._lock = asyncio.Lock()
        self._task: asyncio.Task | None = None

    # ---- API publica ----
    @property
    def rows(self) -> list[Row]:
        return self._rows

    @property
    def actualizado(self) -> str | None:
        return self._actualizado.isoformat() if self._actualizado else None

    async def refrescar(self) -> None:
        posts = []
        for f in self.fuentes:
            try:
                posts.extend(await f.obtener())
            except Exception as e:
                log.error("fuente %s fallo: %s", getattr(f, "plataforma", "?"), e)

        corte = datetime.now(timezone.utc) - timedelta(minutes=settings.ventana_minutos)
        rows: list[Row] = []
        for p in posts:
            if p.fecha and p.fecha.astimezone(timezone.utc) < corte:
                continue   # fuera de la ventana de vigencia
            rows.extend(interpretar(p, self.cat))

        deduped = self._dedupe(rows)
        async with self._lock:
            self._rows = deduped
            self._actualizado = datetime.now(timezone.utc)
        log.info("refresco: %d posts -> %d filas (%d fuentes)", len(posts), len(deduped), len(self.fuentes))

    @staticmethod
    def _dedupe(rows: list[Row]) -> list[Row]:
        vistos: dict[tuple, Row] = {}
        for r in rows:
            clave = (r.tipo, r.linea, norm(r.estacion), norm(r.direccion))
            # conserva la de motivo mas largo (mas informativa)
            if clave not in vistos or len(r.motivo) > len(vistos[clave].motivo):
                vistos[clave] = r
        return list(vistos.values())

    # ---- ciclo de vida ----
    async def iniciar(self) -> None:
        await self.refrescar()  # primer llenado inmediato
        self._task = asyncio.create_task(self._loop())

    async def _loop(self) -> None:
        while True:
            await asyncio.sleep(settings.poll_segundos)
            try:
                await self.refrescar()
            except Exception as e:
                log.error("loop refresco: %s", e)

    async def detener(self) -> None:
        if self._task:
            self._task.cancel()
