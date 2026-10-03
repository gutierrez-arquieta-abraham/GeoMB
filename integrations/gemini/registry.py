"""Tool Registry: junta las TransportTools ya existentes (tools.py, SIN TOCAR) con las
TrackingTools/ReportTools/AppTools nuevas, en un único catálogo para agent.py.

Este archivo existe para que agent.py tenga un solo punto de import (`registry`) y nunca tenga
que conocer cuántos módulos de tools hay ni cómo están organizados -- agregar una categoría nueva
en el futuro es editar esta lista, no tocar agent.py otra vez.
"""
from __future__ import annotations

from google.genai import types

from . import tools, tools_app, tools_reportes, tools_tracking

TODAS_LAS_HERRAMIENTAS = types.Tool(
    function_declarations=(
        list(tools.HERRAMIENTAS.function_declarations)
        + tools_tracking.DECLARACIONES
        + tools_reportes.DECLARACIONES
        + tools_app.DECLARACIONES
    )
)

TODOS_LOS_EJECUTORES = {
    **tools.EJECUTORES,
    **tools_tracking.EJECUTORES,
    **tools_reportes.EJECUTORES,
    **tools_app.EJECUTORES,
}

# Salvaguarda: si dos módulos declaran una tool con el mismo nombre, es un error de programación
# que debe fallar RUIDOSAMENTE al arrancar el proceso, no en silencio en medio de una conversación.
_nombres_declarados = [f.name for f in TODAS_LAS_HERRAMIENTAS.function_declarations]
if len(_nombres_declarados) != len(set(_nombres_declarados)):
    duplicados = {n for n in _nombres_declarados if _nombres_declarados.count(n) > 1}
    raise RuntimeError(f"Tools con nombres duplicados en el registry: {duplicados}")
if set(_nombres_declarados) != set(TODOS_LOS_EJECUTORES.keys()):
    raise RuntimeError(
        "Las FunctionDeclaration y los EJECUTORES del registry no coinciden exactamente: "
        f"declaradas={set(_nombres_declarados)} ejecutores={set(TODOS_LOS_EJECUTORES.keys())}"
    )
