"""AppTools: configuración local de la app (Modos.java / SharedPreferences en Android) -- solo
lectura, nunca cambia nada. Igual que TrackingTools, en la Fase 3 leerá el `contexto_dispositivo`
que Android manda con cada mensaje (todavía NO implementado en esta fase).

FASE 1: solo declaración + placeholder.
"""
from __future__ import annotations

from typing import Any

from google.genai import types

_NO_IMPLEMENTADO = {"error": "no implementado todavía"}


def obtener_configuracion() -> dict[str, Any]:
    """Configuración actual de la app relevante para el usuario (ahorro de datos, si se
    muestra Mexibús, notificaciones de afectaciones)."""
    return _NO_IMPLEMENTADO


DECLARACIONES = [
    types.FunctionDeclaration(
        name="obtenerConfiguracion",
        description="Obtiene la configuración actual de la app del usuario (ahorro de datos, si muestra Mexibús, notificaciones de afectaciones).",
        parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    ),
]

EJECUTORES = {
    "obtenerConfiguracion": obtener_configuracion,
}
