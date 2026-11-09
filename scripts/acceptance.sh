#!/bin/sh
set -eu
TRACESCOPE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TRACESCOPE_ROOT"
export TRACESCOPE_PROJECT="tracescope-acceptance-$(date -u +%Y%m%d%H%M%S)-$$"
export SERVER_PORT=${ACCEPTANCE_SERVER_PORT:-28080}
export GATEWAY_PORT=${ACCEPTANCE_GATEWAY_PORT:-28081}
export CHECKOUT_PORT=${ACCEPTANCE_CHECKOUT_PORT:-28082}
export PAYMENT_PORT=${ACCEPTANCE_PAYMENT_PORT:-28083}
export INVENTORY_PORT=${ACCEPTANCE_INVENTORY_PORT:-28084}
export WEB_PORT=${ACCEPTANCE_WEB_PORT:-28000}
export LOADGEN_UID=$(id -u)
export LOADGEN_GID=$(id -g)
export SAMPLING_RATE=1
export TRACESCOPE_ACCEPTANCE=true
export TRACESCOPE_URL="http://127.0.0.1:$SERVER_PORT"
export GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT"
export CHECKOUT_URL="http://127.0.0.1:$CHECKOUT_PORT"
export PAYMENT_URL="http://127.0.0.1:$PAYMENT_PORT"
export INVENTORY_URL="http://127.0.0.1:$INVENTORY_PORT"
mkdir -p reports/runtime
cleanup() {
  # A killed test worker can leave its one-off load container alive. Scope cleanup
  # to this unique project; never touch an unrelated application or load run.
  load_ids=$(docker ps -aq --filter "label=com.docker.compose.project=$TRACESCOPE_PROJECT" --filter "label=com.docker.compose.service=loadgen")
  if [ -n "$load_ids" ]; then docker rm -f $load_ids >/dev/null 2>&1 || true; fi
  ./scripts/compose.sh -p "$TRACESCOPE_PROJECT" logs --no-color > reports/runtime/acceptance-compose.log 2>&1 || true
  if [ "${KEEP_ACCEPTANCE:-0}" != 1 ]; then
    ./scripts/compose.sh -p "$TRACESCOPE_PROJECT" down -v --remove-orphans
  else
    echo "Acceptance stack retained: $TRACESCOPE_PROJECT"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
./scripts/compose.sh -p "$TRACESCOPE_PROJECT" up -d --no-build --wait --wait-timeout 240 server web gateway checkout payment inventory
docker image inspect tracescope-server:local tracescope-demo-shop:local tracescope-web:local > reports/runtime/acceptance-images.json
./gradlew :tests:acceptance:test --rerun-tasks --no-daemon
if [ "${SKIP_BROWSER:-0}" != 1 ]; then
  python3 scripts/prepare-browser-demo.py
  WEB_URL="http://127.0.0.1:$WEB_PORT" npm --prefix web run test:e2e
fi
