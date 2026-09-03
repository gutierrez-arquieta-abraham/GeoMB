"""API FastAPI: expone las afectaciones Mexibus en el mismo formato que la app.

Endpoints:
  GET /mexibus/afectaciones   -> {"rows":[...], "actualizado": "...", "total": N}
  GET /health                 -> {"ok": true, ...}
  GET /debug/fuentes          -> cuentas configuradas (sin tokens)
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from .aggregator import Agregador
from .catalog import Catalogo
from .config import settings
from .models import RowsResponse
from .sources import cargar_cuentas

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

estado: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    cat = Catalogo.cargar()
    agg = Agregador(cat)
    estado["agg"] = agg
    await agg.iniciar()
    yield
    await agg.detener()


app = FastAPI(title="Mexibus Afectaciones", version="1.0", lifespan=lifespan)


@app.get("/mexibus/afectaciones")
async def afectaciones() -> JSONResponse:
    agg: Agregador = estado["agg"]
    resp = RowsResponse(rows=agg.rows, actualizado=agg.actualizado, total=len(agg.rows))
    # La app solo lee "rows"; los demas campos son informativos.
    return JSONResponse(resp.model_dump())


@app.get("/health")
async def health():
    agg: Agregador = estado.get("agg")
    return {
        "ok": True,
        "mock": settings.usar_mock,
        "fuentes": [getattr(f, "plataforma", "?") for f in (agg.fuentes if agg else [])],
        "actualizado": agg.actualizado if agg else None,
        "filas": len(agg.rows) if agg else 0,
    }


@app.get("/debug/fuentes")
async def debug_fuentes():
    # No expone tokens; solo la config de cuentas.
    return {"cuentas": cargar_cuentas(), "usar_mock": settings.usar_mock}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)
