"""Configuración central de la integración (equivalente al Config.java de la app).

Todo se lee de variables de entorno (ver .env.example) para no dejar nada hardcodeado.
"""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

# --- Gemini ---
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.8-flash")

# --- Control de costo (ver ratelimit.py) ---
LIMITE_MENSAJES_DIA = int(os.environ.get("GEOMB_LIMITE_MENSAJES_DIA", "30"))

# --- Backend real de GeoMB (mismo que usa la app Android, ver Config.java) ---
GEOMB_BASE_URL = os.environ.get("GEOMB_BASE_URL", "https://geomb.duckdns.org")
GEOMB_FALLBACK_URL = os.environ.get(
    "GEOMB_FALLBACK_URL", "https://web-production-6a6c6.up.railway.app"
)
PATH_VEHICLES = "/data/vehicles.json"
PATH_AFECT_MXB = "/data/afectaciones_mexibus.json"  # incluye Metrobús + Mexibús + avisos manuales
HTTP_TIMEOUT_S = float(os.environ.get("GEOMB_HTTP_TIMEOUT_S", "10"))

# --- Catálogo de marca/modelo por económico (ver catalogo_unidades.py) ---
# MISMO link público "publicar en la web" que ya usa Android (Config.java#MODELOS_URL) -- no
# es una credencial, solo un CSV de solo lectura sin autenticación.
MODELOS_URL = os.environ.get(
    "GEOMB_MODELOS_URL",
    "https://docs.google.com/spreadsheets/d/e/2PACX-1vSzbtEUq4-cocjqJOydQZj5HnWLmD4_oURYbXzNLu2wxvSGZxkUMq3QQ-rwVb2_5KB1GyYLs0ddpydR/pub?gid=0&single=true&output=csv",
)

# --- Datos locales (los mismos assets que empaqueta la app Android) ---
# Por defecto asume que este script vive dentro del repo GeoMB, en integrations/gemini/.
_REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = Path(os.environ.get("GEOMB_ASSETS_DIR", _REPO_ROOT / "app/src/main/assets"))

LINEAS_JSON = ASSETS_DIR / "lineas.json"
MEXIBUS_JSON = ASSETS_DIR / "mexibus.json"

# --- Servidor ---
SERVER_HOST = os.environ.get("GEOMB_AGENT_HOST", "0.0.0.0")
SERVER_PORT = int(os.environ.get("GEOMB_AGENT_PORT", "8000"))
