"""Las "funciones de GeoMB" que Gemini puede invocar (Function Calling).

Cada función Python de abajo tiene su espejo en `HERRAMIENTAS` (el esquema que se le manda
a Gemini) -- el NOMBRE debe coincidir exactamente. Si agregas una función nueva, agrégala
también a HERRAMIENTAS y a EJECUTORES.
"""
from __future__ import annotations

from typing import Any

from google.genai import types

from . import backend_client, catalogo_unidades, data_loader as dl, graph


# ============================================================
# Implementación real de cada herramienta (async: alguna pega al backend en vivo)
# ============================================================

def _direccion_cardinal(grados: float) -> str:
    """Grados (0-359, convención GPS estándar: 0=Norte, 90=Este, clockwise) -> Norte/Sur/Este/
    Oeste. MISMA convención que ya usa Android: RealtimeRepository lee 'bearing' del feed tal
    cual (sin reescalar) y lo usa directo como rotación de Marker (grados, 0=norte, sentido
    horario) -- confirmado en código, no asumido."""
    g = grados % 360
    if g < 45 or g >= 315:
        return "Norte"
    if g < 135:
        return "Este"
    if g < 225:
        return "Sur"
    return "Oeste"


def _nombre_linea(valor_line: Any) -> str | None:
    """'line' del feed (string u int, según venga el JSON -- mismo criterio defensivo que
    RealtimeRepository.java: parseInt con try/except, nunca lanza) -> nombre de exhibición
    ('Metrobús L2', etc.) vía data_loader, SIN duplicar su tabla de líneas."""
    try:
        numero = int(str(valor_line).strip())
    except (TypeError, ValueError):
        return None
    l = dl.linea_por_numero(numero)
    return l.nombre if l else None


async def buscar_unidad(economico: str) -> dict[str, Any]:
    """Busca una unidad en el feed en vivo por su número económico (coincidencia exacta, sin
    distinguir mayúsculas) y la complementa con: (a) su ficha de catálogo (marca/modelo/
    empresa/imagen/credito, ver catalogo_unidades.py) cuando existe, y (b) -si está en vivo-
    el nombre de línea resuelto y la dirección cardinal aproximada de su rumbo. Catálogo y feed
    en vivo son independientes: una unidad puede estar catalogada aunque no esté transmitiendo
    ahora (y viceversa) -- 'encontrada' SIGUE significando exactamente lo mismo que antes
    (presente en el feed en vivo AHORA), nunca "existe en el catálogo"; eso es lo que valida
    seguir_unidad antes de proponer el seguimiento, así que ese contrato no cambia.

    NO calcula "próxima estación": el feed no la trae y el backend no tiene la geometría real
    de la ruta (eso vive solo en Linea.java/segmentos.json, del lado Android) -- inventarla a
    partir de la estación geográficamente más cercana ignoraría el sentido real de circulación,
    así que deliberadamente se omite en vez de aproximarla mal."""
    vehiculos = await backend_client.obtener_vehiculos()
    eco = economico.strip().lower()
    unidad_en_vivo = None
    for v in vehiculos:
        label = str(v.get("label") or v.get("id") or "").strip().lower()
        if label == eco:
            unidad_en_vivo = v
            break

    ficha = await catalogo_unidades.ficha(economico)
    tipo = empresa = imagen = credito = None
    if ficha:
        tipo = (f"{ficha.get('marca', '')} {ficha.get('modelo', '')}").strip() or None
        empresa = ficha.get("empresa") or None
        imagen = ficha.get("imagen") or None
        credito = ficha.get("credito") or None

    if unidad_en_vivo is None:
        if tipo is None:
            return {"encontrada": False, "mensaje": f"No se encontró la unidad '{economico}' en el feed actual."}
        resultado: dict[str, Any] = {
            "encontrada": False,
            "tipo": tipo,
            "mensaje": f"La unidad '{economico}' no está transmitiendo en vivo ahora, pero sí está catalogada como {tipo}.",
        }
        if empresa:
            resultado["empresa"] = empresa
        if imagen:
            resultado["imagen"] = imagen
        if credito:
            resultado["credito"] = credito
        return resultado

    resultado = {"encontrada": True, "unidad": unidad_en_vivo}
    if tipo:
        resultado["tipo"] = tipo
    if empresa:
        resultado["empresa"] = empresa
    if imagen:
        resultado["imagen"] = imagen
    if credito:
        resultado["credito"] = credito

    nombre_linea = _nombre_linea(unidad_en_vivo.get("line"))
    if nombre_linea:
        resultado["linea_nombre"] = nombre_linea

    bearing = unidad_en_vivo.get("bearing")
    if bearing is not None:
        try:
            resultado["direccion_cardinal"] = _direccion_cardinal(float(bearing))
        except (TypeError, ValueError):
            pass

    return resultado


async def estado_servicio(linea: str = "") -> dict[str, Any]:
    """Estado actual del servicio. Si se da 'linea' (formato '<sistema>:<numero>'), filtra
    solo esa línea; si no, devuelve TODAS las afectaciones activas (Metrobús + Mexibús +
    avisos manuales)."""
    afectaciones = await backend_client.obtener_afectaciones()
    numero = dl.resolver_linea_id(linea) if linea else None
    if numero is not None:
        afectaciones = [a for a in afectaciones if a.get("linea") == numero]
        if not afectaciones:
            return {"servicio_regular": True, "mensaje": "Sin afectaciones activas en esta línea."}
    elif not afectaciones:
        return {"servicio_regular": True, "mensaje": "Sin afectaciones activas en ninguna línea."}
    return {"servicio_regular": False, "afectaciones": afectaciones}


def info_linea(linea: str) -> dict[str, Any]:
    """Nombre, número de estaciones y catálogo de paradas de una línea."""
    numero = dl.resolver_linea_id(linea)
    if numero is None:
        return {"error": f"No se reconoce el identificador de línea '{linea}'. "
                          "Formato esperado: '<sistema>:<numero>', p. ej. 'metrobus:2'."}
    l = dl.linea_por_numero(numero)
    if l is None:
        return {"error": f"No hay datos locales para la línea '{linea}' (número interno {numero})."}
    return {
        "numero_interno": l.numero,
        "nombre": l.nombre,
        "num_estaciones": len(l.estaciones),
        "estaciones": [e.nombre for e in l.estaciones],
    }


def buscar_estacion(nombre: str) -> dict[str, Any]:
    """Estaciones (con su línea) cuyo nombre coincide (parcial, sin tildes) con la consulta."""
    resultados = dl.buscar_estaciones(nombre)
    if not resultados:
        return {"encontradas": 0, "estaciones": []}
    return {
        "encontradas": len(resultados),
        "estaciones": [
            {"linea": l.nombre, "linea_numero": l.numero, "estacion": e.nombre}
            for l, e in resultados
        ],
    }


def planificar_ruta(origen: str, destino: str) -> dict[str, Any]:
    """Ruta sugerida entre dos estaciones (nombres, no requieren tildes exactas), usando
    el grafo simplificado de líneas troncales (ver graph.py para las limitaciones)."""
    return graph.planificar(origen, destino)


# ============================================================
# Esquema que Gemini ve (nombres y tipos EXACTOS -- ver docs de Function Calling)
# ============================================================

_LINEA_DESC = (
    "Identificador de línea en el formato '<sistema>:<numero>'. Sistema es uno de: "
    "metrobus, mexibus, mexibus_ramal, mexibus_expres, mexicable. Ejemplos: 'metrobus:2' "
    "(Línea 2 del Metrobús), 'mexibus:3' (Mexibús L3 ordinario), 'mexibus_ramal:1' "
    "(Mexibús L1A), 'mexibus_expres:2' (Mexibús L2 Exprés), 'mexicable:1' (Mexicable L1)."
)

HERRAMIENTAS = types.Tool(function_declarations=[
    types.FunctionDeclaration(
        name="buscar_unidad",
        description=(
            "Busca una unidad (camión/autobús) por su número económico: posición/velocidad/línea/"
            "dirección cardinal aproximada (si está transmitiendo en vivo) Y su ficha de catálogo "
            "(marca/modelo/empresa/imagen/crédito), cuando exista. NO incluye la próxima estación "
            "(no hay una fuente confiable para eso todavía -- si te la piden, dilo con honestidad, "
            "nunca la estimes). Úsala para CUALQUIER pregunta informativa sobre una unidad concreta "
            "-- '¿dónde está la X?', '¿qué es la X?', '¿qué modelo es la X?', '¿hacia dónde va la "
            "X?' -- nunca para iniciar o detener su seguimiento (eso son seguirUnidad/"
            "dejarDeSeguirUnidad, acciones distintas que SIEMPRE requieren confirmación explícita "
            "del usuario)."
        ),
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "economico": types.Schema(type=types.Type.STRING, description="Número económico de la unidad, p. ej. '9507'."),
            },
            required=["economico"],
        ),
    ),
    types.FunctionDeclaration(
        name="estado_servicio",
        description="Estado del servicio (afectaciones, manifestaciones, mantenimiento) de una línea o de toda la red.",
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "linea": types.Schema(type=types.Type.STRING, description=_LINEA_DESC + " Déjalo vacío para consultar TODA la red."),
            },
        ),
    ),
    types.FunctionDeclaration(
        name="info_linea",
        description="Nombre, terminales y lista completa de estaciones de una línea específica.",
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "linea": types.Schema(type=types.Type.STRING, description=_LINEA_DESC),
            },
            required=["linea"],
        ),
    ),
    types.FunctionDeclaration(
        name="buscar_estacion",
        description="Busca en qué línea(s) existe una estación por su nombre (búsqueda parcial).",
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "nombre": types.Schema(type=types.Type.STRING, description="Nombre (completo o parcial) de la estación a buscar."),
            },
            required=["nombre"],
        ),
    ),
    types.FunctionDeclaration(
        name="planificar_ruta",
        description="Sugiere cómo llegar de una estación de origen a una de destino, incluyendo transbordos.",
        parameters=types.Schema(
            type=types.Type.OBJECT,
            properties={
                "origen": types.Schema(type=types.Type.STRING, description="Nombre de la estación de origen."),
                "destino": types.Schema(type=types.Type.STRING, description="Nombre de la estación de destino."),
            },
            required=["origen", "destino"],
        ),
    ),
])

# Nombre -> función Python real. Las async se detectan y se awaitean en agent.py.
EJECUTORES = {
    "buscar_unidad": buscar_unidad,
    "estado_servicio": estado_servicio,
    "info_linea": info_linea,
    "buscar_estacion": buscar_estacion,
    "planificar_ruta": planificar_ruta,
}
