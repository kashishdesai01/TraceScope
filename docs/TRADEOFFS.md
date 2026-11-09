# Tradeoffs

- **One server:** keeps trace ownership, dedupe and pipeline synchronization understandable. It prevents horizontal scaling and provides no availability across a server failure.
- **Memory before storage:** removes a broker and consumer coordination from the local system. Acknowledgment is not durability; restart and expired dedupe windows can affect counts. A durable log needs contribution identities and replay-safe writes, not just another queue.
- **Received-span RED:** avoids bias from TraceScope tail sampling, but cannot recover upstream sampled, dropped or unexported spans. Each service's requests are a distinct population.
- **Minute histograms:** merge efficiently and avoid averaging percentiles. They require explicit minute-aligned windows, finite-bucket error bounds and visible censored values. Coarse series use actual boundary intersections for rates.
- **Idle finalization:** supports out-of-order batches without waiting indefinitely. It cannot prove completeness. Late kept spans revise summaries/index bounds; late dependency contributions are omitted and dropped traces cannot be reconstructed.
- **Synchronous critical path:** explains nested HTTP/JDBC latency and overlapping siblings without double-counting wall time. It is a timing heuristic, not a general causal graph for asynchronous systems.
- **Static MCP/chaos tokens:** make local read-only investigation and controlled faults easy to use. They are not a user identity system. Attribute allowlisting and sensitive-key matching do not constitute general PII/content sanitization.
- **Local kind:** validates packaging, probes, permissions, resource declarations, storage and API behavior with real traffic. It supplies no cloud, disaster-recovery or production-scale evidence.

Potential extensions are durable ingest/replay with idempotent contributions, configurable project/target identity, explicit tenant quotas/authentication, and operational deployment design. They are outside the current verified contract and should not be inferred from the existence of a Helm chart.

Application images explicitly select G1 with a 64 MiB initial heap while retaining the 70%-of-container maximum heap. This follows diagnostics of the small-container JVM selecting Serial GC with an 8 MiB initial heap and exhibiting long collection pauses. G1 trades concurrent CPU and bookkeeping for shorter collection pauses; it does not provide a hard pause bound ([Java 21 G1 guide](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)). These settings require measured container headroom and do not compensate for host swapping.
