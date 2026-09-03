"""Fuente X (Twitter) via API v2.

Requiere un Bearer Token (MXB_X_BEARER_TOKEN). La API v2 de recuperacion de
timelines es de PAGO (tier Basic en adelante). Sin token, esta fuente no hace
nada (devuelve lista vacia) y lo registra en el log.

Endpoints usados (cuando hay token):
  GET /2/users/by/username/:handle      -> id de usuario
  GET /2/users/:id/tweets               -> tweets recientes
Docs: https://developer.x.com/en/docs/x-api
"""
from __future__ import annotations

import logging
from datetime import datetime

import httpx

from ..config import settings
from ..models import RawPost
from .base import Source

log = logging.getLogger("mxb.x")
API = "https://api.x.com/2"


class XSource(Source):
    plataforma = "x"

    async def obtener(self) -> list[RawPost]:
        token = settings.x_bearer_token.strip()
        if not token:
            log.warning("X: sin MXB_X_BEARER_TOKEN, fuente inactiva (%d cuentas)", len(self.cuentas))
            return []

        headers = {"Authorization": f"Bearer {token}"}
        posts: list[RawPost] = []
        async with httpx.AsyncClient(timeout=15, headers=headers) as cli:
            for c in self.cuentas:
                try:
                    posts.extend(await self._cuenta(cli, c))
                except Exception as e:  # una cuenta caida no tumba las demas
                    log.error("X @%s: %s", c.get("ref"), e)
        return posts

    async def _cuenta(self, cli: httpx.AsyncClient, c: dict) -> list[RawPost]:
        handle = c["ref"]
        u = await cli.get(f"{API}/users/by/username/{handle}")
        u.raise_for_status()
        uid = u.json()["data"]["id"]

        params = {
            "max_results": 10,
            "tweet.fields": "created_at,text",
            "exclude": "retweets,replies",
        }
        r = await cli.get(f"{API}/users/{uid}/tweets", params=params)
        r.raise_for_status()
        out: list[RawPost] = []
        for t in r.json().get("data", []):
            fecha = None
            if t.get("created_at"):
                fecha = datetime.fromisoformat(t["created_at"].replace("Z", "+00:00"))
            out.append(RawPost(
                fuente=c.get("nombre", handle),
                plataforma="x",
                linea_hint=c.get("linea"),
                post_id=f"x:{t['id']}",
                texto=t.get("text", ""),
                url=f"https://x.com/{handle}/status/{t['id']}",
                fecha=fecha,
            ))
        return out
