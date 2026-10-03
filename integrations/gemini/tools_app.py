"""AppTools: configuración local de la app (Modos.java / SharedPreferences en Android) -- solo
lectura, nunca cambia nada.

FASE 3A: lee ÚNICAMENTE ahorroDatos/mostrarMexibus/notificacionesAfectaciones de
`contexto_dispositivo` (inyectado automáticamente por agent.py porque la función lo declara en su
firma) -- ningún otro dato del dispositivo, y cualquier campo ausente se omite en vez de
inventarse.
"""
from __future__ import annotations

from typing import Any

from google.genai import types

_CAMPOS_CONFIGURACION = ("ahorroDatos", "mostrarMexibus", "notificacionesAfectaciones")


def obtener_configuracion(contexto_dispositivo: dict | None = None) -> dict[str, Any]:
    """Configuración actual de la app relevante para el usuario (ahorro de datos, si se
    muestra Mexibús, notificaciones de afectaciones) -- solo estos 3 campos, nunca ningún otro
    dato del dispositivo."""
    ctx = contexto_dispositivo or {}
    resultado: dict[str, Any] = {"success": True}
    for campo in _CAMPOS_CONFIGURACION:
        if campo in ctx:
            resultado[campo] = ctx[campo]
    return resultado


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
