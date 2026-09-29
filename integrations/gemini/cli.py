"""Chat de prueba por terminal, sin levantar el servidor:

    python -m integrations.gemini.cli
"""
from __future__ import annotations

import asyncio
import logging

from .agent import GeoMBAgent


async def _main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    agente = GeoMBAgent()
    print("GeoMB + Gemini (Function Calling). Ctrl+C o 'salir' para terminar.\n")
    while True:
        try:
            mensaje = input("Tú: ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not mensaje:
            continue
        if mensaje.lower() in {"salir", "exit", "quit"}:
            break
        respuesta = await agente.responder(mensaje)
        print(f"GeoMB: {respuesta}\n")


if __name__ == "__main__":
    asyncio.run(_main())
