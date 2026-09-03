"""Fuente X SIN costo (no oficial). Dos estrategias, en orden:

  1) Nitter RSS  -> si defines MXB_NITTER_BASE (ideal: tu propia instancia).
                    Lee https://<nitter>/<handle>/rss
  2) Sindicación -> endpoint de timelines embebidos de X (cdn.syndication.twimg.com).
                    No documentado; puede dejar de funcionar cuando X lo cambie.

AVISO: ambas rutas son NO oficiales y pueden violar los ToS de X. Úsalas bajo tu
propio criterio. Si tienes Bearer Token, prefiere `twitter_x.XSource` (oficial).

Se activa cuando NO hay bearer token y MXB_X_FREE=true.
"""
from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import httpx

from ..config import settings
from ..models import RawPost
from .base import Source

log = logging.getLogger("mxb.xfree")

UA = "Mozilla/5.0 (compatible; MexibusAfectaciones/1.0)"


class XFreeSource(Source):
    plataforma = "x"

    async def obtener(self) -> list[RawPost]:
        posts: list[RawPost] = []
        async with httpx.AsyncClient(timeout=15, headers={"User-Agent": UA},
                                     follow_redirects=True) as cli:
            for c in self.cuentas:
                handle = c["ref"]
                try:
                    got = await self._nitter(cli, handle, c) if settings.nitter_base \
                        else await self._syndication(cli, handle, c)
                    if not got and settings.nitter_base:
                        got = await self._syndication(cli, handle, c)  # respaldo
                    posts.extend(got)
                except Exception as e:
                    log.error("Xfree @%s: %s", handle, e)
        return posts

    # ---- 1) Nitter RSS ----
    async def _nitter(self, cli, handle, c) -> list[RawPost]:
        base = settings.nitter_base.rstrip("/")
        r = await cli.get(f"{base}/{handle}/rss")
        r.raise_for_status()
        out: list[RawPost] = []
        for m in re.finditer(r"<item>(.*?)</item>", r.text, re.S):
            item = m.group(1)
            desc = _tag(item, "description")
            texto = _strip_html(desc) or _strip_html(_tag(item, "title"))
            link = _tag(item, "link")
            pub = _tag(item, "pubDate")
            fecha = _rss_date(pub)
            pid = link.rsplit("/", 1)[-1] if link else str(hash(texto))
            if texto:
                out.append(_post(c, handle, pid, texto, link, fecha))
        return out

    # ---- 2) Sindicación (timeline embebido) ----
    async def _syndication(self, cli, handle, c) -> list[RawPost]:
        url = "https://cdn.syndication.twimg.com/timeline/profile"
        r = await cli.get(url, params={"screen_name": handle})
        r.raise_for_status()
        data = r.json()
        tweets = (data.get("props", {}).get("pageProps", {}).get("timeline", {}) or {}).get("entries", [])
        out: list[RawPost] = []
        for e in tweets:
            content = (e.get("content") or {}).get("tweet") or {}
            texto = content.get("full_text") or content.get("text") or ""
            tid = str(content.get("id_str") or content.get("id") or "")
            created = content.get("created_at")
            fecha = _tw_date(created)
            if texto and tid:
                out.append(_post(c, handle, tid, texto,
                                 f"https://x.com/{handle}/status/{tid}", fecha))
        return out


# ---- helpers ----
def _post(c, handle, pid, texto, link, fecha) -> RawPost:
    return RawPost(
        fuente=c.get("nombre", handle), plataforma="x", linea_hint=c.get("linea"),
        post_id=f"x:{pid}", texto=texto, url=link or f"https://x.com/{handle}", fecha=fecha,
    )


def _tag(s: str, t: str) -> str:
    m = re.search(rf"<{t}>(.*?)</{t}>", s, re.S)
    if not m:
        return ""
    v = m.group(1)
    cm = re.match(r"\s*<!\[CDATA\[(.*?)\]\]>\s*$", v, re.S)
    return (cm.group(1) if cm else v).strip()


def _strip_html(s: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", s or "")).strip()


def _rss_date(s: str):
    try:
        return parsedate_to_datetime(s).astimezone(timezone.utc) if s else None
    except Exception:
        return None


def _tw_date(s: str):
    for fmt in ("%a %b %d %H:%M:%S %z %Y", "%Y-%m-%dT%H:%M:%S.%fZ"):
        try:
            d = datetime.strptime(s, fmt)
            return d.astimezone(timezone.utc) if d.tzinfo else d.replace(tzinfo=timezone.utc)
        except Exception:
            continue
    return None
