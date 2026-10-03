"""TrackingTools: estado del recorrido guiado (RecorridoService) y de las unidades seguidas
(SeguimientoService/RealtimeRepository) -- ver el diseño aprobado en docs/ (sesión de Gemini
Function Calling). NINGUNA de estas funciones puede tocar código Android directamente: el
proceso Python nunca tiene acceso a RecorridoService/SeguimientoService -- eso solo existe en
memoria del teléfono. Las de LECTURA leerán, en la Fase 3, el `contexto_dispositivo` que Android
manda con cada mensaje (todavía NO implementado en esta fase). Las de ACCIÓN nunca ejecutan nada
aquí: en la Fase 4 devolverán "requiere_confirmacion" para que Android decida y ejecute.

FASE 1: solo declaraciones + placeholders ("no implementado todavía"). No hay contexto, no hay
confirmación, no hay ejecución real.
"""
from __future__ import annotations

from typing import Any

from google.genai import types

_NO_IMPLEMENTADO = {"error": "no implementado todavía"}


# ============================================================
# Placeholders (Fase 1) -- firmas ya definitivas, cuerpo pendiente de Fase 3/4/5
# ============================================================

def obtener_estacion_actual() -> dict[str, Any]:
    """Estación actual del recorrido guiado en curso (RecorridoService)."""
    return _NO_IMPLEMENTADO


def obtener_proxima_estacion() -> dict[str, Any]:
    """Siguiente estación del recorrido guiado en curso."""
    return _NO_IMPLEMENTADO


def obtener_linea_actual() -> dict[str, Any]:
    """Línea del recorrido guiado en curso."""
    return _NO_IMPLEMENTADO


def obtener_unidad_seleccionada() -> dict[str, Any]:
    """Unidad (camión) seleccionada ahora mismo en el mapa, si hay alguna."""
    return _NO_IMPLEMENTADO


def obtener_unidades_seguidas() -> dict[str, Any]:
    """Unidades bajo seguimiento de proximidad activo (SeguimientoService), puede ser ninguna."""
    return _NO_IMPLEMENTADO


def iniciar_recorrido(destino: str, origen: str = "") -> dict[str, Any]:
    """Propone iniciar un recorrido guiado hacia 'destino' (y opcionalmente desde 'origen').
    NUNCA ejecuta nada por sí misma -- ver Fase 4 (requiere_confirmacion) y Fase 5 (ejecución
    real, solo en Android, solo tras que el usuario confirme)."""
    return _NO_IMPLEMENTADO


def detener_recorrido() -> dict[str, Any]:
    """Propone detener el recorrido guiado en curso. Nunca ejecuta nada por sí misma."""
    return _NO_IMPLEMENTADO


def seguir_unidad(economico: str) -> dict[str, Any]:
    """Propone empezar a seguir (alertas de proximidad) la unidad 'economico'. Nunca ejecuta
    nada por sí misma."""
    return _NO_IMPLEMENTADO


def dejar_de_seguir_unidad(economico: str = "") -> dict[str, Any]:
    """Propone dejar de seguir una unidad ('economico') o todas (si se omite). Nunca ejecuta
    nada por sí misma."""
    return _NO_IMPLEMENTADO


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
