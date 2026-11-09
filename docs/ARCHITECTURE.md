# Architecture

One Java 21/Spring Boot server owns OTLP ingest, bounded trace assembly, received-request and edge aggregation, ClickHouse writes/querying, PostgreSQL alert state and read-only MCP tools. React/D3 uses the same public REST query layer. The four demo roles share one image and use the real OpenTelemetry Java agent; HTTP and JDBC instrumentation supplies all demo spans.

## Processing boundaries

1. Ingest permits four concurrent parsed requests, validates protobuf and timestamps, caps compressed/expanded sizes, redacts sensitive keys and projects bounded span attributes.
2. Admission reserves capacity for the whole unique valid batch. A retryable rejection contributes nothing. Duplicate identities are removed before counters and trace assembly.
3. Entry-span RED contributions accumulate independently of sampling. Outbound startup CLIENT/PRODUCER roots and health/control routes are excluded from application request counters.
4. Idle/max-age finalization builds a tree and derives dependency edges before selecting retained traces. Known service targets support failed client calls with no server child.
5. Immutable writer frames preserve table order and retry identity. A failed frame remains at the front of a bounded queue; processing does not silently create an unlimited outage backlog.
6. Queries combine partial histogram/counter rows over effective closed UTC minute windows. Trace lookup uses a versioned ID/time index, then a partition/bloom-filter-assisted span query.
7. Persisted alerts evaluate closed windows, apply traffic guards, sustain pending/recovery timers and attach retained exemplars when available. Condition edits reset open incidents; name-only edits preserve timers.
8. MCP tools use the query layer, return bounded summaries and expose only allowlisted attributes. They cannot inject faults or mutate rules.

## Persistence

ClickHouse stores spans, trace summaries, lookup bounds and partial metric rows. Trace summaries and lookup bounds use versioned ReplacingMergeTree tables without date partitions so earlier late spans can move trace start times safely. Span identity/timestamp fields remain stable. Aggregate tables use explicitly enabled non-replicated insert deduplication and stable retry tokens.

PostgreSQL separates the alert database from payment/inventory demo databases. Flyway manages rules/events; demo application startup creates its small transactional tables. A per-rule unique index prohibits multiple open alert events. Rule-row locking fences evaluator writes against configuration edits.

Cross-table ClickHouse writes are not atomic. A crash can leave partially persisted trace state. No log or database transaction supplies exactly-once ingress or durable recovery. [DATA_MODEL.md](DATA_MODEL.md) specifies the full limits.

## Deployment

Compose publishes only loopback application ports. Helm deploys one server replica with Recreate rollout, one web deployment, four shop deployments and two database StatefulSets. Each pod runs without root, privilege escalation or service-account token mounting; every container has resource requests/limits. Local kind verification uses an isolated kubeconfig and project-owned cluster.

Horizontal scaling is unsupported: ingest dedupe, active trace ownership and writer state are process-local. Production distribution would require explicit trace routing/ownership and durable contribution identities, not merely a higher replica count.
