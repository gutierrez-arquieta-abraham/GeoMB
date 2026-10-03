"""ReportTools: crear un reporte técnico de la app (ver ReporteApp.java / ReporteEntity en el
lado Android). Esta función JAMÁS envía el reporte por sí misma -- el envío real (Room, Firestore,
abrir el cliente de correo) solo puede ocurrir en Android, y solo después de que el usuario
confirme explícitamente (ver Fase 4/5 del diseño aprobado).

La categoría es un enum CERRADO de 3 valores (no texto libre): así el índice que Android necesita
para ReporteApp.enviar(categoriaPos) se deriva de forma determinista, sin que el modelo tenga que
acertar un número.

FASE 1: solo declaración + placeholder. No hay confirmación ni ejecución real todavía.
"""
from __future__ import annotations

from typing import Any

from google.genai import types

_NO_IMPLEMENTADO = {"error": "no implementado todavía"}

# Mismo orden/índices que el spinner de categorías en ConfiguracionFragment (ver
# ReporteApp.calcularPrioridad: categoriaPos == 1 => "Proceso incompleto").
CATEGORIAS = ("mala_informacion", "proceso_incompleto", "otro")


def crear_reporte(categoria: str, descripcion: str) -> dict[str, Any]:
    """Propone crear un reporte técnico de un problema de la app. NUNCA lo envía por sí misma."""
    return _NO_IMPLEMENTADO


DECLARACIONES = [
    types.FunctionDeclaration(
        name="crearReporte",
        description=(
            "Propone crear un reporte técnico de un problema de la app GeoMB (no del servicio de "
            "transporte). Nunca lo envía por sí sola: solo arma la propuesta para que el usuario "
            "la confirme antes de guardarla y notificarla."
        ),
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "categoria": types.Schema(
                    type=types.Type.STRING,
                    enum=list(CATEGORIAS),
                    description="Categoría del problema: 'mala_informacion' (la app muestra datos incorrectos), "
                                 "'proceso_incompleto' (algo no termina de funcionar), u 'otro'.",
                ),
                "descripcion": types.Schema(type=types.Type.STRING, description="Descripción breve de lo ocurrido, en las propias palabras del usuario."),
            },
            required=["categoria", "descripcion"],
        ),
    ),
]

EJECUTORES = {
    "crearReporte": crear_reporte,
}
