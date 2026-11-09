# Ground-truth acceptance

Run started: 2026-11-09T16:54:15.811223Z. Protocol:fixed 300s healthy 20req/s warmup; final closed 120s rule qualification and no active incident at baseline entry. A1 observes firing through its last closed minute plus 120s window, 60s hold and two 15s evaluations. Earlier resolved warmup history remains visible but is excluded from the baseline. Then full A1–A8. Local real-agent Compose stack with fresh databases; sampling rate 1.0. Source has not been committed; runtime image IDs are recorded in `reports/runtime/acceptance-images.json`.

| Scenario | Result | Evidence |
|---|---|---|
| A1 | NOT RUN |  |
| A2 | NOT RUN |  |
| A3 | NOT RUN |  |
| A4 | NOT RUN |  |
| A5 | NOT RUN |  |
| A6 | NOT RUN |  |
| A7 | NOT RUN |  |
| A8 | NOT RUN |  |

Warmup: FAIL:org.opentest4j.AssertionFailedError: Insufficient k6 VUs invalidate arrival-rate scenario ==> expected: <0> but was: <266>

## Independent load measurements

| Load | Effective from | Effective to | Requests | 5xx | k6 p95 ms | k6 p99 ms |
|---|---|---|---:|---:|---:|---:|

Counts compare identical checkout traffic populations. Minute-aligned measurement intervals include their boundary idle periods. Histogram error and client/server latency boundary differences are separate contracts. These tests do not establish production, cloud, crash recovery or scale behavior. Raw k6 reports and logs are retained locally under ignored `reports/runtime/`.
