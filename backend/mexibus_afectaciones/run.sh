#!/usr/bin/env bash
# Arranque local / EC2. Crea venv, instala deps y levanta uvicorn.
set -euo pipefail
cd "$(dirname "$0")"

python3 -m venv .venv 2>/dev/null || true
source .venv/bin/activate
pip install -q -r requirements.txt

[ -f .env ] || cp .env.example .env
export $(grep -v '^#' .env | grep -E '^MXB_HOST|^MXB_PORT' | xargs -d '\n' -r) 2>/dev/null || true

exec uvicorn app.main:app --host "${MXB_HOST:-0.0.0.0}" --port "${MXB_PORT:-8080}"
