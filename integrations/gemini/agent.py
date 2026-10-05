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

from . import config, registry

logger = logging.getLogger("geomb.gemini")

SYSTEM_INSTRUCTION = (
    "Eres el asistente de GeoMB, una app de transporte de la Ciudad de México y el Estado "
    "de México (Metrobús L1-L7, Mexibús L1-L4 + ramales A + exprés, Mexicable L1-L2). "
    "Respondes SIEMPRE en español, de forma breve y concreta. "
    "NUNCA inventes posiciones de unidades, estado del servicio, estaciones ni rutas: para "
    "cualquier dato real usa las herramientas disponibles. Si una herramienta devuelve un "
    "error o no encuentra algo, dilo con honestidad en vez de adivinar. "
    "Terminología: transbordo (Metrobús↔Metrobús), correspondencia (Mexibús/Mexicable entre "
    "sí), conexión (Metrobús↔Edomex). "
    "IMPORTANTE -- no confundas preguntas informativas con acciones: '¿dónde está/qué es/qué "
    "modelo es la unidad X?' son SIEMPRE consultas (usa buscar_unidad u otra herramienta de "
    "lectura), nunca disparan seguimiento. Solo una solicitud EXPLÍCITA de iniciar o detener el "
    "seguimiento de una unidad ('quiero seguir la X', 'deja de seguir la X') debe usar "
    "seguirUnidad/dejarDeSeguirUnidad -- y aun así, esas herramientas NUNCA ejecutan nada: solo "
    "proponen la acción para que el usuario la confirme en la app."
)

# Límite de vueltas herramienta->modelo->herramienta por mensaje, para no quedar en bucle
# si el modelo insiste en pedir funciones (p. ej. por una respuesta de error repetida).
MAX_TURNOS_HERRAMIENTA = 6

# Cuántos turnos de conversación (usuario + respuesta final) se conservan por sesión. Cada
# turno de más se descarta del INICIO del historial -- sin esto, una sesión de larga duración
# (un device_id que no reinicia el chat) acumularía tokens de contexto sin límite en cada
# llamada a Gemini, disparando el costo.
MAX_HISTORIAL_TURNOS = 6


class GeoMBAgent:
    def __init__(
        self,
        api_key: str | None = None,
        model: str | None = None,
        client: "genai.Client | None" = None,
    ) -> None:
        if client is None and not (api_key or config.GEMINI_API_KEY):
            raise RuntimeError(
                "Falta GEMINI_API_KEY (variable de entorno o .env) -- ver .env.example."
            )
        self._client = client or genai.Client(api_key=api_key or config.GEMINI_API_KEY)
        self._model = model or config.GEMINI_MODEL
        self._historial: list[types.Content] = []
        # Índice en _historial donde empieza cada turno de usuario (para poder recortar por
        # turnos completos en vez de por número de Content sueltos, que varía según cuántas
        # herramientas se hayan llamado dentro de un mismo turno).
        self._inicios_turno: list[int] = []
        # Contexto de tracking del último mensaje (ver responder()) -- disponible desde ya para
        # que las Tools de Fase 3 lo puedan leer, aunque todavía ninguna lo use.
        self._contexto_dispositivo: dict = {}
        # Última propuesta de acción (ver accion_pendiente()) del turno más reciente de
        # responder() -- se reinicia en CADA llamada, nunca se arrastra de un turno a otro.
        self._ultima_accion_pendiente: dict | None = None

    def reiniciar(self) -> None:
        """Borra el historial de la conversación (nueva sesión)."""
        self._historial = []
        self._inicios_turno = []

    def accion_pendiente(self) -> dict | None:
        """Última propuesta de acción (p. ej. detenerRecorrido) del turno más reciente de
        responder() -- None si ninguna tool de acción se invocó en ese turno, o si lo que
        devolvió no traía requiere_confirmacion. server.py llama a esto (nunca lee el atributo
        privado directo) para armar ChatResponse.accionPendiente."""
        return self._ultima_accion_pendiente

    async def responder(self, mensaje_usuario: str, contexto_dispositivo: dict | None = None) -> str:
        # Contexto de tracking que Android manda con cada mensaje (ver DiagnosticoReporte.
        # contextoTracking() del lado Android y ChatRequest.contextoDispositivo en server.py).
        # _ejecutar_herramienta() lo inyecta SOLO a las tools que lo declaran en su firma (ver
        # tools_tracking.py/tools_app.py) -- las demás no lo reciben ni cambian su comportamiento.
        self._contexto_dispositivo = contexto_dispositivo or {}
        # Nunca se arrastra de un turno a otro: si ESTE mensaje no propone ninguna acción nueva,
        # accion_pendiente() debe devolver None, aunque el turno anterior sí hubiera propuesto una.
        self._ultima_accion_pendiente = None

        self._inicios_turno.append(len(self._historial))
        self._historial.append(
            types.Content(role="user", parts=[types.Part(text=mensaje_usuario)])
        )

        for _ in range(MAX_TURNOS_HERRAMIENTA):
            respuesta = await self._client.aio.models.generate_content(
                model=self._model,
                contents=self._historial,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_INSTRUCTION,
                    tools=[registry.TODAS_LAS_HERRAMIENTAS],
                ),
            )
            candidato = respuesta.candidates[0] if respuesta.candidates else None
            if candidato is None or candidato.content is None:
                return "No pude generar una respuesta en este momento, intenta de nuevo."

            llamadas = [p.function_call for p in candidato.content.parts if p.function_call]
            if not llamadas:
                # Respuesta final en texto: la guarda en el historial y la regresa.
                self._historial.append(candidato.content)
                self._recortar_historial()
                return respuesta.text or ""

            # El modelo pidió una o varias funciones: se ejecutan TODAS antes de responder
            # (Gemini puede pedir varias en paralelo), y el turno del modelo (con las
            # function_call) se agrega al historial ANTES de las respuestas, como exige la API.
            self._historial.append(candidato.content)
            partes_respuesta = []
            for llamada in llamadas:
                resultado = await self._ejecutar_herramienta(llamada.name, dict(llamada.args or {}))
                if isinstance(resultado, dict) and resultado.get("requiere_confirmacion"):
                    # Se conserva la propuesta (nunca se ejecuta nada aquí): si varias tools de
                    # acción se llamaran en el mismo turno, gana la última -- no debería pasar en
                    # Fase 3B (solo hay una tool de acción implementada), pero el criterio queda
                    # definido para cuando haya más.
                    self._ultima_accion_pendiente = {
                        "accion": resultado.get("accion"),
                        "parametros": resultado.get("parametros", {}),
                        "resumen": resultado.get("resumen", ""),
                    }
                partes_respuesta.append(
                    types.Part.from_function_response(name=llamada.name, response={"result": resultado})
                )
            self._historial.append(types.Content(role="user", parts=partes_respuesta))

        self._recortar_historial()
        return ("No logré completar tu solicitud después de varios intentos con las "
                "herramientas disponibles. Intenta reformular la pregunta.")

    def _recortar_historial(self) -> None:
        if len(self._inicios_turno) <= MAX_HISTORIAL_TURNOS:
            return
        exceso = len(self._inicios_turno) - MAX_HISTORIAL_TURNOS
        corte = self._inicios_turno[exceso]
        self._historial = self._historial[corte:]
        self._inicios_turno = [i - corte for i in self._inicios_turno[exceso:]]

    async def _ejecutar_herramienta(self, nombre: str, args: dict) -> dict:
        funcion = registry.TODOS_LOS_EJECUTORES.get(nombre)
        if funcion is None:
            return {"error": f"Herramienta desconocida: {nombre}"}
        try:
            # Solo se inyecta el contexto del dispositivo a las tools que lo DECLARAN en su firma
            # (ver tools_tracking.py/tools_app.py, Fase 3A) -- las demás (las 5 de tools.py y las
            # tools de acción, todavía placeholders) no lo reciben y siguen funcionando exactamente
            # igual que antes, sin tocar su firma ni su comportamiento.
            if "contexto_dispositivo" in inspect.signature(funcion).parameters:
                args = {**args, "contexto_dispositivo": self._contexto_dispositivo}
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
