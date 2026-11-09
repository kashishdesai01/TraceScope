# Kubernetes verification

Local kind verification passed at 2026-11-09T16:42:20.149521+00:00.

- Helm lint and render: PASS.
- Install with wait: PASS.
- Eight non-root pods; requests and limits on all containers; exactly one server: PASS.
- Real 60-second k6 Job at 20 checkout requests/s with zero failed requests and zero dropped iterations: PASS.
- Public service-map API matches all five expected edges: PASS.
- Packaged UI and nginx API proxy through port forwarding: PASS.
- Bounded writer healthy after load: PASS.

This verifies a single local kind node, not a cloud deployment, high availability, or production load. Raw manifests, pod snapshots, Job output and API JSON are retained locally under ignored `reports/runtime/`.
