# Engineering decisions

## Ownership and failure boundaries

One server owns admission, deduplication, trace assembly and queued writes. A single synchronization boundary makes whole-batch reservation and aggregate snapshots atomic with respect to admission. Database I/O runs outside that boundary. A second replica would break process-local ownership; the chart therefore enforces one replica and a Recreate rollout.

An OTLP 200 means admission to memory. A retryable rejection contributes nothing. Storage failures retain the same bounded writer frame and table-specific insert tokens; acknowledgment loss can be retried without intentionally duplicating an aggregate block. ClickHouse deduplication and ingress identities have finite windows. Neither supplies durable ingress or replay after restart. Cross-table writes are not atomic.

## Measurements

The agent creates spans; TraceScope maps, validates, assembles, aggregates and queries them. Application RED metrics count SERVER/CONSUMER spans and parentless INTERNAL transactions before trace sampling. Outbound CLIENT roots, including startup JDBC calls, are not received application requests. A checkout handled by four services produces four separate service-request observations.

Histograms merge duration counts, never percentile values. With 900 observations at 100 ms and 100 at 1,000 ms, the combined nearest-rank p95 is 1,000 ms; averaging the two individual p95 values gives an incorrect 550 ms. The fixed finite buckets provide approximately 4.881% relative error; censored underflow/overflow values are qualified separately.

Queries use effective closed UTC minute windows. RPS divides received counts by the effective interval, including idle periods. Empty series buckets have zero received throughput and unknown latency/error rate. They cannot distinguish an idle application from missing telemetry. k6 observes client-side duration, so agreement with gateway span latency is a separate empirical check rather than the histogram's mathematical error guarantee.

## Trace attribution

Root/idle and maximum-age finalization bound memory and waiting. They do not prove completeness. Late kept spans revise stored metadata and lookup bounds, while late dependency contributions are omitted. Dropped traces cannot be reconstructed from a later error. Persisted incompleteness remains visible even when the available tree has no missing parent links.

The critical path attributes synchronous elapsed time. Overlapping children do not add wall time. Display clock adjustment is flagged and never changes raw timestamps or durations. The deepest observed error is a failure candidate, not proof of causal origin in arbitrary distributed or asynchronous systems.

Known unavailable demo targets use distinct failed HTTP destinations, so payment failure cannot masquerade as inventory failure. HTTP/JDBC work and fault controls are real; generated traffic supplies a known diagnosis for validation.

## Alert and agent boundaries

Alert timers and incidents persist in PostgreSQL. Hysteresis and minimum traffic suppress noise; absent data cancels pending/recovery timers and leaves firing incidents open. Configuration changes cannot inherit an old condition's pending duration. A database transaction lock serializes automatic and manual rule creation at the supported capacity.

MCP tools are authenticated and read-only, share the REST query layer, and return bounded JSON summaries with an attribute allowlist. Sensitive-key filtering is not general content/PII sanitization. A recorded investigation demonstrates a particular tool-assisted diagnosis, not an agent accuracy benchmark.

## Deployment evidence

Compose and kind use the same application images. A local Kubernetes pass establishes packaging, permissions, readiness, storage mounting and behavior under its tested traffic. It does not establish cloud operation, high availability, backups, production security or scale. CI definitions establish intended automation; only actual workflow results can establish a remote CI pass.
