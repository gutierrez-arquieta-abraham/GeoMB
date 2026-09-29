"""API REST que expone las herramientas de GeoMB (para pruebas/uso directo, sin pasar por
Gemini) + un endpoint /chat que sí usa el agente con Function Calling.

Arrancar:
    uvicorn integrations.gemini.server:app --reload --port 8000
"""
from __future__ import annotations

import asyncio
import logging

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from . import data_loader as dl
from . import idle_shutdown, ip_limiter, ratelimit, tools
from .session_manager import SessionManager

logger = logging.getLogger("geomb.gemini.server")

app = FastAPI(
    title="GeoMB · API + agente Gemini",
    description="Endpoints propios de GeoMB (estaciones, líneas, ruta, unidades, estado del "
                 "servicio) y un chat con Function Calling sobre Gemini que los usa.",
    version="0.1.0",
)


@app.middleware("http")
async def _marcar_actividad(request: Request, call_next):
    idle_shutdown.registrar_actividad()
    return await call_next(request)


@app.on_event("startup")
async def _iniciar_vigilancia_inactividad() -> None:
    # Solo hace algo si GEOMB_IDLE_TIMEOUT_S > 0 -- ver idle_shutdown.py y
    # deploy/geomb-gemini.socket (activación por socket: el proceso se apaga solo sin
    # tráfico y systemd lo vuelve a levantar en la siguiente solicitud).
    asyncio.create_task(idle_shutdown.vigilar_inactividad())

# Una sesión de conversación por dispositivo (X-Device-ID), no una sola global compartida --
# ver session_manager.py. El límite diario de mensajes por dispositivo vive en ratelimit.py.
_sesiones = SessionManager()


def _ip_cliente(request: Request) -> str:
    adelante = request.headers.get("x-forwarded-for")
    if adelante:
        return adelante.split(",")[0].strip()
    return request.client.host if request.client else "desconocida"


# ---------------------------------------------------------------- esquemas de request/response

class ChatRequest(BaseModel):
    mensaje: str
    reiniciar: bool = False


class ChatResponse(BaseModel):
    respuesta: str


# ---------------------------------------------------------------- endpoints propios de GeoMB

@app.get("/lineas")
def listar_lineas():
    return [{"numero": l.numero, "nombre": l.nombre} for l in dl.todas_las_lineas()]


@app.get("/lineas/{identificador}")
def obtener_linea(identificador: str):
    resultado = tools.info_linea(identificador)
    if "error" in resultado:
        raise HTTPException(status_code=404, detail=resultado["error"])
    return resultado


@app.get("/estaciones")
def buscar_estacion(q: str):
    return tools.buscar_estacion(q)


@app.get("/unidades/{economico}")
async def buscar_unidad(economico: str):
    return await tools.buscar_unidad(economico)


@app.get("/estado-servicio")
async def estado_servicio(linea: str = ""):
    return await tools.estado_servicio(linea)


@app.get("/ruta")
def planificar_ruta(origen: str, destino: str):
    resultado = tools.planificar_ruta(origen, destino)
    if "error" in resultado:
        raise HTTPException(status_code=404, detail=resultado["error"])
    return resultado


# ---------------------------------------------------------------- chat con Gemini (Function Calling)

@app.post("/chat")
async def chat(
    req: ChatRequest,
    request: Request,
    x_device_id: str | None = Header(default=None, alias="X-Device-ID"),
):
    if not x_device_id:
        raise HTTPException(status_code=400, detail="Falta el header X-Device-ID.")

    ip = _ip_cliente(request)
    if not ip_limiter.permitir(ip):
        raise HTTPException(
            status_code=429, detail="Demasiadas solicitudes desde esta red, intenta más tarde."
        )

    if not ratelimit.puede_enviar(x_device_id):
        return JSONResponse(
            status_code=429,
            content={
                "error": "cuota_agotada",
                "mensaje": "Ya usaste tu límite de mensajes del asistente por hoy. Vuelve mañana.",
            },
        )

    if req.reiniciar:
        _sesiones.reiniciar(x_device_id)
    agente = _sesiones.obtener(x_device_id)

    try:
        respuesta = await agente.responder(req.mensaje)
    except Exception as e:
        # Cualquier falla real llamando a Gemini (API key inválida, modelo retirado, error de
        # red, etc.) -- sin este catch amplio, una excepción que NO fuera RuntimeError se
        # filtraba como un 500 en texto plano sin JSON (bug real, visto en producción).
        logger.exception("Fallo llamando al asistente para device_id=%s", x_device_id)
        raise HTTPException(status_code=500, detail=f"El asistente no pudo responder: {e}")

    # Se cuenta contra la cuota diaria solo si Gemini de verdad respondió (una falla del
    # servidor no debe consumirle su mensaje del día al usuario).
    ratelimit.registrar_mensaje(x_device_id)
    return ChatResponse(respuesta=respuesta)


@app.get("/salud")
def salud():
    return {"ok": True, "lineas_cargadas": len(dl.todas_las_lineas())}
