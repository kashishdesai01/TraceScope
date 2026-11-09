# Verification status

Local verification on 2026-11-08/09. This source folder has no Git metadata; no Git commit or remote CI result is claimed. Final runtime image identities and a source-file hash manifest are retained under ignored `reports/runtime/`.

| Gate | Current evidence |
|---|---|
| Java/model/integration | PASS:55 tests, zero failures/errors/skips;21 model,29 server,5 demo;10 real-storage integration tests |
| Acceptance harness policy | PASS:3 tests, zero failures/errors/skips; prospective 300-second qualification and baseline alert interval policy independently reviewed |
| Frontend | PASS:19 tests, production TypeScript/Vite build and formatting |
| Runtime dependency advisories | PASS:npm audit --omit=dev reported zero vulnerabilities at verification time |
| Helm static checks | PASS:lint and rendering with load generation enabled |
| Independent source review | PASS:R1–R6 remediated and separately re-reviewed; R6 full55-test Java gate passed; see [review](review.md) |
| Prior full-run A1 baseline | PASS on pre-G1 images:6,001 requests per service,5,964/6,001 complete nine-span traces, exact expected dependency set, zero firing alerts |
| Prior full-run A2–A8 | A2 failed its70% criterion; A3/A4/A5/A7 passed; A6 latency recovery was not run; A8 was invalidated by a harness false-positive. Archived under ignored `reports/runtime/attempt-3/`. |
| Prior G1 attempt 4 | Its original 120-second warmup failed alert qualification:2,401 requests, zero HTTP/transport failures or dropped iterations; k6 p95 1,098.799ms; checkout latency alert fired. A1–A8 NOT RUN. See [acceptance](acceptance.md). |
| Current G1 attempt 5 | FAIL during300-second warmup:5,734 requests,266 dropped iterations,2 failed HTTP requests,zero transport failures; k6 p952,472.903ms. A1–A8 NOT RUN; archive `reports/runtime/attempt-5/`. |
| Final Chromium | PASS:3 real-data tests against rebuilt R6 server/current UI images; map drag bounds and investigation, rule preview/create/edit/delete, mobile overflow; zero flaky/skipped/unexpected tests |
| Kubernetes runtime | PASS:8 non-root pods, real60-second/1,201-request load with zero failures/drops, exact graph, UI proxy and healthy writer; see [Kubernetes verification](kubernetes.md) |
| Recorded MCP investigations | PASS: local Codex A2 and A4 investigations captured; see [MCP sessions](../docs/mcp-session.md). Captures precede the GC configuration change; final runtime verification remains pending. |
| Remote CI/publication | NOT RUN; no repository publication action authorized |

The advisory check covers npm runtime dependencies, not all Java dependencies, container packages or a security certification. Credential-signature and local Markdown-link scans passed for candidate source/documentation files; these are bounded hygiene checks.

Earlier acceptance attempts are archived locally: one was invalidated by dropped k6 iterations, and another was stopped after the rule-cap audit finding. Neither is presented as a final acceptance pass. The final suite preallocates the same100-VU ceiling while retaining20 requests/second and requiring zero dropped iterations and transport failures.

The original scope is a local single-node synthetic-data APM application. No result here implies production hosting, real-user telemetry, cloud deployment, crash durability, horizontal scaling or untested fault coverage.

Current-image warmup resource evidence shows no cgroup OOM or memory-limit events. Demo JVM peaks were approximately266–284MiB against384MiB limits. A later five-second host sample observed458.7MB swap-in and515.4MB swap-out; this confirms active host paging, not the cause of any particular trace. The G1 adjustment did not establish a latency pass. Raw evidence is retained under ignored `reports/runtime/`.

Attempt5 used a prospectively fixed 300-second warmup with actual final-window rule qualification and a 210-second post-baseline alert observation. Previously resolved startup history stays visible; active or overlapping incidents fail. Original A1–A8 numeric thresholds are unchanged. Active paging alone does not invalidate a run. The run failed its original zero-drop load prerequisite before final-window qualification. No full fault-acceptance pass is claimed. The test stack is stopped with volumes retained; memory relief was requested before another run.

R6 changes the model after the runtime images above were built: partial traces now retain valid descendant parent links beneath a missing ancestor. The complete55-test Java gate passed and the independent source rereview passed; the rebuilt server passed the exact retained-trace API check and all three browser tests. Full fault acceptance and a current-image kind rerun remain outstanding. Previously recorded runtime passes do not prove this new source revision.

R6 runtime verification used existing retained data without checkout load: the same10 raw spans and completeness metadata were unchanged, while the prior9 flattened roots became2 component roots with maximum depth3. Runtime server image is `sha256:6316814769474064e1981d2911fbfb7cab70c541699a2c979e02f7e755ed6eb9`. Browser3/3 passed with zero skipped/flaky/unexpected tests.
