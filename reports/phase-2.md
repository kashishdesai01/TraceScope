# Phase 2 — bounded pipeline and real-agent vertical slice

Verified locally on 2026-11-08. All results are local evidence; remote CI and deployment are not claimed.

## Automated checks

- Eight pipeline/window tests: PASS, zero skipped.
- Five integration tests against isolated ClickHouse 26.8 and PostgreSQL 17.11 containers: PASS, zero skipped.
- Four demo-shop tests: PASS, zero skipped.
- Fifteen model tests/properties from phase 1 remain passing.

The real SDK integration verifies resource mapping, IDs, exact duration, route/status, sensitive-key removal and public trace retrieval. Other integration checks cover JSON rejection, wire/expanded size limits, malformed protobuf/gzip, partial success, multiple flushes, batch replay before/after finalization and actual ClickHouse token deduplication. Pipeline tests cover atomic admission, bounded outage/recovery and acknowledgment loss. Demo tests verify seeded failure counters, JDBC delay execution, and an actual HTTP attempt for the unavailable-dependency fault.

The initial SDK test failed because Spring dependency management mixed exporter 1.57.0 with older SDK internals. Aligning the OpenTelemetry BOM version corrected the failure; the real-export test then passed. A demo counter assertion initially compared boxed Integer and Long values; it was corrected and rerun.

## Real-agent runtime

Both multi-stage images built successfully. The health-gated Compose stack started with the OpenTelemetry Java agent 2.22.0 and sampling rate 1.0. Local verification used backend port 24880 and gateway port 24881 because an existing service occupied 18080; that service was not changed.

Twenty independent HTTP checkout calls all returned 200. After the event-time minute closed:

```sh
python3 scripts/verify_vertical_slice.py --url http://127.0.0.1:24880
```

Result: **PASS**.

- Public API gateway request count: 20, exactly matching the independent caller.
- Public API edge set: exactly gateway→checkout, checkout→payment, checkout→inventory, payment→postgresql:payments, inventory→postgresql:inventory.
- Retained request trace: nine real-agent spans across all four services.
- Critical-path segment durations sum exactly to root duration.
- The first immediate metric check intentionally preceded minute closure and did not yet include the workload. SQL confirmed the correct current-minute contributions; the subsequent closed-minute public API check passed. This is the documented metric-window contract, not real-time trace latency.

Raw local JSON is under ignored `reports/runtime/vertical-traffic.json` and `vertical-api.json`.

## Remaining gates

This is a baseline vertical slice, not A1–A8 acceptance. UI, alert lifecycle, authenticated MCP tool sessions, fault acceptance, Kubernetes, CI, and separate independent review remain pending. The repository is not yet complete or ready for public exposure.
