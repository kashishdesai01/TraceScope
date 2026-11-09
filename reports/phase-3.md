# Phase 3 — investigation UI, alerts and MCP

Verified locally on 2026-11-08. Latest regression results: **45 Java tests/properties**, **15 frontend tests**, **3 Chromium browser tests**, zero failures or skips in these suites.

## Alerting

PostgreSQL/Flyway schema persists pending/firing/resolved events, recovery timers, no-data status and exemplars. Real-database tests verify firing, retained exemplar links, disabling, soft deletion with event history, and condition edits resetting pending timers. Name-only edits preserve timers. Pure lifecycle tests verify sustained breach and recovery duration, hysteresis gray-band behavior, and no-data transitions. Preview and CRUD work through the UI.

## MCP

All seven tools register with the Spring AI Streamable HTTP transport. A real official Java MCP client initializes an authenticated session, lists exactly the expected tool names, and invokes every tool. Each response parses as JSON within the 6,000-character cap. Separate fixtures verify payment latency attribution, 2,001-span output truncation, safe attribute projection and validation. Missing bearer credentials return 401.

This is protocol evidence. A recorded coding-agent investigation is still pending.

## UI and browser evidence

React/TypeScript production build passes. Fonts are bundled locally. The runtime image is unprivileged nginx with same-origin API proxying and a content security policy. Runtime dependency audit reported zero advisories at verification time.

Chromium ran against the packaged Compose application with real Java-agent data from an 800 ms payment fault:

1. Service map → keyboard-select payment → service detail → slow trace → payment attributes → Escape dismissal: PASS; no browser exceptions.
2. Rule creation, current-value preview, edit, deletion and native-dialog Escape behavior: PASS.
3. Narrow viewport overview with no page overflow: PASS.

Screenshots were inspected. Mobile dependency labels were too small when the full graph shrank, so the map now has a scrollable canvas. Browser tests were repeated after that fix and passed. Local screenshots and browser JSON/trace artifacts are under ignored `reports/runtime/`.

## Author-review corrections

- Root CLIENT/PRODUCER spans are outbound work, not received application requests. Real-agent JDBC startup roots exposed denominator inflation. The entry rule now includes SERVER, CONSUMER and root INTERNAL transactions; regression tests cover exclusions.
- Rule condition edits resolve existing incidents with `rule_changed`, preventing an old pending timer from satisfying a new condition. Name-only edits preserve history and timers.
- Coarse series boundary buckets use their actual intersection with the effective query window for RPS. Real ClickHouse series queries and a boundary fixture pass.
- Future query end times are capped at the latest closed minute; requested intervals over 24 hours remain invalid.
- Histogram underflow/overflow use safe threshold bounds when they can establish an alert/recovery decision; ambiguous censored values remain no data.

Full A1–A8 fault acceptance, kind/Helm, CI definitions, recorded agent use, final independent review and the final evidence audit remain pending. No production or cloud-readiness result is implied.
