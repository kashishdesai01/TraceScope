# Implementation and verification ledger

The scope is a single-node APM application: four real Java-agent-instrumented demo roles, OTLP ingest, all-request RED metrics, dependency edges, retained traces and synchronous critical path, React/D3 investigation, persisted alerts, read-only authenticated MCP, Compose and local kind/Helm. No production, scale, cloud, durability or real-user claim is implied by local tests.

## Phases

1. Contracts and model: pinned Java 21 build, protobuf mapping, histogram boundaries, trace trees, synchronous critical path, sampling, redaction; unit/property tests.
2. Vertical slice and pipeline: real agent demo, bounded atomic admission and dedupe, stable aggregate flush retries, ClickHouse storage/query; integration and outage tests.
3. Product: React/D3 pages, persisted alert lifecycle and exemplars, authenticated bounded MCP; frontend and contract tests.
4. Deployment and ground truth: Compose, k6 checkout measurements, complete acceptance suite, Helm/kind and CI.
5. Independent read-only engineering review, remediation, rerun verification, final requirement audit and reader-facing docs.

Each phase records actual commands and results here or under reports. Pending work is not a pass.

## Revised contracts

- Admission is atomic per valid batch; a retryable rejection contributes nothing. Deduplication by trace/span identity occurs before aggregation, bounded for ten minutes. Exhausted dedupe capacity rejects rather than evicts unexpired identities. Restart/expired-window replay can count again.
- One server replica only. Bounded active spans, retained estimated bytes, aggregation keys, writer work and decision entries. Storage outages exert admission backpressure; no unbounded queue.
- Aggregate rows carry stable flush identity with explicit ClickHouse insert deduplication for non-replicated MergeTree. Retries reuse identical content and identity. No exactly-once claim after restart or dedupe expiry.
- RED queries expose effective UTC minute-aligned windows. Alerts evaluate closed minute windows. Histogram error is bounded only within finite buckets; zero, underflow and overflow are explicit.
- Finalization is an idle heuristic, not proof of completeness. Missing parents/roots and max age are flagged. Late retained spans revise trace metadata and lookup bounds; late data on dropped traces does not retroactively reconstruct traces. Edges omit post-finalization contributions and disclose that limit.
- Known service addresses resolve failed client spans to service edges without a server child. Fault injection performs actual failed HTTP work.
- Alert pending and recovery timers persist. No data cancels pending and recovery timers, leaves firing incidents open, and is visible. Deadline tests include window, ingest/flush and evaluator delay. Exemplars can be unavailable.
- Critical path is a synchronous timing attribution heuristic. Raw clocks remain unchanged, display adjustment is flagged, and asynchronous causal analysis is out of scope.
- Histogram correctness uses exact percentiles of identical span samples. k6 comparisons select checkout only and account separately for client/server measurement boundaries.

## Required evidence

- U1–U6: arrival/duplicate invariance, missing/cyclic parents, sequential/parallel/skew fixtures, histogram merge and boundary properties, entry/failed-edge semantics, sampler, redactor.
- I1–I4 plus replay/outage: real SDK protobuf/gzip, validation/limits/partial success, exact counts across flushes, duplicate retries before and after completion, bounded outage and restored writer.
- A1–A8: baseline exact request/edge counts, payment latency, JDBC delay, inventory failure ground truth, unavailable dependency, recovery with valid deadlines, minimum traffic, checkout-only k6 agreement.
- V1–V4: layout, highlighting, map semantics, alert validation; browser smoke of real investigation flow.
- M1–M3: initialized protocol sessions and tools, bounded valid JSON, allowlisted attributes, expected latency attribution, token rejection.
- K1: lint/render, install into kind, real traffic, public service-map assertion.
- Separate independent review and remediation log; no claim of independent review for author checks.

## Phase 1 evidence (2026-11-08)

- Gradle 8.14.3 wrapper generated; Java 21 toolchain; Spring Boot 3.5.10 and Spring AI 1.1.2 resolve together.
- Initial model suite passed, including 200 jqwik merge/quantile trials, 40 seeded distribution samples, and 100k sampling decisions. Mapper conformance cases added for the next run.
- Current phase remains in verification until the complete model suite reruns. No runtime, UI, acceptance or Kubernetes result is claimed.

Phase 1 gate passed: complete model suite including mapper and parent-overrun regression passed on Java 21. Server dependency compilation passed. Proceeding to phase 2; runtime and storage behavior remain unverified.

Phase 3 author review: real-agent startup JDBC CLIENT roots inflated payment/inventory request denominators. Entry metrics now exclude root CLIENT/PRODUCER spans while retaining SERVER, CONSUMER and root INTERNAL transactions. Added regression coverage. Browser checks passed before this refinement; mobile map was made horizontally scrollable after visual inspection. Rule condition edits now reset open incidents so an old persistence timer cannot satisfy a new condition. Coarse series boundary rates use the actual bucket/window intersection, and future query end times are capped at the latest closed minute. These refinements require regression reruns before the phase gate is final.

Phase 3 gate passed: 45 Java tests/properties, 15 Vitest checks, and three real Chromium tests passed. Production frontend and all three Docker images built; the map investigation and rule editor ran against real-agent traces. Evidence: reports/phase-3.md. Phase 4 starts with full ground-truth fault acceptance, immutable image pins, Helm/kind and CI tooling. Independent review remains pending after implementation.

Final source review remediation: R1–R5 are resolved and independently re-reviewed. The final local Java gate passed54 tests (20 model,29 server,5 demo) including10 real-storage integration tests; frontend19 tests and production build pass. The new capacity race test exposed a test helper's fixed-sleep assumption; it now waits for actual persistence. Standard Spring context teardown replaces manual closure. Helm lint/render and runtime npm audit (zero advisories) pass. Current-image Chromium verification passed all three tests, including map drag bounds, application trace search, span inspection, rule editing and mobile layout. Both local Codex MCP investigations are captured and documented; those captures predate the G1 configuration change. The current G1 warmup failed alert qualification and stopped A1–A8 before measurement. Runtime kind verification passed: eight non-root pods, real traffic, exact public graph and packaged UI proxy. The subsequent prospective300-second warmup qualification and210-second post-baseline alert observation were independently reviewed; all three harness policy tests passed. The revised attempt5 failed its zero-drop warmup prerequisite (266 drops and2 failed HTTP requests/5,734); A1–A8 did not run. The test stack is stopped and evidence retained. Full acceptance remains outstanding. Earlier acceptance results are archived with their failures and invalidated A8 result intact. See reports/verification.md for current evidence.

R6 final source correction preserves valid descendant parent links and depths beneath a missing ancestor while retaining incompleteness warnings. A reproducing regression first failed and then passed; the complete55-test Java gate (21 model,29 server,5 demo) passed with zero failures/errors/skips. Independent source rereview passed. Runtime images built before this correction are historical; the affected server image was rebuilt, its exact saved partial trace passed API hierarchy checks and all three browser tests passed. Full fault acceptance and current-image kind remain outstanding.
