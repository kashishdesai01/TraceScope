# Data and measurement contracts

TraceScope accepts OTLP/HTTP protobuf with identity or gzip encoding. An HTTP 200 acknowledges in-memory admission, not durable storage. A retryable 503 rejects the entire valid batch without contributing counters. Invalid spans are reported through OTLP partial success, which exporters must not retry. HTTP status attributes outside 100–599 (or malformed values) map to unknown status 0; explicit OTLP error status is preserved. Retention admits timestamps from the last seven days and at most five minutes in the future.

## Request metrics

Application entry spans are SERVER, CONSUMER, or parentless INTERNAL transactions. Parentless CLIENT/PRODUCER spans are outbound work, including startup JDBC calls, and are excluded. Actuator health and chaos-control routes are excluded from application RED counters. Each admitted `(trace_id, span_id)` contributes at most once while its ten-minute process-local identity is retained. The identity cache rejects new data when full; it does not evict live identities. Restart or replay after expiry can count again. Upstream sampling or exporter loss makes these received-span metrics incomplete relative to application traffic.

Counts, errors and fixed-layout latency histograms are aggregated before tail sampling. Different service counts describe different request populations: four services handling one checkout are four service requests, not four user requests. Fleet-wide histogram merging is mathematically valid but measures that combined service-request population.

The API reports effective UTC minute-aligned windows and excludes the current partial minute. Both requested boundaries are rounded down, and the end is capped at the latest closed minute. Time series include empty buckets explicitly: zero received requests/throughput and unknown (null) latency/error rate. These gaps do not distinguish idle traffic from missing telemetry. Charts preserve UTC elapsed time and break latency/error lines across gaps. Coarse time-series boundary buckets divide counts by their intersection with the effective query window. RPS uses the resulting interval length. Alert evaluation uses closed minute windows as well. Late arrivals can revise historical buckets; these values are not final event-time watermarks.

## Histogram

There are 148 counters. Underflow covers durations below 0.1 ms, including zero. Finite bucket boundaries have ratio 1.1. Overflow begins at `0.1 * 1.1^146`, approximately 110,492.305 ms. Nearest-rank quantiles in finite buckets use geometric midpoints, with at most approximately 4.881% relative error. Empty quantiles are null. Underflow and overflow return their boundary with an explicit range flag; they carry no finite relative-error guarantee.

Element-wise addition combines independent contributions. It does not make repeated copies of the same contribution safe. A flush snapshot has a stable token reused across retries. ClickHouse non-replicated insert deduplication is explicitly enabled with a bounded block window. The queue processes frames in order and stops at a failed frame. This is bounded retry protection, not an exactly-once or restart recovery guarantee.

## Retained traces

The sampler retains observed error traces, root durations of at least 500 ms, and a deterministic hash sample. Acceptance runs use rate 1.0. This decision reflects the spans available at finalization. A late error on an already dropped trace does not reconstruct the discarded spans.

Finalization occurs after root idle time, ten seconds without a root, or a thirty-second maximum age. `known_incomplete` flags missing parents, cycles, absent roots, max-age finalization and late revisions. Missing ancestry detaches only the orphan component root; available parent links and depths inside that component are preserved, with inherited incompleteness warnings. A false flag does not prove completeness: unobserved subtrees cannot be discovered from the available parent links.

Trace detail and MCP include persisted incompleteness, finalization reason and late-span count alongside structural/timing flags. Late retained spans update span storage, counts, service membership, root details and trace-index time bounds. Trace metadata and index use a monotonic process-local revision; `FINAL` selects the latest version. Trace metadata tables are not date-partitioned because a late earlier span can move the trace start across a date boundary. Spans retain their original timestamp and date partition.

Writes to spans, metadata and index are not a multi-table transaction. A trace can be temporarily unavailable or incomplete while a frame is being persisted; retries finish the same frame. A crash during that sequence can leave partial persistent state.

## Dependency edges and timing attribution

Client/server matching identifies service edges. A failed client with a known demo target also identifies that service without requiring a server span. Database attributes identify database leaves; unknown remote addresses remain external. Edge counts include all traces available at finalization, before sampling, but omit late contributions. They need not equal request counters.

Critical path is synchronous timing attribution from nested span intervals. It does not establish arbitrary asynchronous causality or explain why a remote service was slow. Parallel siblings do not double-count wall time. Out-of-bounds child intervals are clipped for attribution and flagged approximate. Display-only CLIENT-to-SERVER skew correction shifts subtrees without changing stored timestamps or durations.

Propagated HTTP failures can mark gateway, checkout, client and leaf spans as errors. Incident summaries must distinguish the deepest observed failing dependency from earlier-starting ancestors that report its failure; calling the chronologically first error span the origin would be misleading.

## Capacity and failure limits

The single server bounds active and queued span counts, estimated retained payload bytes, per-trace span count, identity entries, aggregate keys and writer frames. Estimated payload bytes are not a guarantee about total JVM heap: parsing, serialization, libraries and object overhead require additional process memory. Ingest parsing has a separate four-request concurrency limit and compressed/expanded body caps.

Storage outages retain bounded immutable frames and then apply admission backpressure. They do not create an unbounded retry queue. Crashes lose active traces, unflushed aggregates and queued frames; during an outage, loss can cover more than the normal ten-second flush interval. Writer health and failures are exposed through API and Micrometer. No durable replay exists.

The standalone `/traces/{id}/critical-path` response describes structural/timing approximation computed from available spans. Consumers needing retained completeness and late-arrival facts should use trace detail, which supplies persisted metadata separately.

Rule creation (manual and automatic discovery) shares a PostgreSQL transaction-scoped advisory lock. The count and insert occur under that lock so the 100-active-rule cap cannot be exceeded by concurrent creators. Rule evaluation and edits do not take that creator lock.
