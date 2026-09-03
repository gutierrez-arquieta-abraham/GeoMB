"""Fuente Facebook via Graph API.

Requiere un Page Access Token (MXB_FB_PAGE_ACCESS_TOKEN) con permiso para leer el
feed de la pagina. Solo funciona con paginas que administras o que te dan acceso;
leer paginas de terceros publicas ya NO es abierto en Graph API. Sin token, esta
fuente devuelve lista vacia.

Para 'ref' se acepta el username (Mexibus3) o el id numerico (100064783413610).
Docs: https://developers.facebook.com/docs/graph-api
"""
from __future__ import annotations

import logging
from datetime import datetime

import httpx

from ..config import settings
from ..models import RawPost
from .base import Source

log = logging.getLogger("mxb.fb")


class FacebookSource(Source):
    plataforma = "facebook"

    async def obtener(self) -> list[RawPost]:
        token = settings.fb_page_access_token.strip()
        if not token:
            log.warning("FB: sin MXB_FB_PAGE_ACCESS_TOKEN, fuente inactiva (%d cuentas)", len(self.cuentas))
            return []

        base = f"https://graph.facebook.com/{settings.fb_graph_version}"
        posts: list[RawPost] = []
        async with httpx.AsyncClient(timeout=15) as cli:
            for c in self.cuentas:
                try:
                    posts.extend(await self._cuenta(cli, base, token, c))
                except Exception as e:
                    log.error("FB %s: %s", c.get("ref"), e)
        return posts

    async def _cuenta(self, cli, base, token, c) -> list[RawPost]:
        ref = c["ref"]
        params = {
            "fields": "id,message,created_time,permalink_url",
            "limit": 10,
            "access_token": token,
        }
        r = await cli.get(f"{base}/{ref}/posts", params=params)
        r.raise_for_status()
        out: list[RawPost] = []
        for p in r.json().get("data", []):
            msg = p.get("message")
            if not msg:
                continue
            fecha = None
            if p.get("created_time"):
                fecha = datetime.fromisoformat(p["created_time"].replace("Z", "+00:00"))
            out.append(RawPost(
                fuente=c.get("nombre", ref),
                plataforma="facebook",
                linea_hint=c.get("linea"),
                post_id=f"fb:{p['id']}",
                texto=msg,
                url=p.get("permalink_url", c.get("url", "")),
                fecha=fecha,
            ))
        return out
