#!/bin/sh
set -eu
TRACESCOPE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if docker compose version >/dev/null 2>&1; then
  exec docker compose -f "$TRACESCOPE_ROOT/deploy/compose/docker-compose.yml" "$@"
elif command -v docker-compose >/dev/null 2>&1; then
  exec docker-compose -f "$TRACESCOPE_ROOT/deploy/compose/docker-compose.yml" "$@"
else
  echo 'Docker Compose is required (docker compose or docker-compose).' >&2
  exit 1
fi
