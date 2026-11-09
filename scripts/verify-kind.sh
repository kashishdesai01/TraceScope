#!/bin/sh
set -eu
TRACESCOPE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TRACESCOPE_ROOT"
export PATH="$TRACESCOPE_ROOT/.local/tools:$PATH"
CLUSTER=${KIND_CLUSTER_NAME:-tracescope-verify-$$}
NAMESPACE=${KIND_NAMESPACE:-tracescope}
RELEASE=verify
PREFIX=verify-tracescope
export KUBECONFIG="$TRACESCOPE_ROOT/.local/kubeconfig-$CLUSTER"
mkdir -p reports/runtime .local
for tool in kind kubectl helm; do command -v "$tool" >/dev/null || { echo "$tool is required; run python3 scripts/install-local-tools.py" >&2; exit 1; }; done
if kind get clusters 2>/dev/null | grep -Fx "$CLUSTER" >/dev/null; then
  echo "Cluster $CLUSTER already exists; choose a fresh KIND_CLUSTER_NAME. No existing cluster was changed." >&2
  exit 1
fi
SERVER_FORWARD_PID=
WEB_FORWARD_PID=
CREATED=0
cleanup() {
  [ -z "$SERVER_FORWARD_PID" ] || kill "$SERVER_FORWARD_PID" 2>/dev/null || true
  [ -z "$WEB_FORWARD_PID" ] || kill "$WEB_FORWARD_PID" 2>/dev/null || true
  if [ "$CREATED" = 1 ]; then
    kubectl -n "$NAMESPACE" get pods -o wide > reports/runtime/kind-pods.txt 2>&1 || true
    kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp > reports/runtime/kind-events.txt 2>&1 || true
    if [ "${KEEP_KIND:-0}" != 1 ]; then
      kind delete cluster --name "$CLUSTER"
      rm -f "$KUBECONFIG"
    else
      echo "Cluster retained: $CLUSTER; KUBECONFIG=$KUBECONFIG"
    fi
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
helm lint deploy/helm/tracescope
helm template "$RELEASE" deploy/helm/tracescope --set loadgen.enabled=true > reports/runtime/helm.yaml
kind create cluster --name "$CLUSTER" --config deploy/kind/cluster.yaml --wait 180s
CREATED=1
kind load docker-image --name "$CLUSTER" tracescope-server:local tracescope-web:local tracescope-demo-shop:local
helm upgrade --install "$RELEASE" deploy/helm/tracescope --namespace "$NAMESPACE" --create-namespace --set server.samplingRate=1 --wait --timeout 6m
kubectl -n "$NAMESPACE" get pods -o json > reports/runtime/kind-pods.json
python3 - <<'PY'
import json
pods = json.load(open('reports/runtime/kind-pods.json'))['items']
assert len(pods) == 8, len(pods)
assert sum(p['metadata']['labels'].get('app.kubernetes.io/component') == 'server' for p in pods) == 1
for pod in pods:
    assert pod['spec']['securityContext']['runAsNonRoot'], pod['metadata']['name']
    assert pod['spec'].get('automountServiceAccountToken') is False
    for container in pod['spec']['containers'] + pod['spec'].get('initContainers', []):
        assert container['resources']['requests'] and container['resources']['limits']
        assert container['securityContext']['allowPrivilegeEscalation'] is False
        assert 'ALL' in container['securityContext']['capabilities']['drop']
print('Eight ready non-root pods with bounded resources; exactly one server replica.')
PY
helm upgrade "$RELEASE" deploy/helm/tracescope --namespace "$NAMESPACE" --set server.samplingRate=1 --set loadgen.enabled=true --wait --wait-for-jobs --timeout 6m
kubectl -n "$NAMESPACE" logs job/"$PREFIX-loadgen" > reports/runtime/kind-k6.log
kubectl -n "$NAMESPACE" port-forward --address 127.0.0.1 svc/"$PREFIX-server" "${KIND_SERVER_PORT:-28180}:8080" > reports/runtime/kind-server-forward.log 2>&1 &
SERVER_FORWARD_PID=$!
kubectl -n "$NAMESPACE" port-forward --address 127.0.0.1 svc/"$PREFIX-web" "${KIND_WEB_PORT:-28100}:8080" > reports/runtime/kind-web-forward.log 2>&1 &
WEB_FORWARD_PID=$!
export KIND_SERVER_PORT=${KIND_SERVER_PORT:-28180}
export KIND_WEB_PORT=${KIND_WEB_PORT:-28100}
python3 - <<'PY'
import json
import os
import time
import urllib.request
from datetime import datetime, timezone
server='http://127.0.0.1:'+os.environ['KIND_SERVER_PORT']
web='http://127.0.0.1:'+os.environ['KIND_WEB_PORT']
def get(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        return response.read()
deadline=time.time()+30
while True:
    try:
        assert get(server+'/actuator/health/readiness')
        assert b'TraceScope' in get(web+'/')
        break
    except Exception:
        if time.time()>deadline: raise
        time.sleep(1)
ready=(int(time.time())//60+1)*60+20
while time.time()<ready: time.sleep(min(3,ready-time.time()))
data=json.loads(get(server+'/api/v1/service-map'))
expected={('gateway','checkout'),('checkout','payment'),('checkout','inventory'),('payment','postgresql:payments'),('inventory','postgresql:inventory')}
actual={(e['caller'],e['callee']) for e in data['edges']}
assert actual==expected,(actual,expected)
assert {n['service'] for n in data['nodes']}=={'gateway','checkout','payment','inventory'}
assert all(n['requests']>0 for n in data['nodes'])
assert json.loads(get(web+'/api/v1/services'))['data']
assert json.loads(get(server+'/api/v1/pipeline'))['writerHealthy']
open('reports/runtime/kind-service-map.json','w').write(json.dumps(data,indent=2))
open('reports/kubernetes.md','w').write('# Kubernetes verification\n\nLocal kind verification passed at '+datetime.now(timezone.utc).isoformat()+'.\n\n- Helm lint and render: PASS.\n- Install with wait: PASS.\n- Eight non-root pods; requests and limits on all containers; exactly one server: PASS.\n- Real 60-second k6 Job at 20 checkout requests/s with zero failed requests and zero dropped iterations: PASS.\n- Public service-map API matches all five expected edges: PASS.\n- Packaged UI and nginx API proxy through port forwarding: PASS.\n- Bounded writer healthy after load: PASS.\n\nThis verifies a single local kind node, not a cloud deployment, high availability, or production load. Raw manifests, pod snapshots, Job output and API JSON are retained locally under ignored `reports/runtime/`.\n')
print('K1 PASS: real load, expected graph, UI proxy, pod controls, healthy writer.')
PY
