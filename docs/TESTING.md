# Testing and evidence

Use current worktree files and fresh command output as authority. Reports describe what ran locally. CI workflow definitions are not evidence that remote jobs passed.

## Fast and integration checks

Prerequisites: Java 21, Node 24.14.1 (Node 22.23.2 also passed local frontend checks), Docker/Compose and approximately 6 GiB free Docker memory. Docker must be available to Java Testcontainers. macOS/Homebrew users can select Java 21 with `export JAVA_HOME="$(brew --prefix openjdk@21)"`.

```sh
./gradlew check -x :tests:acceptance:test --no-daemon
TRACESCOPE_ACCEPTANCE=true ./gradlew :tests:acceptance:test --tests dev.tracescope.acceptance.HarnessPolicyTest --no-daemon
npm --prefix web ci --no-fund --no-audit
npm --prefix web run format:check
npm --prefix web run build
npm --prefix web test
npm --prefix web audit --omit=dev
```

Expected: model/property, pipeline, real ClickHouse/PostgreSQL integration, MCP client/session and demo-shop tests pass; frontend build/tests/format checks pass. Java XML reports live under each module's `build/test-results/test`.

With Colima, set the Docker endpoint exported by your local context and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` if needed. This is environment configuration, not application behavior. No Docker failure is treated as a passing integration test.

## Full ground-truth acceptance

Build images before measuring and leave enough memory for the isolated stack; stop another running TraceScope stack if necessary. Install Python 3 and Chromium for Playwright:

```sh
make build
npm --prefix web ci --no-fund --no-audit
cd web && npx playwright install chromium && cd ..
./scripts/acceptance.sh
```

This is a sustained suite, not a smoke check. It begins with a fixed 300-second healthy 20-request/s warmup, validates zero drops/transport/HTTP failures, and excludes that interval from the baseline. A failed warmup invalidates the run. Its final 120-second window must satisfy the actual enabled rules, including minimum traffic and healthy thresholds, with no open incidents at the measurement boundary. Host paging is recorded as context, not an additional acceptance gate. It includes a five-minute 20-request/s baseline, payment and JDBC latency, inventory error injection, failed downstream calls, two recovery runs and sparse-traffic alert guards. Each load runs through real instrumented HTTP services; independent k6 counters/quantiles are compared through public REST APIs. The load preallocates 100 virtual users at 20 requests/second; any dropped iterations or transport errors invalidate the measurement. The script creates fresh uniquely named Compose volumes and removes only that test stack on exit. `KEEP_ACCEPTANCE=1` preserves a failed stack for diagnosis. The application demo volumes are not deleted.

Expected: A1–A8 pass in `reports/acceptance.md`, followed by all three Chromium tests. Raw k6 JSON, container logs, image IDs and browser artifacts live under ignored `reports/runtime/`. `SKIP_BROWSER=1` is available for diagnosis only and does not constitute the full acceptance gate.

Minute boundaries and ingest/export/flush delays are part of the measurement contract. Alert firing/recovery deadlines account for those delays and persisted for-duration timers. Recovery p95 may be faster than baseline; it must not exceed 110% of baseline. Quantile accuracy tests compare identical span samples. The k6 comparison separately measures client/server boundary agreement for checkout-only traffic.

## Browser checks

With the app running and its endpoint URLs set correctly:

```sh
python3 scripts/prepare-browser-demo.py
WEB_URL=http://localhost:3000 npm --prefix web run test:e2e
```

The preparation helper creates twenty actual delayed-payment traces, clears the fault in a finally block, and waits for the measurement minute. The browser suite exercises keyboard map navigation, service detail, trace waterfall/attributes, alert preview/create/edit/delete, Escape dismissal and narrow-screen overflow. It does not claim comprehensive accessibility certification.

## Kubernetes

```sh
python3 scripts/install-local-tools.py
make build
./scripts/verify-kind.sh
```

Expected: Helm lint/render, ready installation, eight non-root pods with resources, one server replica, a successful real 60-second k6 Job, correct public service-map edges, functioning packaged UI/proxy and a healthy writer. The fresh test cluster and isolated kubeconfig are removed by default. `KEEP_KIND=1` retains them for manual use. This does not establish a cloud deployment, disaster recovery or high availability.

## CI

The workflow runs Java/integration and frontend checks on pushes and pull requests. Full acceptance and kind run on main or explicit workflow dispatch. Reports are uploaded as run artifacts; CI does not commit generated reports back into the repository.

## Evidence limits

Fixture/property tests establish behavior for their covered invariants. Real SDK tests establish OTLP/protocol integration. Real-agent Compose tests establish local cross-service behavior and measurements under the tested faults. kind establishes local Kubernetes packaging. A recorded agent session demonstrates a tool-assisted investigation; it is not an LLM accuracy benchmark. None of these implies production telemetry quality, crash durability, distributed-scale operation or untested network-partition recovery.

The prospective 300-second warmup budget includes 90 seconds for initialization, a 120-second alert window, a 60-second recovery hold and two 15-second evaluations. This budget does not guarantee qualification: the actual final window and boundary state must pass. Earlier startup incidents remain visible and are excluded only when resolved before baseline starts. A1 observes alert history through 210 seconds after its five-minute load (window + hold + two evaluations), rejecting any incident active at entry or first firing through that observation boundary. Three pure harness policy tests cover historical, overlapping and delayed firing intervals. Earlier failed attempts remain archived under their original protocols.
