"""API REST que expone las herramientas de GeoMB (para pruebas/uso directo, sin pasar por
Gemini) + un endpoint /chat que sí usa el agente con Function Calling.

Arrancar:
    uvicorn integrations.gemini.server:app --reload --port 8000
"""
from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from . import data_loader as dl
from . import tools
from .agent import GeoMBAgent

app = FastAPI(
    title="GeoMB · API + agente Gemini",
    description="Endpoints propios de GeoMB (estaciones, líneas, ruta, unidades, estado del "
                 "servicio) y un chat con Function Calling sobre Gemini que los usa.",
    version="0.1.0",
)

# Instancia única del agente para esta demo (mantiene UN historial de conversación
# compartido). En producción, cada usuario necesita su propia sesión -- ver README.md.
_agente: GeoMBAgent | None = None


def _get_agente() -> GeoMBAgent:
    global _agente
    if _agente is None:
        _agente = GeoMBAgent()
    return _agente


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

@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest):
    agente = _get_agente()
    if req.reiniciar:
        agente.reiniciar()
    try:
        respuesta = await agente.responder(req.mensaje)
    except RuntimeError as e:
        # Típicamente falta GEMINI_API_KEY.
        raise HTTPException(status_code=500, detail=str(e))
    return ChatResponse(respuesta=respuesta)


@app.get("/salud")
def salud():
    return {"ok": True, "lineas_cargadas": len(dl.todas_las_lineas())}
