"""TrackingTools: estado del recorrido guiado (RecorridoService) y de las unidades seguidas
(SeguimientoService/RealtimeRepository) -- ver el diseño aprobado en docs/ (sesión de Gemini
Function Calling). NINGUNA de estas funciones puede tocar código Android directamente: el
proceso Python nunca tiene acceso a RecorridoService/SeguimientoService -- eso solo existe en
memoria del teléfono.

FASE 3A: las 5 de LECTURA leen SOLO `contexto_dispositivo` (el dict que Android ya manda con cada
mensaje, ver DiagnosticoReporte.contextoTracking() y agent.py#_ejecutar_herramienta -- se inyecta
automáticamente porque estas funciones lo declaran en su firma). Nunca consultan GPS, nunca
calculan nada nuevo, nunca devuelven coordenadas: si el campo no vino en el contexto, es
"sin dato", nunca se inventa.

ACCIÓN: "seguirUnidad"/"dejarDeSeguirUnidad" (seguimiento de proximidad, SeguimientoService) ya
están activas -- devuelven "requiere_confirmacion" para que Android decida, confirme y ejecute
(ver SeguimientoService.iniciar()/.detener() y ChatAsistenteActivity.confirmarAccion()); JAMÁS se
ejecutan aquí. "iniciarRecorrido"/"detenerRecorrido" son un flujo DISTINTO (RecorridoService, el
recorrido guiado por voz, no el seguimiento de una unidad) y siguen fuera de este alcance:
detenerRecorrido ya estaba implementada desde Fase 3B; iniciarRecorrido sigue como placeholder.
"""
from __future__ import annotations

import asyncio
from typing import Any

from google.genai import types

from . import tools

_NO_IMPLEMENTADO = {"error": "no implementado todavía"}

# Tope para la validación de seguir_unidad contra el feed en vivo (tools.buscar_unidad ->
# backend_client, que ya tiene SU PROPIO failover de hasta HTTP_TIMEOUT_S=10s por intento, hasta
# 20s en el peor caso con el de respaldo). Una acción confirmable ya implica 2 vueltas a Gemini
# dentro del readTimeout de 30s de Android (ver Asistente.java); sin este tope, un backend lento
# puede acercar o superar ese presupuesto y el teléfono muestra "no se pudo conectar" aunque el
# servidor esté sano -- mejor fallar rápido y claro que arriesgar el timeout completo del cliente.
_TIMEOUT_VALIDACION_S = 8.0


# ============================================================
# Tools de LECTURA (Fase 3A) -- leen únicamente contexto_dispositivo, nunca GPS/Android
# ============================================================

def obtener_estacion_actual(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Estación actual del recorrido guiado en curso, según el contexto que mandó Android."""
    ctx = contexto_dispositivo or {}
    estacion = ctx.get("estacionActual")
    if not ctx.get("recorridoActivo") or not estacion:
        return {"success": False, "error": "NO_ACTIVE_TRACKING"}
    resultado: dict[str, Any] = {"success": True, "station": estacion}
    if ctx.get("linea"):
        resultado["line"] = ctx["linea"]
    if ctx.get("sentido"):
        resultado["direction"] = ctx["sentido"]
    return resultado


def obtener_proxima_estacion(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Siguiente estación del recorrido guiado en curso, según el contexto que mandó Android.
    NUNCA inventa una distancia: ese dato no existe en el contexto (RecorridoService no lo
    calcula), así que jamás aparece en la respuesta."""
    ctx = contexto_dispositivo or {}
    if not ctx.get("recorridoActivo"):
        return {"success": False, "error": "NO_ACTIVE_TRACKING"}
    siguiente = ctx.get("estacionSiguiente")
    if not siguiente:
        return {"success": False, "error": "NO_NEXT_STATION"}
    resultado: dict[str, Any] = {"success": True, "station": siguiente}
    if ctx.get("linea"):
        resultado["line"] = ctx["linea"]
    return resultado


def obtener_linea_actual(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Línea del recorrido guiado en curso, según el contexto que mandó Android."""
    ctx = contexto_dispositivo or {}
    linea = ctx.get("linea")
    if not linea:
        return {"success": False, "error": "NO_LINE_AVAILABLE"}
    resultado: dict[str, Any] = {"success": True, "line": linea}
    if ctx.get("sentido"):
        resultado["direction"] = ctx["sentido"]
    return resultado


def obtener_unidad_seleccionada(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Unidad (camión) seleccionada ahora mismo en el mapa, según el contexto que mandó Android
    -- NUNCA devuelve coordenadas, solo el número económico."""
    ctx = contexto_dispositivo or {}
    unidad = ctx.get("unidadSeleccionada")
    if not unidad:
        return {"success": False, "error": "NO_UNIT_SELECTED"}
    return {"success": True, "unit": unidad}


def obtener_unidades_seguidas(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Unidades bajo seguimiento de proximidad activo, según el contexto que mandó Android --
    una lista vacía es una respuesta VÁLIDA (no seguir ninguna unidad no es un error), nunca se
    confunde con obtener_unidad_seleccionada (son conceptos distintos, ver tools_tracking.py)."""
    ctx = contexto_dispositivo or {}
    unidades = ctx.get("unidadesSeguidas")
    if not isinstance(unidades, list):
        unidades = []
    return {"success": True, "units": [str(u) for u in unidades]}


# ============================================================
# Placeholders de ACCIÓN (Fase 1, sin cambios en Fase 3A) -- pendientes de Fase 4/5
# ============================================================

def iniciar_recorrido(destino: str, origen: str = "") -> dict[str, Any]:
    """Propone iniciar un recorrido guiado hacia 'destino' (y opcionalmente desde 'origen').
    NUNCA ejecuta nada por sí misma -- ver Fase 4 (requiere_confirmacion) y Fase 5 (ejecución
    real, solo en Android, solo tras que el usuario confirme)."""
    return _NO_IMPLEMENTADO


def detener_recorrido() -> dict[str, Any]:
    """Propone detener el recorrido guiado en curso -- NUNCA lo detiene por sí misma: Python no
    tiene (ni puede tener) acceso a RecorridoService, que solo existe en el proceso Android. Esto
    solo arma la propuesta; agent.py la captura (ver _ultima_accion_pendiente) y server.py la
    expone en ChatResponse.accionPendiente para que Android decida, confirme y ejecute."""
    return {
        "requiere_confirmacion": True,
        "accion": "detenerRecorrido",
        "parametros": {},
        "resumen": "Detener el recorrido actual",
    }


async def seguir_unidad(economico: str) -> dict[str, Any]:
    """Propone empezar a seguir (alertas de proximidad) la unidad 'economico'. Comprueba primero
    que la unidad exista en el feed en vivo (reusa tools.buscar_unidad, SIN duplicar esa lógica
    ni tocar tools.py) para no proponerle a Android una confirmación que de todos modos no podría
    cumplir. Nunca ejecuta nada por sí misma -- solo arma la propuesta, ver SeguimientoService."""
    economico = (economico or "").strip()
    if not economico:
        return {"error": "Falta el número económico de la unidad a seguir."}
    try:
        resultado = await asyncio.wait_for(
            tools.buscar_unidad(economico), timeout=_TIMEOUT_VALIDACION_S
        )
    except asyncio.TimeoutError:
        return {
            "error": f"No se pudo verificar la unidad '{economico}' a tiempo "
                     "(el feed en vivo no respondió). Intenta de nuevo en un momento."
        }
    if not resultado.get("encontrada"):
        return {"error": f"No se encontró la unidad '{economico}' en el feed actual, no se puede seguir."}
    return {
        "requiere_confirmacion": True,
        "accion": "seguirUnidad",
        "parametros": {"economico": economico},
        "resumen": f"Seguir la unidad {economico} (te avisará cuando esté cerca)",
    }


def dejar_de_seguir_unidad(economico: str = "") -> dict[str, Any]:
    """Propone dejar de seguir una unidad ('economico') o TODAS (si se omite). A diferencia de
    seguir_unidad, no valida contra el feed en vivo: dejar de seguir una unidad que ya no está en
    seguimiento (o que nunca se encontró) es una operación segura e idempotente del lado Android
    (ver SeguimientoService.detener()). Nunca ejecuta nada por sí misma."""
    economico = (economico or "").strip()
    if economico:
        return {
            "requiere_confirmacion": True,
            "accion": "dejarDeSeguirUnidad",
            "parametros": {"economico": economico},
            "resumen": f"Dejar de seguir la unidad {economico}",
        }
    return {
        "requiere_confirmacion": True,
        "accion": "dejarDeSeguirUnidad",
        "parametros": {},
        "resumen": "Dejar de seguir todas las unidades en seguimiento",
    }


# ============================================================
# Esquema que Gemini ve (mismo estilo que tools.py)
# ============================================================

DECLARACIONES = [
    types.FunctionDeclaration(
        name="obtenerEstacionActual",
        description="Obtiene la estación actual del recorrido guiado que el usuario está siguiendo ahora mismo en la app.",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="obtenerProximaEstacion",
        description="Obtiene la siguiente estación del recorrido guiado actual del usuario.",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="obtenerLineaActual",
        description="Obtiene la línea del recorrido guiado que el usuario está siguiendo ahora mismo.",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="obtenerUnidadSeleccionada",
        description="Obtiene la unidad (camión) que el usuario tiene seleccionada en el mapa en este momento, si hay alguna.",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="obtenerUnidadesSeguidas",
        description="Obtiene la lista de unidades (camiones) bajo seguimiento de proximidad activo del usuario (puede estar vacía).",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="iniciarRecorrido",
        description=(
            "Propone iniciar un recorrido guiado por voz hacia una estación de destino. Requiere "
            "el nombre de la estación de destino; el origen es opcional (si no se da, se usará la "
            "ubicación/estación más cercana del usuario cuando sea posible). Esta función NUNCA "
            "inicia el recorrido por sí sola: solo arma la propuesta para que el usuario la confirme."
        ),
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "destino": types.Schema(type=types.Type.STRING, description="Nombre de la estación de destino."),
                "origen": types.Schema(type=types.Type.STRING, description="Nombre de la estación de origen (opcional)."),
            },
            required=["destino"],
        ),
    ),
    types.FunctionDeclaration(
        name="detenerRecorrido",
        description="Propone detener el recorrido guiado en curso. Nunca lo detiene por sí sola: solo arma la propuesta para que el usuario la confirme.",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
    types.FunctionDeclaration(
        name="seguirUnidad",
        description=(
            "Propone empezar a seguir una unidad (camión) específica por su número económico, para "
            "recibir alertas de proximidad. Nunca inicia el seguimiento por sí sola: solo arma la "
            "propuesta para que el usuario la confirme."
        ),
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "economico": types.Schema(type=types.Type.STRING, description="Número económico de la unidad a seguir."),
            },
            required=["economico"],
        ),
    ),
    types.FunctionDeclaration(
        name="dejarDeSeguirUnidad",
        description=(
            "Propone dejar de seguir una unidad específica (o todas, si no se da número económico). "
            "Nunca lo ejecuta por sí sola: solo arma la propuesta para que el usuario la confirme."
        ),
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "economico": types.Schema(type=types.Type.STRING, description="Número económico de la unidad a dejar de seguir (opcional; vacío = todas)."),
            },
        ),
    ),
]

EJECUTORES = {
    "obtenerEstacionActual": obtener_estacion_actual,
    "obtenerProximaEstacion": obtener_proxima_estacion,
    "obtenerLineaActual": obtener_linea_actual,
    "obtenerUnidadSeleccionada": obtener_unidad_seleccionada,
    "obtenerUnidadesSeguidas": obtener_unidades_seguidas,
    "iniciarRecorrido": iniciar_recorrido,
    "detenerRecorrido": detener_recorrido,
    "seguirUnidad": seguir_unidad,
    "dejarDeSeguirUnidad": dejar_de_seguir_unidad,
}
