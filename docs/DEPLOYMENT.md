# Deployment

## Compose

`make dev` builds the three application images once, then starts the health-gated stack. The backend, gateway, checkout, payment and inventory bind to loopback ports 8080–8084; UI binds to loopback 3000. Databases remain internal. Override the documented port variables before `make dev` for an occupied port.

`make down` stops the application and preserves volumes. Use `./scripts/compose.sh logs --tail 100 server` to inspect bounded-writer warnings. `/api/v1/pipeline` and `/actuator/prometheus` expose pipeline capacity and writer health.

Tokens/passwords are configured with `MCP_TOKEN`, `CHAOS_TOKEN`, `POSTGRES_PASSWORD` and `CLICKHOUSE_PASSWORD`. Defaults are intentionally local demo values. This deployment does not provide user authentication, TLS or an internet security boundary. Restrict it to synthetic data and loopback access.

## kind and Helm

Project-local tooling is optional; the installer downloads pinned official binaries and checks SHA-256 sums without modifying global tools. `make kind` retains a fresh verification cluster; the printed kubeconfig path is separate from your normal Kubernetes context.

After the verification script prints that path:

```sh
export KUBECONFIG=/absolute/path/printed/by/the/script
kubectl -n tracescope port-forward svc/verify-tracescope-web 3000:8080
kubectl -n tracescope port-forward svc/verify-tracescope-server 8080:8080
```

Use separate terminals for the two forwards. The server still requires its bearer token at `/mcp`.

For manual installation into an existing cluster, build/load the images into that cluster first, then:

```sh
helm upgrade --install demo deploy/helm/tracescope \
  --namespace tracescope --create-namespace --wait --timeout 6m
```

Services use the release prefix, for example `demo-tracescope-web`. Enable generated load only when appropriate with `--set loadgen.enabled=true`. The Job waits for the shop, runs 60 seconds at 20 requests/s, and fails for unsuccessful requests or dropped iterations. Its default duration is a verification workload, not an always-running generator.

One server replica is enforced by chart schema; Recreate rollout prevents overlapping process-local ownership. Data volumes survive Helm uninstall. The chart does not provision backups, cloud credentials, ingress, managed databases, durable ingestion or multi-user authorization. Installing into a cloud cluster needs a separate operational/security design beyond storage-class changes.

Delete only the project-owned verification cluster using the name printed by the script:

```sh
kind delete cluster --name THE_PRINTED_CLUSTER_NAME
```

## Failure behavior

In-memory admission can be acknowledged before data reaches ClickHouse. Bounded writer frames retry with stable identities and apply backpressure during outages. A process crash loses active and queued work; a partial cross-table write can remain. Logs and metrics expose failures but do not supply replay. This is an explicit scope limit rather than a production durability guarantee.
