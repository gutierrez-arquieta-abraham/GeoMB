"""Configuracion del servicio (variables de entorno / .env).

Ninguna credencial va hardcodeada: se leen del entorno. Si faltan, las fuentes
que las necesitan quedan inactivas (el servicio sigue corriendo con las demas).
"""
from __future__ import annotations

from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="MXB_", extra="ignore")

    # --- Servidor ---
    host: str = "0.0.0.0"
    port: int = 8080

    # --- Poller (cada cuanto refresca las redes) ---
    poll_segundos: int = 180              # 3 min; MexibusInforma es "lento" pero seguro
    ventana_minutos: int = 240            # solo posts de las ultimas N min cuentan como afectacion vigente

    # --- Rutas de datos ---
    catalog_path: Path = DATA_DIR / "mexibus_catalog.json"
    accounts_path: Path = DATA_DIR / "accounts.json"

    # --- Credenciales X (Twitter API v2). Sin esto, la fuente X oficial queda inactiva. ---
    x_bearer_token: str = ""

    # --- X SIN costo (no oficial). Solo se usa si NO hay bearer token y x_free=true. ---
    #     Ideal: MXB_NITTER_BASE con tu propia instancia de Nitter (p. ej. https://nitter.tudominio.com).
    #     Si no defines nitter_base, intenta el endpoint de sindicacion de X (fragil).
    x_free: bool = False
    nitter_base: str = ""

    # --- Credenciales Facebook (Graph API). Sin esto, la fuente FB queda inactiva. ---
    fb_page_access_token: str = ""
    fb_graph_version: str = "v20.0"

    # --- Modo demo: usa la fuente 'mock' (posts de ejemplo) para probar sin credenciales ---
    usar_mock: bool = True


settings = Settings()
