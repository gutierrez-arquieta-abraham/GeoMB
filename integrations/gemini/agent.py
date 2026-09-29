"""Orquestador de Gemini + Function Calling: recibe un mensaje del usuario, deja que Gemini
decida qué herramienta(s) de GeoMB llamar, ejecuta esas funciones de verdad y le regresa el
resultado al modelo hasta que arme una respuesta final en lenguaje natural.

Uso mínimo:
    agente = GeoMBAgent()
    respuesta = await agente.responder("¿cómo llego de Tacubaya a Tenayuca?")
"""
from __future__ import annotations

import asyncio
import inspect
import json
import logging

from google import genai
from google.genai import types

from . import config, tools

logger = logging.getLogger("geomb.gemini")

SYSTEM_INSTRUCTION = (
    "Eres el asistente de GeoMB, una app de transporte de la Ciudad de México y el Estado "
    "de México (Metrobús L1-L7, Mexibús L1-L4 + ramales A + exprés, Mexicable L1-L2). "
    "Respondes SIEMPRE en español, de forma breve y concreta. "
    "NUNCA inventes posiciones de unidades, estado del servicio, estaciones ni rutas: para "
    "cualquier dato real usa las herramientas disponibles. Si una herramienta devuelve un "
    "error o no encuentra algo, dilo con honestidad en vez de adivinar. "
    "Terminología: transbordo (Metrobús↔Metrobús), correspondencia (Mexibús/Mexicable entre "
    "sí), conexión (Metrobús↔Edomex)."
)

# Límite de vueltas herramienta->modelo->herramienta por mensaje, para no quedar en bucle
# si el modelo insiste en pedir funciones (p. ej. por una respuesta de error repetida).
MAX_TURNOS_HERRAMIENTA = 6


class GeoMBAgent:
    def __init__(self, api_key: str | None = None, model: str | None = None) -> None:
        if not (api_key or config.GEMINI_API_KEY):
            raise RuntimeError(
                "Falta GEMINI_API_KEY (variable de entorno o .env) -- ver .env.example."
            )
        self._client = genai.Client(api_key=api_key or config.GEMINI_API_KEY)
        self._model = model or config.GEMINI_MODEL
        self._historial: list[types.Content] = []

    def reiniciar(self) -> None:
        """Borra el historial de la conversación (nueva sesión)."""
        self._historial = []

    async def responder(self, mensaje_usuario: str) -> str:
        self._historial.append(
            types.Content(role="user", parts=[types.Part(text=mensaje_usuario)])
        )

        for _ in range(MAX_TURNOS_HERRAMIENTA):
            respuesta = await self._client.aio.models.generate_content(
                model=self._model,
                contents=self._historial,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_INSTRUCTION,
                    tools=[tools.HERRAMIENTAS],
                ),
            )
            candidato = respuesta.candidates[0] if respuesta.candidates else None
            if candidato is None or candidato.content is None:
                return "No pude generar una respuesta en este momento, intenta de nuevo."

            llamadas = [p.function_call for p in candidato.content.parts if p.function_call]
            if not llamadas:
                # Respuesta final en texto: la guarda en el historial y la regresa.
                self._historial.append(candidato.content)
                return respuesta.text or ""

            # El modelo pidió una o varias funciones: se ejecutan TODAS antes de responder
            # (Gemini puede pedir varias en paralelo), y el turno del modelo (con las
            # function_call) se agrega al historial ANTES de las respuestas, como exige la API.
            self._historial.append(candidato.content)
            partes_respuesta = []
            for llamada in llamadas:
                resultado = await self._ejecutar_herramienta(llamada.name, dict(llamada.args or {}))
                partes_respuesta.append(
                    types.Part.from_function_response(name=llamada.name, response={"result": resultado})
                )
            self._historial.append(types.Content(role="user", parts=partes_respuesta))

        return ("No logré completar tu solicitud después de varios intentos con las "
                "herramientas disponibles. Intenta reformular la pregunta.")

    async def _ejecutar_herramienta(self, nombre: str, args: dict) -> dict:
        funcion = tools.EJECUTORES.get(nombre)
        if funcion is None:
            return {"error": f"Herramienta desconocida: {nombre}"}
        try:
            if inspect.iscoroutinefunction(funcion):
                resultado = await funcion(**args)
            else:
                resultado = await asyncio.to_thread(funcion, **args)
            logger.info("herramienta=%s args=%s -> %s", nombre, args, _resumen_log(resultado))
            return resultado
        except Exception as e:  # nunca dejar que un error de una herramienta tumbe el chat
            logger.exception("Fallo ejecutando la herramienta %s", nombre)
            return {"error": f"La herramienta '{nombre}' falló: {e}"}


def _resumen_log(resultado) -> str:
    try:
        texto = json.dumps(resultado, ensure_ascii=False)
    except Exception:
        texto = str(resultado)
    return texto[:200] + ("…" if len(texto) > 200 else "")
